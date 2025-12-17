package net.mysterria.cosmos.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.item.PaperAngelItem;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Handles Paper Angel item usage
 * When right-clicked, consumes the item and grants protection from sequence regression
 */
public class PaperAngelListener implements Listener {

    private final CosmosIncursion plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey protectionKey;

    public PaperAngelListener(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.itemKey = plugin.getKey("paper_angel_item");
        this.protectionKey = plugin.getKey("paper_angel");
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle right-click actions
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Check if it's a Paper Angel
        if (!PaperAngelItem.isPaperAngel(item, itemKey)) {
            return;
        }

        // Check if player already has protection
        if (player.getPersistentDataContainer().has(protectionKey, PersistentDataType.BOOLEAN)) {
            player.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                    .append(Component.text("You already have Paper Angel protection!").color(NamedTextColor.YELLOW)));
            event.setCancelled(true);
            return;
        }

        // Activate protection
        player.getPersistentDataContainer().set(protectionKey, PersistentDataType.BOOLEAN, true);

        // Consume one Paper Angel
        item.setAmount(item.getAmount() - 1);

        // Visual and audio feedback
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        player.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("Paper Angel activated! ").color(NamedTextColor.GREEN))
                .append(Component.text("You are now protected from sequence regression on your next death in an incursion zone.").color(NamedTextColor.GRAY)));

        // Cancel the event to prevent placing blocks or other interactions
        event.setCancelled(true);

        plugin.log("Player " + player.getName() + " activated Paper Angel protection");
    }

}
