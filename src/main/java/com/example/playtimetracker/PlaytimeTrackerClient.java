package com.example.playtimetracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlaytimeTrackerClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlaytimeTrackerClient.class);
    private static final Pattern PLAYTIME_PATTERN = Pattern.compile("Общее время в игре:\\s*(.+)");
    private static final int SCAN_INTERVAL_TICKS = 200;

    private final Set<String> requested = new HashSet<>();
    private final Set<String> completed = new HashSet<>();
    private final Deque<String> pendingQueue = new ArrayDeque<>();
    private int tickCounter = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> handlePlaytimeMessage(message));
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.getNetworkHandler() == null) {
            return;
        }

        tickCounter++;
        if (tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        for (PlayerListEntry entry : client.getNetworkHandler().getPlayerList()) {
            String name = entry.getProfile().getName();
            if (completed.contains(name) || requested.contains(name)) {
                continue;
            }
            if (hasDonation(entry)) {
                continue;
            }

            client.player.networkHandler.sendChatCommand("playtime " + name);
            requested.add(name);
            pendingQueue.add(name);
        }
    }

    private boolean hasDonation(PlayerListEntry entry) {
        String name = entry.getProfile().getName();
        String displayName = extractDisplayName(entry, name);
        String lowered = displayName.toLowerCase(Locale.ROOT);
        if (containsDonationMarker(lowered)) {
            return true;
        }

        Team team = entry.getScoreboardTeam();
        if (team == null) {
            return false;
        }

        String teamPrefix = team.getPrefix().getString().toLowerCase(Locale.ROOT);
        String teamSuffix = team.getSuffix().getString().toLowerCase(Locale.ROOT);
        return containsDonationMarker(teamPrefix) || containsDonationMarker(teamSuffix);
    }

    private String extractDisplayName(PlayerListEntry entry, String fallback) {
        Text display = entry.getDisplayName();
        if (display == null) {
            return fallback;
        }
        return display.getString();
    }

    private boolean containsDonationMarker(String value) {
        return value.contains("донат") || value.contains("donat") || value.contains("donate");
    }

    private void handlePlaytimeMessage(Text message) {
        String text = message.getString();
        Matcher matcher = PLAYTIME_PATTERN.matcher(text);
        if (!matcher.find()) {
            return;
        }
        if (pendingQueue.isEmpty()) {
            return;
        }

        String playerName = pendingQueue.poll();
        String playtime = matcher.group(1).trim();
        completed.add(playerName);
        writePlaytimeFile(playerName, playtime);
    }

    private void writePlaytimeFile(String playerName, String playtime) {
        MinecraftClient client = MinecraftClient.getInstance();
        Path output = client.runDirectory.toPath().resolve("playtime_report.txt");
        String line = playerName + " - " + playtime + System.lineSeparator();
        try {
            Files.writeString(output, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            LOGGER.error("Failed to write playtime file", error);
        }
    }
}
