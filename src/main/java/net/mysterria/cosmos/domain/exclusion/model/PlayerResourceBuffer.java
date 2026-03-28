package net.mysterria.cosmos.domain.exclusion.model;

import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class PlayerResourceBuffer {

    private final UUID playerId;
    private final Map<ResourceType, Double> carried = new EnumMap<>(ResourceType.class);

    public PlayerResourceBuffer(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void add(ResourceType type, double amount) {
        carried.merge(type, amount, Double::sum);
    }

    public double get(ResourceType type) {
        return carried.getOrDefault(type, 0.0);
    }

    public boolean isEmpty() {
        return carried.values().stream().allMatch(v -> v <= 0.0);
    }

    public void clear() {
        carried.clear();
    }

    /** Returns a defensive copy of all carried resources. */
    public Map<ResourceType, Double> snapshot() {
        return new EnumMap<>(carried);
    }

    /**
     * Drains up to {@code maxPerType} units of each resource type, removes them from the buffer,
     * and returns the drained amounts.
     */
    public Map<ResourceType, Double> drain(double maxPerType) {
        Map<ResourceType, Double> drained = new EnumMap<>(ResourceType.class);
        for (ResourceType type : ResourceType.values()) {
            double current = carried.getOrDefault(type, 0.0);
            if (current <= 0.0) continue;
            double take = Math.min(current, maxPerType);
            carried.put(type, current - take);
            drained.put(type, take);
        }
        carried.values().removeIf(v -> v <= 0.0);
        return drained;
    }
}
