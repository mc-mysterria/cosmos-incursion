package net.mysterria.cosmos.domain.incursion.service;

import net.mysterria.cosmos.domain.incursion.model.PlayerContribution;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each player's personal contribution score for the current incursion event.
 * <p>
 * Per-town scoring doesn't need a live tracker — {@link net.mysterria.cosmos.domain.beacon.model.BeaconCapture}
 * already accumulates per-town hold/contested/capture data for the lifetime of the event, and
 * {@code RewardDistributor} reads it directly at event end. This class exists only because that
 * per-town data isn't broken down by player; it feeds MVP rewards, which are personal and
 * independent of which town (if any) ends up on the podium.
 */
public class ContributionTracker {

    private final Map<UUID, Double> scores = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    /** Clears all tracked scores. Called at the start of each event. */
    public void reset() {
        scores.clear();
        names.clear();
    }

    /** Credits a player's personal contribution score by the given amount. */
    public void credit(Player player, double amount) {
        if (amount <= 0) return;
        scores.merge(player.getUniqueId(), amount, Double::sum);
        names.put(player.getUniqueId(), player.getName());
    }

    /**
     * Returns the top {@code count} players by score, descending. Ties break on player UUID so
     * an exact tie is reproducible instead of depending on map iteration order.
     */
    public List<PlayerContribution> top(int count) {
        return scores.entrySet().stream()
                .map(e -> new PlayerContribution(e.getKey(), names.getOrDefault(e.getKey(), "Unknown"), e.getValue()))
                .sorted(Comparator.comparingDouble(PlayerContribution::score).reversed()
                        .thenComparing(PlayerContribution::playerId))
                .limit(Math.max(0, count))
                .toList();
    }

}
