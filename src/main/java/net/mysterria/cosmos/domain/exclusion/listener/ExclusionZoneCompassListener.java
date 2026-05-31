package net.mysterria.cosmos.domain.exclusion.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Prevents the zone compass from being dropped or placed into containers.
 * The compass is given to players on exclusion zone entry and must stay
 * in their personal inventory at all times.
 */
public class ExclusionZoneCompassListener implements Listener {

    private final CosmosIncursion plugin;

    public ExclusionZoneCompassListener(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /** PDC byte tag on the compass: 0 = PoI mode (default), 1 = extraction mode. */
    public static final String COMPASS_MODE_KEY = "cosmos_compass_mode";

    public static boolean isExtractionMode(CosmosIncursion plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return isExtractionMode(plugin, item.getItemMeta());
    }

    public static boolean isExtractionMode(CosmosIncursion plugin, ItemMeta meta) {
        if (meta == null) return false;
        Byte mode = meta.getPersistentDataContainer()
                .get(plugin.getKey(COMPASS_MODE_KEY), PersistentDataType.BYTE);
        return mode != null && mode == 1;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        if (!isZoneCompass(item)) return;

        event.setCancelled(true);

        // Toggle mode in PDC
        ItemMeta meta = item.getItemMeta();
        Byte current = meta.getPersistentDataContainer()
                .get(plugin.getKey(COMPASS_MODE_KEY), PersistentDataType.BYTE);
        boolean nowExtraction = (current == null || current == 0);
        meta.getPersistentDataContainer()
                .set(plugin.getKey(COMPASS_MODE_KEY), PersistentDataType.BYTE, (byte) (nowExtraction ? 1 : 0));
        item.setItemMeta(meta);

        event.getPlayer().sendActionBar(nowExtraction
            ? Component.text("Compass: ", NamedTextColor.GRAY)
                .append(Component.text("Extraction Points", NamedTextColor.GREEN))
                .decoration(TextDecoration.ITALIC, false)
            : Component.text("Compass: ", NamedTextColor.GRAY)
                .append(Component.text("Points of Interest", NamedTextColor.AQUA))
                .decoration(TextDecoration.ITALIC, false));
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getPermanentZoneManager().isInsideAnyZone(player.getLocation())) {
            removeCompass(player);
        }
    }

    private void removeCompass(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isZoneCompass(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (isZoneCompass(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        // Block moving the compass out of the player's own inventory
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        if (isZoneCompass(cursor) || isZoneCompass(current)) {
            // Allow movement only within the player's own inventory
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().getType() != InventoryType.PLAYER) {
                event.setCancelled(true);
                return;
            }
            // Block shift-click into any other inventory (e.g. chest)
            if (event.isShiftClick()
                    && event.getView().getTopInventory().getType() != InventoryType.CRAFTING
                    && event.getView().getTopInventory().getType() != InventoryType.PLAYER) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isZoneCompass(ItemStack item) {
        if (item == null || item.getType() != org.bukkit.Material.COMPASS) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(plugin.getKey("cosmos_zone_compass"), PersistentDataType.BOOLEAN);
    }
}
