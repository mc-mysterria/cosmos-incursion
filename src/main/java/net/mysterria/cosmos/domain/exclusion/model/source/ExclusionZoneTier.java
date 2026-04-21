package net.mysterria.cosmos.domain.exclusion.model.source;

/**
 * Risk tiers for permanent extraction zones.
 * Controls inventory item drop chance on death inside the zone.
 * Resource accumulation rates are also tier-scaled (configured per-tier in config.yml).
 */
public enum ExclusionZoneTier {

    /** No inventory item drops on death. Lowest risk, lowest resource rate. */
    SAFE,

    /** ~33% of items drop on death. Moderate risk and reward. */
    MEDIUM,

    /** All items drop on death. Highest risk, highest resource rate. */
    HARD;

    public String configKey() {
        return name().toLowerCase();
    }
}
