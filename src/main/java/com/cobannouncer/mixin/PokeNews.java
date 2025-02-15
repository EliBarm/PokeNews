package com.cobannouncer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import eu.pb4.placeholders.api.TextParserUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Supplier;

import static net.minecraft.server.command.CommandManager.literal;

public class PokeNews implements ModInitializer {
    // Disable HTML escaping so that < and > are output as-is in JSON.
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
    private static final File CONFIG_FILE = new File("config/pokenews_config.json");
    private static Config config;
    private int tickCounter = 0;

    @Override
    public void onInitialize() {
        config = loadConfig();
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("pokenews")
                    .executes(ctx -> onPokeNews(ctx.getSource()))
                    .then(literal("reload")
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(ctx -> {
                            config = loadConfig();
                            tickCounter = 0;
                            String combined = config.messagePrefix + " Config reloaded. Timer reset!";
                            ctx.getSource().sendFeedback((Supplier<Text>) () -> TextParserUtils.formatText(combined), false);
                            return 1;
                        })
                    )
            );
        });
    }

    private void onTick(MinecraftServer server) {
        tickCounter++;
        int remaining = config.announceInterval - tickCounter;
        if (config.debugMode && remaining % 20 == 0) {
            System.out.println("[DEBUG] TickCounter: " + tickCounter + " | Remaining: " + remaining);
        }
        if (remaining == config.countdownStart) {
            broadcastMessage(server, config.messagePreEvent);
        } else if (remaining == config.finalCountdownStart + 40) {
            broadcastMessage(server, config.messageFinal3);
        } else if (remaining == config.finalCountdownStart + 20) {
            broadcastMessage(server, config.messageFinal2);
        } else if (remaining == config.finalCountdownStart) {
            broadcastMessage(server, config.messageFinal1);
        } else if (remaining == 0) {
            broadcastMessage(server, config.messageStolen);
            server.getCommandManager().executeWithPrefix(
                server.getCommandSource().withLevel(4),
                config.killCommand
            );
            if (config.debugMode) {
                System.out.println("[DEBUG] Executed: " + config.killCommand);
            }
            tickCounter = 0;
        }
    }

    private int onPokeNews(ServerCommandSource source) {
        int remaining = config.announceInterval - tickCounter;
        if (remaining < 0) {
            remaining = 0;
        }
        int secondsRemaining = remaining / 20;
        String timeString = getTimeString(secondsRemaining);
        String msg = (remaining > config.pendingThreshold)
            ? config.messageGracePeriod.replace("{time}", timeString)
            : config.messagePending.replace("{time}", timeString);
        String combined = config.messagePrefix + msg;
        source.sendFeedback((Supplier<Text>) () -> TextParserUtils.formatText(combined), false);
        return 1;
    }

    private void broadcastMessage(MinecraftServer server, String rawMessage) {
        String combined = config.messagePrefix + rawMessage;
        Text parsed = TextParserUtils.formatText(combined);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(parsed, false);
        }
    }

    private static Config loadConfig() {
        if (!CONFIG_FILE.exists()) {
            Config defaults = new Config();
            saveConfig(defaults);
            return defaults;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            return GSON.fromJson(reader, Config.class);
        } catch (IOException | JsonIOException | JsonSyntaxException e) {
            e.printStackTrace();
            Config defaults = new Config();
            saveConfig(defaults);
            return defaults;
        }
    }

    private static void saveConfig(Config cfg) {
        CONFIG_FILE.getParentFile().mkdirs();
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(cfg, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String getTimeString(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else {
            return String.format("%dm %ds", minutes, seconds);
        }
    }

    public static class Config {
        public int announceInterval = 72000;
        public int countdownStart = 600;
        public int finalCountdownStart = 60;
        public int pendingThreshold = 12000;
        public String killCommand = "pokekill";
        public boolean debugMode = false;

        // Configured with PlaceholderAPI supported tags like <#RRGGBB> and <bold>
        public String messagePrefix = "<#FFFFFF><bold>[<#FF1F1F>P<#FF1F1F>o<#FF3F3F>k<#FF5F5F>é<#FF7F7F>N<#FF9F9F>e<#FFBFBF>w<#FFDFDF>s<#FFFFFF>] <#FFFFFF>";
        public String messagePreEvent = "<#FF7F7F><bold>Team Rocket is plotting to steal wild Pokémon in <#FFFFFF>30 seconds!";
        public String messageFinal3 = "<#FF5F5F><bold>Team Rocket will steal all wild Pokémon in <#FFFFFF>3 seconds!";
        public String messageFinal2 = "<#FF4F4F><bold>Team Rocket will steal all wild Pokémon in <#FFFFFF>2 seconds!";
        public String messageFinal1 = "<#FF3F3F><bold>Team Rocket will steal all wild Pokémon in <#FFFFFF>1 second!";
        public String messageStolen = "<#FF1F1F><bold>Team Rocket has stolen all the wild Pokémon!";
        public String messageGracePeriod = "<#FFBFBF><bold>No major news. Team Rocket is counting Pokémon! Next wipe in <#FFFFFF>{time}";
        public String messagePending = "<#FF9F9F><bold>Team Rocket is lurking nearby and will steal wild Pokémon soon! Time left: <#FFFFFF>{time}";
    }
}
