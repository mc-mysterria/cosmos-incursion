package net.mysterria.cosmos.toolkit.towns;

import java.util.Set;
import java.util.UUID;

/**
 * Plugin-agnostic representation of a player town or land.
 * Abstracts over HuskTowns {@code Town} and Lands {@code Land}.
 *
 * <p>The {@code id} field is a stable positive integer derived from the
 * underlying plugin's identifier. For HuskTowns it is {@code Town.getId()};
 * for Lands it is {@code Math.abs(land.getName().hashCode())} (never 0).
 */
public record TownData(int id, String name, Set<UUID> memberUuids) {

    /** Sentinel value meaning "no owner" – same role as {@code townId == 0} in BeaconCapture. */
    public static final int NO_OWNER = 0;
}
