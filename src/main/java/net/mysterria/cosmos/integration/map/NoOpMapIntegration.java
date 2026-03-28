package net.mysterria.cosmos.integration.map;

import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.domain.permanent.ExtractionPoint;
import net.mysterria.cosmos.domain.permanent.PermanentZone;
import net.mysterria.cosmos.domain.permanent.PointOfInterest;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * No-op map integration used when neither BlueMap nor squaremap is present.
 * All methods silently do nothing.
 */
public class NoOpMapIntegration implements MapIntegration {

    @Override public void initialize() {}
    @Override public boolean isAvailable() { return false; }

    @Override public void createZoneMarker(IncursionZone zone) {}
    @Override public void removeZoneMarker(IncursionZone zone) {}
    @Override public void removeAllZoneMarkers() {}

    @Override public void createBeaconMarker(SpiritBeacon beacon, double captureRadius) {}
    @Override public void removeBeaconMarker(SpiritBeacon beacon) {}
    @Override public void removeBeaconMarker(String beaconId) {}
    @Override public void removeAllBeaconMarkers() {}

    @Override public void markCorruptedMonster(Player player) {}
    @Override public void removeCorruptedMonsterMarker(Player player) {}
    @Override public void removeCorruptedMonsterMarker(UUID playerId) {}

    @Override public void createPermanentZoneMarker(PermanentZone zone) {}
    @Override public void removePermanentZoneMarker(UUID zoneId) {}
    @Override public void removeAllPermanentZoneMarkers() {}
    @Override public void syncPermanentZonePoIs(PermanentZone zone, List<PointOfInterest> pois) {}
    @Override public void syncPermanentZoneExtractionPoints(PermanentZone zone, List<ExtractionPoint> eps) {}
}
