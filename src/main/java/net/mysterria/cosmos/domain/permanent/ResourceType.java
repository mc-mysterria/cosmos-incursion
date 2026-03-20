package net.mysterria.cosmos.domain.permanent;

import org.bukkit.Material;

public enum ResourceType {
    GOLD(Material.GOLD_INGOT),
    SILVER(Material.IRON_INGOT),
    GEMS(Material.EMERALD);

    private final Material defaultMaterial;

    ResourceType(Material defaultMaterial) {
        this.defaultMaterial = defaultMaterial;
    }

    public Material getDefaultMaterial() {
        return defaultMaterial;
    }

    public String configKey() {
        return name().toLowerCase();
    }
}
