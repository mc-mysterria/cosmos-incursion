package net.mysterria.cosmos.domain.incursion.model;

import java.util.UUID;

/**
 * A player's accumulated personal contribution score for the current incursion event,
 * used to determine MVP rewards independently of which town (if any) placed on the podium.
 */
public record PlayerContribution(UUID playerId, String playerName, double score) {
}
