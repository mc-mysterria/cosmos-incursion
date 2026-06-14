package net.mysterria.cosmos.domain.incursion.listener;

import io.papermc.paper.event.entity.EntityLungeEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the incursion-zone saddle that lets players summon a fast horse.
 * The horse is only available when neither the player nor any online town
 * member has gathered exclusion-zone resources.
 */
public class IncursionZoneHorseListener implements Listener {

    private static final double HORSE_SPEED = 0.6; // ~3× normal horse speed

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;

    /**
     * Player UUID → horse entity UUID (only set while actively riding).
     */
    private final ConcurrentHashMap<UUID, UUID> playerHorses = new ConcurrentHashMap<>();
    private final Set<UUID> saddleHolders = new HashSet<>();

    public IncursionZoneHorseListener(CosmosIncursion plugin,
                                      PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    // ── Zone entry / exit helpers (called from ZoneCheckTask) ───────────────────

    /**
     * Give the cosmos saddle to a player on incursion zone entry.
     */
    public void giveSaddle(Player player) {
        UUID uuid = player.getUniqueId();
        if (saddleHolders.contains(uuid)) return; // fast path
        // Slow path: re-sync set after server restart
        if (hasCosmosSaddle(player)) {
            saddleHolders.add(uuid);
            return;
        }
        ItemStack saddle = buildSaddle();
        player.getInventory().addItem(saddle);
        saddleHolders.add(uuid);
        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                .append(Component.text("You received a ", NamedTextColor.GRAY))
                .append(Component.text("Zone Saddle", NamedTextColor.GOLD))
                .append(Component.text(". Right-click to summon a mount (requires no carried resources).", NamedTextColor.GRAY)));
    }

    /**
     * Remove saddle + active horse when the player leaves an incursion zone or the
     * event ends. Does NOT return the saddle (zone-exit cleanup).
     */
    public void cleanupPlayer(Player player) {
        // Remove from map FIRST so the EntityDismountEvent handler skips saddle return
        UUID horseId = playerHorses.remove(player.getUniqueId());
        if (horseId != null) {
            Entity horse = Bukkit.getEntity(horseId);
            if (horse != null && !horse.isDead()) {
                horse.eject();
                horse.remove();
            }
        }
        forceRemoveSaddle(player);
    }

    // ── Event handlers ────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || !isCosmosSaddle(item)) return;

        // Must be right-click (any surface or air)
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        event.setCancelled(true);

