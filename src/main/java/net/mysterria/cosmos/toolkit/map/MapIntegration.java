package net.mysterria.cosmos.toolkit.map;

import net.mysterria.cosmos.domain.beacon.model.SpiritBeacon;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Common interface for map integrations (BlueMap, squaremap, etc.).
 * All marker operations are no-ops when the backing map plugin is unavailable.
 */
public interface MapIntegration {

    /** Initialize the integration after the map plugin has loaded. */
    void initialize();

    /** Returns true if the backing map plugin is available and initialized. */
    boolean isAvailable();

    // --- Zone markers ---

    void createZoneMarker(IncursionZone zone);

    void removeZoneMarker(IncursionZone zone);

    void removeAllZoneMarkers();

    // --- Beacon markers ---

    void createBeaconMarker(SpiritBeacon beacon, double captureRadius);

    void removeBeaconMarker(SpiritBeacon beacon);

    void removeBeaconMarker(String beaconId);

    void removeAllBeaconMarkers();

    // --- Player markers ---

    void markCorruptedMonster(Player player);

    void removeCorruptedMonsterMarker(Player player);

    void removeCorruptedMonsterMarker(UUID playerId);

    // --- Permanent zone markers ---

    void createPermanentZoneMarker(PermanentZone zone);

    void removePermanentZoneMarker(UUID zoneId);

    void removeAllPermanentZoneMarkers();

    void syncPermanentZonePoIs(PermanentZone zone, List<PointOfInterest> pois);

    void syncPermanentZoneExtractionPoints(PermanentZone zone, List<ExtractionPoint> eps);
}
