package net.mysterria.cosmos.integration.map;

import net.mysterria.cosmos.domain.beacon.SpiritBeacon;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import org.bukkit.entity.Player;

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
}
