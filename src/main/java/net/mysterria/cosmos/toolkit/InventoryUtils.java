package net.mysterria.cosmos.toolkit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Shared player-inventory helpers used by combat-log / hollow-body flows.
 */
public final class InventoryUtils {

    private InventoryUtils() {
    }

    public static void clearPlayerInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
    }
}
