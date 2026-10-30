package com.antielytratarget.managers;

import com.antielytratarget.AntiElytraTargetPlugin;
import com.antielytratarget.models.FlagLogEntry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class ProfileManager {

    private final AntiElytraTargetPlugin              plugin;
    private final ConfigManager                       config;

private final Map<UUID, List<FlagLogEntry>> profiles = new ConcurrentHashMap<>();

    public ProfileManager(AntiElytraTargetPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        loadAllProfiles();
    }

public void addEntry(UUID uuid, FlagLogEntry entry) {
        List<FlagLogEntry> profile = profiles.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
        appendEntry(profile, entry, config.getMaxProfileEntries());
    }

    public List<FlagLogEntry> getProfile(UUID uuid) {
        List<FlagLogEntry> profile = profiles.get(uuid);
        if (profile == null) return Collections.emptyList();
        synchronized (profile) {
            return Collections.unmodifiableList(new ArrayList<>(profile));
        }
    }

public Map.Entry<UUID, List<FlagLogEntry>> findByName(String name) {
        for (var player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return Map.entry(player.getUniqueId(), getProfile(player.getUniqueId()));
            }
        }
        for (var entry : profiles.entrySet()) {
            List<FlagLogEntry> list = entry.getValue();
            synchronized (list) {
                if (!list.isEmpty() && list.get(0).getPlayerName().equalsIgnoreCase(name)) {
                    return Map.entry(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(list)));
                }
            }
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.getUniqueId() != null) {
            List<FlagLogEntry> profile = profiles.get(offline.getUniqueId());
            if (profile != null && !profile.isEmpty()) {
                synchronized (profile) {
                    return Map.entry(offline.getUniqueId(), Collections.unmodifiableList(new ArrayList<>(profile)));
                }
            }
        }
        return null;
    }

    public int getTotalFlags(UUID uuid) {
        List<FlagLogEntry> profile = profiles.get(uuid);
        return profile != null ? profile.size() : 0;
    }
    public void    clearProfile(UUID uuid)    { profiles.remove(uuid); }
    public Set<UUID> getAllTrackedUUIDs()      { return Collections.unmodifiableSet(profiles.keySet()); }
    public int     getTrackedPlayerCount()    { return profiles.size(); }

public List<String> getAllTrackedNames() {
        List<String> names = new ArrayList<>();
        for (List<FlagLogEntry> list : profiles.values()) {
            synchronized (list) {
                if (!list.isEmpty()) names.add(list.get(0).getPlayerName());
            }
        }
        return names;
    }

public void loadAllProfiles() {
        Path logPath = plugin.getLogManager().getLogPath();
        if (!Files.exists(logPath)) {
            plugin.getLogger().info("[AET] No previous flag history found (first run or file missing).");
            return;
        }
        Map<String, UUID> knownPlayers = buildPlayerUuidIndex();
        Function<String, UUID> uuidResolver = name -> resolvePlayerUuid(name, knownPlayers);
        int max = Math.max(0, config.getMaxProfileEntries());
        int loaded = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(logPath), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (appendLogLine(line, uuidResolver, profiles, max)) loaded++;
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[AET] Failed to load flag history: " + e.getMessage());
        }

        for (List<FlagLogEntry> profile : profiles.values()) {
            synchronized (profile) {
                int excess = profile.size() - max;
                if (excess > 0) profile.subList(0, excess).clear();
            }
        }
        plugin.getLogger().info("[AET] Loaded " + loaded + " historical flag entries for "
                + profiles.size() + " players.");
    }

    static boolean appendLogLine(String line, Function<String, UUID> uuidResolver,
                                 Map<UUID, List<FlagLogEntry>> profiles, int max) {
        FlagLogEntry entry = FlagLogEntry.fromLogLine(line, uuidResolver);
        if (entry == null) return false;
        List<FlagLogEntry> profile = profiles.computeIfAbsent(entry.getPlayerUUID(),
                key -> Collections.synchronizedList(new ArrayList<>()));
        appendEntry(profile, entry, max);
        return true;
    }

    private static void appendEntry(List<FlagLogEntry> profile, FlagLogEntry entry, int max) {
        int limit = Math.max(0, max);
        synchronized (profile) {
            // Keep history bounded while reading the file, not only after the entire log is loaded.
            int excess = profile.size() - limit + 1;
            if (excess > 0 && !profile.isEmpty()) {
                profile.subList(0, Math.min(excess, profile.size())).clear();
            }
            if (limit > 0) profile.add(entry);
        }
    }

    private Map<String, UUID> buildPlayerUuidIndex() {
        Map<String, UUID> players = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.put(player.getName().toLowerCase(Locale.ROOT), player.getUniqueId());
        }
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName();
            if (name != null) {
                players.putIfAbsent(name.toLowerCase(Locale.ROOT), player.getUniqueId());
            }
        }
        return players;
    }

    @SuppressWarnings("deprecation")
    private UUID resolvePlayerUuid(String playerName, Map<String, UUID> knownPlayers) {
        String key = playerName.toLowerCase(Locale.ROOT);
        UUID known = knownPlayers.get(key);
        if (known != null) return known;

        UUID resolved = Bukkit.getOfflinePlayer(playerName).getUniqueId();
        knownPlayers.put(key, resolved);
        return resolved;
    }

public void saveAllProfiles() {
        plugin.getLogger().info("[AET] Session ended. Total tracked players: " + profiles.size()
                + " | Total flags this session: " + profiles.values().stream()
                .mapToInt(List::size).sum());
    }
}
