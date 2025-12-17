package net.mysterria.cosmos.domain.player.source;

/**
 * Represents the tier classification of a player in the Incursion zone
 */
public enum PlayerTier {
    /**
     * High-tier players (Sequence 4-5)
     * - GLOWING effect (cannot hide)
     * - DOT damage (Spirit Weight attrition)
     * - Visible on map
     */
    SPIRIT_WEIGHT,

    /**
     * Low-tier players (Sequence 6-9 and non-beyonders)
     * - No glowing effect (stealth mechanics apply)
     * - No DOT damage
     * - Access to tactical items (future implementation)
     */
    INSIGNIFICANT
}
