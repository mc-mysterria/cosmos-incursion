package net.mysterria.cosmos.domain.incursion.model;

import java.util.List;
import java.util.UUID;

/**
 * A persisted record of one incursion event's outcome — the plugin's first durable history of
 * who actually won, used by {@code /cosmos leaderboard} and Discord result announcements.
 */
public record EventResult(long timestampMillis, List<TownScore> standings, UUID mvpId, String mvpName) {
}
