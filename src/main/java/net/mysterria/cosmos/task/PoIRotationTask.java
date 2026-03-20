package net.mysterria.cosmos.task;

import net.mysterria.cosmos.domain.permanent.PermanentZone;
import net.mysterria.cosmos.domain.permanent.PermanentZoneManager;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Runs every 20 ticks (1 second). Checks each permanent zone's PoIs and extraction
 * points, replacing expired ones with fresh ones.
 */
public class PoIRotationTask extends BukkitRunnable {

    private final PermanentZoneManager permanentZoneManager;

    public PoIRotationTask(PermanentZoneManager permanentZoneManager) {
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        for (PermanentZone zone : permanentZoneManager.getAllZones()) {
            if (!zone.isActive()) continue;
            permanentZoneManager.rotatePoIs(zone);
            permanentZoneManager.rotateExtractionPoints(zone);
        }
    }
}
