package com.antielytratarget.managers;

import com.antielytratarget.models.FlagLogEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProfileHistoryRegressionSimulation {

    private ProfileHistoryRegressionSimulation() {}

    public static void main(String[] args) {
        historyMustStayBoundedDuringLoading();
        playersMustHaveIndependentWindows();
        zeroAndNegativeLimitsMustKeepNoEntries();
        malformedLinesMustNotCreateProfiles();
        loweredLimitMustDiscardTheOldestEntries();
        System.out.println("Profile history regression simulation passed.");
    }

    private static void historyMustStayBoundedDuringLoading() {
        UUID uuid = UUID.randomUUID();
        TrackingList history = new TrackingList();
        Map<UUID, List<FlagLogEntry>> profiles = new HashMap<>();
        profiles.put(uuid, history);
        int limit = 25;
        for (int count = 1; count <= 10_000; count++) {
            require(ProfileManager.appendLogLine(line("Alice", count), name -> uuid, profiles, limit),
                    "A valid history entry must be counted even when older entries are evicted");
        }
        require(history.peakSize <= limit,
                "Loading must not materialize the entire history before applying the retention limit");
        require(history.size() == limit && history.get(0).getFlagCount() == 9_976
                        && history.get(limit - 1).getFlagCount() == 10_000,
                "Retain exactly the latest entries in their original order");
    }

    private static void playersMustHaveIndependentWindows() {
        Map<String, UUID> identities = Map.of("Alice", UUID.randomUUID(), "Bob", UUID.randomUUID());
        Map<UUID, List<FlagLogEntry>> profiles = new HashMap<>();
        for (int count = 1; count <= 8; count++) {
            ProfileManager.appendLogLine(line("Alice", count), identities::get, profiles, 3);
            ProfileManager.appendLogLine(line("Bob", count + 20), identities::get, profiles, 3);
        }
        require(profiles.size() == 2 && profiles.get(identities.get("Alice")).size() == 3
                        && profiles.get(identities.get("Bob")).get(0).getFlagCount() == 26,
                "Each player's retention window must be independent");
    }

    private static void zeroAndNegativeLimitsMustKeepNoEntries() {
        for (int limit : new int[] {0, -1, Integer.MIN_VALUE}) {
            UUID uuid = UUID.randomUUID();
            Map<UUID, List<FlagLogEntry>> profiles = new HashMap<>();
            require(ProfileManager.appendLogLine(line("Alice", 1), name -> uuid, profiles, limit),
                    "A valid line must remain distinguishable from malformed input");
            require(profiles.get(uuid).isEmpty(), "Non-positive limits must retain no history");
        }
    }

    private static void malformedLinesMustNotCreateProfiles() {
        Map<UUID, List<FlagLogEntry>> profiles = new HashMap<>();
        UUID uuid = UUID.randomUUID();
        for (String line : new String[] {"", "[INFO] session started", "invalid history"}) {
            require(!ProfileManager.appendLogLine(line, name -> uuid, profiles, 2),
                    "Malformed/non-flag lines must be ignored");
        }
        require(!ProfileManager.appendLogLine(line("Unknown", 1), name -> null, profiles, 2)
                        && profiles.isEmpty(),
                "Unresolved or malformed entries must not create empty profiles");
    }

    private static void loweredLimitMustDiscardTheOldestEntries() {
        UUID uuid = UUID.randomUUID();
        Map<UUID, List<FlagLogEntry>> profiles = new HashMap<>();
        for (int count = 1; count <= 20; count++) {
            ProfileManager.appendLogLine(line("Alice", count), name -> uuid, profiles, 20);
        }
        ProfileManager.appendLogLine(line("Alice", 21), name -> uuid, profiles, 2);
        List<FlagLogEntry> history = profiles.get(uuid);
        require(history.size() == 2 && history.get(0).getFlagCount() == 20
                        && history.get(1).getFlagCount() == 21,
                "A lower configured limit must preserve the newest entries");
    }

    private static String line(String player, int flags) {
        return "[2026-10-29 18:00:00] [FLAG] PLAYER=" + player
                + " CHECK=Aim FLAGS=" + flags
                + " VALUE=1.0000 VICTIM=N/A LOCATION=world 0 64 0";
    }

    private static final class TrackingList extends ArrayList<FlagLogEntry> {
        private int peakSize;

        @Override
        public boolean add(FlagLogEntry entry) {
            boolean added = super.add(entry);
            peakSize = Math.max(peakSize, size());
            return added;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
