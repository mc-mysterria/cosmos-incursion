package net.mysterria.cosmos.domain.exclusion.model.source;

import lombok.Getter;
import org.bukkit.Material;

@Getter
public enum ResourceType {
    GOLD(Material.GOLD_INGOT),
    SILVER(Material.IRON_INGOT),
    GEMS(Material.EMERALD);

    private final Material defaultMaterial;

    ResourceType(Material defaultMaterial) {
        this.defaultMaterial = defaultMaterial;
    }

    public String configKey() {
        return name().toLowerCase();
    }

    public String displayName() {
        return switch (this) {
            case GOLD -> "Gold";
            case SILVER -> "Iron";
            case GEMS -> "Gems";
        };
    }
}
