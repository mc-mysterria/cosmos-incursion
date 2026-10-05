package net.mysterria.cosmos.domain.combat.listener;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.service.DeathAudit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import net.mysterria.cosmos.toolkit.item.PaperAngelToolkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Paper Angel item usage
 * When right-clicked, consumes the item and grants protection from sequence regression
 */
public class PaperAngelListener implements Listener {

    /** Minimum time between two refusal rows for the same player and reason. */
    private static final long REFUSAL_WINDOW_MILLIS = 60_000L;

    private final CosmosIncursion plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey protectionKey;
    private final Map<UUID, Map<String, RefusalWindow>> refusals = new ConcurrentHashMap<>();

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
        if (!PaperAngelToolkit.isPaperAngel(item, itemKey)) {
            return;
        }

        // Check if player already has protection
        if (player.getPersistentDataContainer().has(protectionKey, PersistentDataType.BOOLEAN)) {
            player.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                    .append(Component.text("You already have Paper Angel protection!").color(NamedTextColor.YELLOW)));
            event.setCancelled(true);
            emitRefused(player, "already_protected", item.getAmount());
            return;
        }

        // Activate protection
        player.getPersistentDataContainer().set(protectionKey, PersistentDataType.BOOLEAN, true);

        // Consume one Paper Angel
        item.setAmount(item.getAmount() - 1);
        emitActivated(player, item.getAmount());

        // Visual and audio feedback
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.2f);
        player.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("Paper Angel activated! ").color(NamedTextColor.GREEN))
                .append(Component.text("You are now protected from sequence regression on your next death in an incursion zone.").color(NamedTextColor.GRAY)));

        // Cancel the event to prevent placing blocks or other interactions
        event.setCancelled(true);

        plugin.log("Player " + player.getName() + " activated Paper Angel protection");
    }

    /** Player quit: forget its refusal windows so the map only holds online players. */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        refusals.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Records a Paper Angel click that was refused ({@code already_protected}). The click can be
     * repeated every tick, so at most one row is written per player and reason in
     * {@link #REFUSAL_WINDOW_MILLIS}; the next row reports how many refusals the window swallowed
     * in {@code suppressed_since_last_row}. Built from the player and the stack the handler already
     * holds, with one map access and a clock read.
     */
    private void emitRefused(Player player, String reason, int stackSize) {
        try {
            long now = System.currentTimeMillis();
            RefusalWindow window = refusals
                    .computeIfAbsent(player.getUniqueId(), id -> new ConcurrentHashMap<>())
                    .computeIfAbsent(reason, key -> new RefusalWindow());
            int suppressed;
            synchronized (window) {
                if (window.lastRowAt != 0 && now - window.lastRowAt < REFUSAL_WINDOW_MILLIS) {
                    window.suppressed++;
                    return;
                }
                suppressed = window.suppressed;
                window.suppressed = 0;
                window.lastRowAt = now;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", player.getName());
            metadata.put("stack_size", stackSize);
            metadata.put("suppressed_since_last_row", suppressed);
            metadata.put("rate_limit_seconds", REFUSAL_WINDOW_MILLIS / 1000L);
            MysterriaAuditEmitter.putPlayerLocation(metadata, player);

            DeathAudit.emit(plugin, "incursion.paper_angel_refused", AuditOutcome.DENIED, AuditRisk.LOW,
                    player.getUniqueId(), player.getUniqueId(), null, reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /** Last row time and refusals swallowed since, for one player and reason. */
    private static final class RefusalWindow {
        private long lastRowAt;
        private int suppressed;
    }

    /**
     * Records one Paper Angel used: the protection flag set and one item consumed. Built from the
     * player and the stack the handler already holds; {@code remaining} is the stack size after use.
     */
    private void emitActivated(Player player, int remaining) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", player.getName());
            metadata.put("remaining_in_stack", remaining);
            MysterriaAuditEmitter.putPlayerLocation(metadata, player);

            DeathAudit.emit(plugin, "incursion.paper_angel_activated", AuditOutcome.COMMITTED, AuditRisk.LOW,
                    player.getUniqueId(), player.getUniqueId(), null, null, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

}
