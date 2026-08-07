package net.mysterria.cosmos.domain.incursion.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.incursion.model.EventResult;
import net.mysterria.cosmos.domain.incursion.model.TownScore;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
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

    private final List<EventResult> history = new CopyOnWriteArrayList<>();
    private volatile int holderTownId = 0;
    private volatile String holderTownName = null;
    private volatile int holderStreak = 0;

    // MVP acting-effort rewards that couldn't be granted because the player was offline at
    // distribution time, queued to be paid via the normal online grant path on next join
    // (PlayerJoinListener) rather than through COI's cruder, unscaled offline API. Keyed by
    // UUID string rather than UUID directly since Gson doesn't natively support non-primitive
    // map keys.
    private final Map<UUID, Double> pendingMvpEffort = new ConcurrentHashMap<>();

    public EventHistoryStore(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.historyFile = new File(plugin.getDataFolder(), "event_history.json");
    }

    private record PersistedState(List<EventResult> history, int holderTownId, String holderTownName,
                                  int holderStreak, Map<String, Double> pendingMvpEffort) {}

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
                if (state.pendingMvpEffort() != null) {
                    state.pendingMvpEffort().forEach((uuidString, effort) ->
                            pendingMvpEffort.put(UUID.fromString(uuidString), effort));
                }
                plugin.log("Loaded " + history.size() + " event history entries" +
                        (holderTownId != 0 ? " (current holder: " + holderTownName + ", streak " + holderStreak + ")" : "") +
                        (pendingMvpEffort.isEmpty() ? "" : ", " + pendingMvpEffort.size() + " pending offline MVP reward(s)"));
            }
        } catch (IOException e) {
            plugin.log("Error loading event history: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(historyFile)) {
            Map<String, Double> pendingMvpEffortByString = new LinkedHashMap<>();
            pendingMvpEffort.forEach((uuid, effort) -> pendingMvpEffortByString.put(uuid.toString(), effort));

            PersistedState state = new PersistedState(new ArrayList<>(history), holderTownId, holderTownName,
                    holderStreak, pendingMvpEffortByString);
            gson.toJson(state, writer);
        } catch (IOException e) {
            plugin.log("Error saving event history: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Queues an MVP reward for a player who was offline at distribution time. */
    public void queuePendingMvpEffort(UUID playerId, double effort) {
        pendingMvpEffort.merge(playerId, effort, Double::sum);
        save();
    }

    /** Removes and returns any pending MVP effort for a player (0 if none), for granting on join. */
    public double drainPendingMvpEffort(UUID playerId) {
        Double effort = pendingMvpEffort.remove(playerId);
        if (effort != null) {
            save();
        }
        return effort != null ? effort : 0.0;
    }

    /** Appends a result, evicting the oldest entry once the cap is exceeded, then saves. */
    public void recordResult(EventResult result) {
        history.add(result);
        while (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        save();
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
