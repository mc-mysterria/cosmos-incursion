package net.mysterria.cosmos.domain.incursion.model.source;

/**
 * Defines the four risk tiers for incursion zones.
 * Tier controls item drop chance on death and whether sequence regression applies.
 */
public enum ZoneTier {

    /**
     * No item drops on death. No sequence regression.
     * Lowest risk, lowest reward.
     */
    GREEN,

    /**
     * ~33% of items drop on death. No sequence regression.
     */
    YELLOW,

    /**
     * All items drop on death. No sequence regression.
     */
    RED,

    /**
     * All items drop on death AND sequence regression applies.
     * Highest risk, highest reward. Replicates original zone behavior.
     */
    DEATH;

    /** Config section key for this tier, e.g. "green", "yellow". */
    public String configKey() {
        return name().toLowerCase();
    }
}
