package net.mysterria.cosmos.domain.incursion.task;

import net.mysterria.cosmos.domain.incursion.service.EventManager;
import org.bukkit.scheduler.BukkitRunnable;

public class EventCheckTask extends BukkitRunnable {

    private final EventManager eventManager;

    public EventCheckTask(EventManager eventManager) {
        this.eventManager = eventManager;
    }

    @Override
    public void run() {
        try {
            eventManager.tick();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
