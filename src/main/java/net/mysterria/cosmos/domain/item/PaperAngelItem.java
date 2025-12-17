package net.mysterria.cosmos.domain.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Utility class for creating and identifying Paper Angel items
 * Paper Angels protect players from sequence regression on death
 */
public class PaperAngelItem {

    private static final String ITEM_ID = "paper_angel";

    /**
     * Create a Paper Angel item
     */
    public static ItemStack create(NamespacedKey key, int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        ItemMeta meta = item.getItemMeta();

        // Set display name
        meta.displayName(Component.text("Paper Angel")
                .color(NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));

        // Set lore
        meta.lore(List.of(
                Component.text("A mystical guardian angel").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("made of paper and hope.").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Right-click to activate protection.").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Protects from sequence regression").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("on your next death in an").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.text("incursion zone.").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
        ));

        // Mark as Paper Angel using PDC
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, ITEM_ID);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Check if an item is a Paper Angel
     */
    public static boolean isPaperAngel(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(key, PersistentDataType.STRING) &&
               ITEM_ID.equals(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

}
