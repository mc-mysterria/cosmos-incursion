package net.mysterria.cosmos.domain.incursion.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.incursion.model.EventResult;
import net.mysterria.cosmos.domain.incursion.model.TownScore;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Persists a capped history of incursion event results plus the current title "Holder" and their
 * win streak, following the same Gson load/save pattern as {@link net.mysterria.cosmos.toolkit.BuffToolkit}.
 * <p>
 * This is the plugin's first durable record of who won an event — previously the outcome was
 * logged to console and discarded the moment the next event started.
 */
public class EventHistoryStore {

    private static final int MAX_HISTORY = 50;

    private final CosmosIncursion plugin;
    private final Gson gson;
    private final File historyFile;
    private final Object persistenceLock = new Object();

    private final List<EventResult> history = new CopyOnWriteArrayList<>();
    private volatile int holderTownId = 0;
    private volatile String holderTownName = null;
    private volatile int holderStreak = 0;

    // MVP acting-effort rewards that couldn't be granted because the player was offline at
    // distribution time, queued with their source event so the eventual grant keeps the same
    // audit correlation. Persistence converts player UUID keys to strings for Gson.
    private final Map<UUID, List<PendingMvpReward>> pendingMvpRewards = new ConcurrentHashMap<>();

    public record PendingMvpReward(UUID eventId, double effort) {}

    public EventHistoryStore(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.historyFile = new File(plugin.getDataFolder(), "event_history.json");
    }

    private long cooldownEndTime;

    private record PersistedState(List<EventResult> history, int holderTownId, String holderTownName,
                                  int holderStreak, Map<String, Double> pendingMvpEffort,
                                  Map<String, List<PendingMvpReward>> pendingMvpRewards,
                                  long cooldownEndTime) {}

    public void load() {
        if (!historyFile.exists()) {
            plugin.log("No event history file found, starting fresh");
            return;
        }

        try (FileReader reader = new FileReader(historyFile)) {
            PersistedState state = gson.fromJson(reader, PersistedState.class);
            if (state != null) {
                if (state.history() != null) {
                    history.addAll(state.history());
                }
                holderTownId = state.holderTownId();
                holderTownName = state.holderTownName();
                holderStreak = state.holderStreak();
                cooldownEndTime = state.cooldownEndTime();
                if (state.pendingMvpRewards() != null) {
                    state.pendingMvpRewards().forEach((uuidString, rewards) ->
                            pendingMvpRewards.put(UUID.fromString(uuidString), new ArrayList<>(rewards)));
                } else if (state.pendingMvpEffort() != null) {
                    state.pendingMvpEffort().forEach((uuidString, effort) ->
                            pendingMvpRewards.put(UUID.fromString(uuidString),
                                    new ArrayList<>(List.of(new PendingMvpReward(null, effort)))));
                }
                plugin.log("Loaded " + history.size() + " event history entries" +
                        (holderTownId != 0 ? " (current holder: " + holderTownName + ", streak " + holderStreak + ")" : "") +
                        (pendingMvpRewards.isEmpty() ? "" : ", " + pendingMvpRewards.size() + " pending offline MVP reward(s)"));
            }
        } catch (IOException | JsonParseException e) {
            plugin.log("Error loading event history: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean save() {
        synchronized (persistenceLock) {
            return saveLocked();
        }
    }

    private boolean saveLocked() {
        Path temporary = null;
        try {
            Map<String, List<PendingMvpReward>> pendingByPlayer = new LinkedHashMap<>();
            pendingMvpRewards.forEach((uuid, rewards) ->
                    pendingByPlayer.put(uuid.toString(), new ArrayList<>(rewards)));
            PersistedState state = new PersistedState(new ArrayList<>(history), holderTownId, holderTownName,
                    holderStreak, null, pendingByPlayer, cooldownEndTime);
            String json = gson.toJson(state);
            Path target = historyFile.toPath().toAbsolutePath();
            temporary = Files.createTempFile(target.getParent(), historyFile.getName(), ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | JsonParseException e) {
            plugin.log("Error saving event history: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Queues an MVP reward for a player who was offline at distribution time. */
    public boolean queuePendingMvpReward(UUID playerId, UUID eventId, double effort) {
        synchronized (persistenceLock) {
            List<PendingMvpReward> previous = pendingMvpRewards.get(playerId);
            List<PendingMvpReward> updated = previous == null ? new ArrayList<>() : new ArrayList<>(previous);
            updated.add(new PendingMvpReward(eventId, effort));
            pendingMvpRewards.put(playerId, updated);
            if (saveLocked()) return true;
            if (previous == null) pendingMvpRewards.remove(playerId);
            else pendingMvpRewards.put(playerId, previous);
            return false;
        }
    }

    /** Returns pending rewards without removing them. */
    public List<PendingMvpReward> getPendingMvpRewards(UUID playerId) {
        synchronized (persistenceLock) {
            List<PendingMvpReward> rewards = pendingMvpRewards.get(playerId);
            return rewards == null ? List.of() : List.copyOf(rewards);
        }
    }

    /** Removes one applied reward only after the removal is durably persisted. */
    public boolean acknowledgePendingMvpReward(UUID playerId, PendingMvpReward reward) {
        synchronized (persistenceLock) {
            List<PendingMvpReward> current = pendingMvpRewards.get(playerId);
            if (current == null) return true;
            List<PendingMvpReward> updated = new ArrayList<>(current);
            if (!updated.remove(reward)) return true;
            if (updated.isEmpty()) pendingMvpRewards.remove(playerId);
            else pendingMvpRewards.put(playerId, updated);
            if (saveLocked()) return true;
            pendingMvpRewards.put(playerId, current);
            return false;
        }
    }

    /** Appends a result, evicting the oldest entry once the cap is exceeded, then saves. */
    public boolean recordResult(EventResult result) {
        synchronized (persistenceLock) {
            history.add(result);
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }
            return saveLocked();
        }
    }

    public int getHolderTownId() {
        return holderTownId;
    }

    public String getHolderTownName() {
        return holderTownName;
    }

    public int getHolderStreak() {
        return holderStreak;
    }

    public void setHolder(int townId, String townName, int streak) {
        this.holderTownId = townId;
        this.holderTownName = townName;
        this.holderStreak = streak;
    }

    public long getCooldownEndTime() {
        return cooldownEndTime;
    }

    public void setCooldownEndTime(long cooldownEndTime) {
        this.cooldownEndTime = cooldownEndTime;
    }

    /** Most recent results, newest first, capped to {@code count}. */
    public List<EventResult> getRecent(int count) {
        List<EventResult> copy = new ArrayList<>(history);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(Math.max(0, count), copy.size()));
    }

    /** All-time win counts (rank 1, qualified) per town name, descending. */
    public Map<String, Long> getWinCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        history.stream()
                .flatMap(r -> r.standings().stream())
                .filter(t -> t.rank() == 1 && t.qualified())
                .collect(Collectors.groupingBy(TownScore::townName, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

}
