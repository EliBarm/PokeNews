package com.cobannouncer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * CobblemonAnnouncer Mod
 * Author: Elyygee
 * This mod is open-source.
 *
 * Restores the older two-pass approach:
 *   1) Convert "&#RRGGBB" to '§x§R§R§G§G§B§B'
 *   2) Convert standard format codes like &l, &c, etc. to '§l', '§c'.
 * This ensures &l (bold), &r, &c, etc. show up as styling in chat.
 */
public class CobblemonAnnouncer implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File("config/pokenews_config.json");
    private static Config config;
    private int tickCounter = 0;

    // Regex to find hex color codes in the format: "&#RRGGBB" (case-insensitive)
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("(?i)&#([0-9a-f]{6})");

    @Override
    public void onInitialize() {
        config = loadConfig();
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                literal("pokenews")
                    .executes(ctx -> onPokeNews(ctx.getSource()))
                    .then(literal("reload")
                        // Only allow OP players (permission level >= 4) to reload config
                        .requires(source -> source.hasPermissionLevel(4))
                        .executes(ctx -> {
                            config = loadConfig();
                            tickCounter = 0;

                            // Build the reload message using the prefix from config
                            // This will parse color codes and styling.
                            MutableText reloadMsg = parseColorAndFormatCodes(config.messagePrefix + " Config reloaded. Timer reset!");

                            ctx.getSource().sendFeedback((Supplier<Text>) () -> reloadMsg, false);
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
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withLevel(4), config.killCommand);
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

        // Decide whether we're in "grace" or "pending"
        String msg = (remaining > config.pendingThreshold)
            ? config.messageGracePeriod.replace("{time}", timeString)
            : config.messagePending.replace("{time}", timeString);

        // Combine prefix + the message
        MutableText feedbackMsg = parseColorAndFormatCodes(config.messagePrefix + msg);
        source.sendFeedback((Supplier<Text>) () -> feedbackMsg, false);
        return 1;
    }

    /**
     * Broadcast final styled message with prefix in yellow bold text.
     */
    private void broadcastMessage(MinecraftServer server, String rawMessage) {
        // Combine prefix + main message.
        // We'll parse the prefix with color codes.
        // Then append the message in bold yellow.
        MutableText prefix = parseColorAndFormatCodes(config.messagePrefix);
        MutableText main = Text.literal(rawMessage)
            .setStyle(Style.EMPTY.withBold(true)
                                 .withColor(TextColor.fromRgb(0xFFFF00)));
        MutableText combined = prefix.append(main);

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(combined, false);
        }
    }

    /**
     * parseColorAndFormatCodes:
     *  1) Replaces "&#RRGGBB" with '§x§R§R§G§G§B§B'.
     *  2) Replaces any leftover codes like &l, &c, &r with their '§' equivalents.
     * This ensures that &l (bold) or &c (red) appear properly.
     */
    private static MutableText parseColorAndFormatCodes(String input) {
        if (input == null) {
            return Text.literal("");
        }
        
        // 1) Convert hex color codes from "&#RRGGBB" -> '§x§R§R§G§G§B§B'
        Matcher matcher = HEX_COLOR_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            // Build the replacement: §x§R§R§G§G§B§B
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                replacement.append("§").append(c);
            }
            matcher.appendReplacement(sb, replacement.toString());
        }
        matcher.appendTail(sb);
        String afterHex = sb.toString();

        // 2) Convert leftover format codes like &l, &r, &c, etc. to '§l', '§r', '§c'
        // This also covers &0-9, &k, &o, &n, &m
        String finalStr = afterHex.replaceAll("(?i)&([0-9A-FK-OR])", "§$1");

        // Return a literal text. This uses the legacy '§' codes, which modern MC should interpret.
        return Text.literal(finalStr);
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
        public int announceInterval = 36000;
        public int countdownStart = 600;
        public int finalCountdownStart = 60;
        public int pendingThreshold = 12000;
        public String killCommand = "pokekill";
        public boolean debugMode = false;

        // Example prefix with various codes.
        public String messagePrefix = "&#ffffff&l[&#dd959c&lP&#dd959c&lo&#dd959c&lk&#dd959c&lé&#ffffff&lN&#ffffff&le&#ffffff&lw&#ffffff&ls&#ffffff&l] &#3b4cca&l";

        public String messagePreEvent = "Team Rocket is plotting to steal wild Pokémon in 30 seconds!";
        public String messageFinal3 = "Team Rocket will steal all wild Pokémon in 3 seconds!";
        public String messageFinal2 = "Team Rocket will steal all wild Pokémon in 2 seconds!";
        public String messageFinal1 = "Team Rocket will steal all wild Pokémon in 1 second!";
        public String messageStolen = "Team Rocket has stolen all wild Pokémon!";
        public String messageGracePeriod = "No major news. Team Rocket is counting Pokémon! Next wipe in &#ffffff&l{time}";
        public String messagePending = "Team Rocket is lurking nearby and will steal wild Pokémon soon! Time left: &#ffffff&l{time}";
    }
}