        if (permanentZoneManager.getPlayerZone(player.getUniqueId()) == null) {
            player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text("This saddle only works inside extraction zones.", NamedTextColor.RED)));
            return;
        }

        if (playerHorses.containsKey(player.getUniqueId())) {
            player.sendActionBar(Component.text("You already have an active mount!", NamedTextColor.RED));
            return;
        }

        if (TownsToolkit.isPlayerInCombat(player)) {
            player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text("You cannot summon a mount while in combat.", NamedTextColor.RED)));
            return;
        }

        if (hasAnyTownResources(player)) {
            player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                    .append(Component.text("You cannot summon a mount while your town has gathered resources from a Point of Interest.", NamedTextColor.RED)));
            return;
        }

        summonHorse(player, item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID horseId = playerHorses.get(player.getUniqueId());
        if (horseId == null) return; // Not one of our horses (or cleanup already ran)

        Entity dismounted = event.getDismounted();
        if (!dismounted.getUniqueId().equals(horseId)) return;

        // Legitimate player dismount — remove from tracking, delete horse, return saddle
        playerHorses.remove(player.getUniqueId());

        // Delay removal by 1 tick so the dismount completes cleanly
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!dismounted.isDead()) dismounted.remove();
        }, 1L);

        // Return saddle only if still inside an extraction zone
        if (permanentZoneManager.getPlayerZone(player.getUniqueId()) != null) {
            giveSaddle(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Horse horse)) return;
        if (!horse.getPersistentDataContainer().has(
                plugin.getKey("cosmos_incursion_horse"), PersistentDataType.BOOLEAN)) return;
        // Block direct clicks on slot 0 (saddle slot) and shift-clicks that would auto-move into it
        boolean targetsSlot0 = event.getRawSlot() == 0
                || (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                && event.getClickedInventory() != event.getInventory());
        if (targetsSlot0) {
            event.setCancelled(true);
            ((Player) event.getWhoClicked()).updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Horse horse)) return;
        if (!horse.getPersistentDataContainer().has(
                plugin.getKey("cosmos_incursion_horse"), PersistentDataType.BOOLEAN)) return;
        if (event.getRawSlots().contains(0)) {
            event.setCancelled(true);
            ((Player) event.getWhoClicked()).updateInventory();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (isCosmosSaddle(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityLunge(EntityLungeEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        UUID horseId = playerHorses.get(player.getUniqueId());
        if (horseId == null) return;
        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.getUniqueId().equals(horseId)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!permanentZoneManager.isInsideAnyZone(player.getLocation())) {
            cleanupPlayer(player);
        }
    }

    public boolean hasActiveHorse(Player player) {
        return playerHorses.containsKey(player.getUniqueId());
    }

    /**
     * Force-dismount a player from their cosmos horse due to combat. Saddle is returned.
     */
    public void dismountForCombat(Player player) {
        UUID horseId = playerHorses.remove(player.getUniqueId());
        if (horseId == null) return;
        Entity horse = Bukkit.getEntity(horseId);
        if (horse != null && !horse.isDead()) {
            horse.eject();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!horse.isDead()) horse.remove();
            }, 1L);
        }
        giveSaddle(player);
        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                .append(Component.text("You were dismounted — cannot ride while in combat!", NamedTextColor.RED)));
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private void summonHorse(Player player, ItemStack saddleItem) {
        saddleItem.setAmount(saddleItem.getAmount() - 1);
        saddleHolders.remove(player.getUniqueId()); // consumed — allow re-grant on dismount

        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setAdult();
        horse.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40.0);
        horse.setHealth(40.0);
        horse.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(HORSE_SPEED);
        horse.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(1.0);
        horse.setCustomName("Zone Mount");
        horse.setCustomNameVisible(false);
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.getPersistentDataContainer().set(
                plugin.getKey("cosmos_incursion_horse"), PersistentDataType.BOOLEAN, true);

        playerHorses.put(player.getUniqueId(), horse.getUniqueId());
        horse.addPassenger(player);

        player.sendActionBar(Component.text("Mount summoned! Dismount to dismiss it.", NamedTextColor.GOLD));
    }

    private boolean hasAnyTownResources(Player player) {
        Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);
        if (townOpt.isEmpty()) {
            return !permanentZoneManager.getBuffer(player.getUniqueId()).isEmpty();
        }
        java.util.Set<UUID> members = townOpt.get().memberUuids();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (members.contains(online.getUniqueId())) {
                if (!permanentZoneManager.getBuffer(online.getUniqueId()).isEmpty()) return true;
            }
        }
        return false;
    }

    private boolean hasCosmosSaddle(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCosmosSaddle(item)) return true;
        }
        return false;
    }

    private void forceRemoveSaddle(Player player) {
        saddleHolders.remove(player.getUniqueId());
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isCosmosSaddle(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private boolean isCosmosSaddle(ItemStack item) {
        if (item == null || item.getType() != Material.SADDLE) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(plugin.getKey("cosmos_incursion_saddle"), PersistentDataType.BOOLEAN);
    }

    private ItemStack buildSaddle() {
        ItemStack saddle = new ItemStack(Material.SADDLE);
        ItemMeta meta = saddle.getItemMeta();
        meta.displayName(Component.text("Zone Saddle", NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Right-click to summon a fast mount.", NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("Requires no gathered resources.", NamedTextColor.DARK_GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("Only works inside incursion zones.", NamedTextColor.DARK_GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
        ));
        meta.getPersistentDataContainer().set(
                plugin.getKey("cosmos_incursion_saddle"), PersistentDataType.BOOLEAN, true);
        saddle.setItemMeta(meta);
        return saddle;
    }
}

