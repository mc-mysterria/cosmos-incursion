package net.mysterria.cosmos.toolkit.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Utility for materializing carried permanent-zone resources as physical ground items.
 * <p>
 * Used when a resource-carrying player is forced into spectator mode (e.g. by a
 * movement-canceling spell). Rather than silently voiding the buffer, the resources are
 * dropped on the ground as identifiable items so they remain contestable loot — anyone
 * who picks one up has its exact amount credited straight back to their buffer.
 */
public class ResourceItemToolkit {

    private static final String ITEM_ID = "resource_drop";

    public static ItemStack create(NamespacedKey idKey, NamespacedKey typeKey, NamespacedKey amountKey, ResourceType type, double amount) {
        ItemStack item = new ItemStack(type.getDefaultMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("Spilled " + type.displayName(), color(type))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text(String.format("%.1f", amount) + " units", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Pick up to reclaim.", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
        ));

        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, ITEM_ID);
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
        meta.getPersistentDataContainer().set(amountKey, PersistentDataType.DOUBLE, amount);

        item.setItemMeta(meta);
        return item;
    }

    public static boolean isResourceDrop(ItemStack item, NamespacedKey idKey) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return ITEM_ID.equals(meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    public static ResourceType getType(ItemStack item, NamespacedKey typeKey) {
        if (item == null || !item.hasItemMeta()) return null;
        String name = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (name == null) return null;
        try {
            return ResourceType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static double getAmount(ItemStack item, NamespacedKey amountKey) {
        if (item == null || !item.hasItemMeta()) return 0.0;
        Double amount = item.getItemMeta().getPersistentDataContainer().get(amountKey, PersistentDataType.DOUBLE);
        return amount != null ? amount : 0.0;
    }

    private static NamedTextColor color(ResourceType type) {
        return switch (type) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.WHITE;
            case GEMS -> NamedTextColor.GREEN;
        };
    }
}
