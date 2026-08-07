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
 *
 * <p>{@code nationId}/{@code nationName} describe the Lands Nation (an alliance of towns) this
 * town belongs to, if any. HuskTowns has no nation concept, so towns backed by it always report
 * {@link #NO_NATION}. A nation's id uses the same stable-hash convention as a Lands town id.
 */
public record TownData(int id, String name, Set<UUID> memberUuids, int nationId, String nationName) {

    /** Sentinel value meaning "no owner" – same role as {@code townId == 0} in BeaconCapture. */
    public static final int NO_OWNER = 0;

    /** Sentinel value meaning "not part of any nation". */
    public static final int NO_NATION = 0;

    public boolean hasNation() {
        return nationId != NO_NATION;
    }

    /**
     * The side this town fights on for beacon-contest purposes: its Nation if it has one,
     * otherwise the town itself. Nation ids are negated so they can never collide with a
     * (always positive) town id — a townless town is simply a faction of one.
     */
    public int factionId() {
        return hasNation() ? -nationId : id;
    }
}
