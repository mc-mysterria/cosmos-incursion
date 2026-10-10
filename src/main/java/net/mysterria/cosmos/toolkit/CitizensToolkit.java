package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.model.HollowBody;
import net.mysterria.cosmos.domain.combat.service.DeathAudit;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Citizens API integration for Hollow Body NPCs
 * Creates NPCs that represent combat-logged players
 */
public class CitizensToolkit {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
    /** Grace period after NPC despawn before pending reconnect state is evicted. */
    private static final long PENDING_EVICT_GRACE_MILLIS = 6L * 60L * 60L * 1000L;

    private final Map<UUID, HollowBody> hollowBodies;
    private final Map<Integer, UUID> npcIdToPlayerId;
    private NPCRegistry registry;

    public CitizensToolkit(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigLoader().getConfig();
        this.hollowBodies = new HashMap<>();
        this.npcIdToPlayerId = new HashMap<>();
    }

    /**
     * Initialize Citizens integration
     */
    public boolean initialize() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Citizens")) {
            return false; // Silent fail if plugin not present
        }

        try {
            // Use the default registry instead of creating a named one
            // Temporary NPCs like Hollow Bodies don't need a separate registry with persistence
            this.registry = CitizensAPI.getNPCRegistry();
            if (this.registry == null) {
                plugin.log("Citizens registry is null - API not ready");
                return false;
            }
            plugin.log("Citizens integration enabled - Hollow Body NPCs active");
            return true;
        } catch (IllegalStateException e) {
            // Citizens API not ready yet
            return false;
        } catch (Exception e) {
            plugin.log("Failed to initialize Citizens integration: " + e.getMessage());
            return false;
        }
    }

    /**
     * Create a Hollow Body NPC for a combat-logged player.
     * Transfers inventory to the hollow (player is cleared + saved) so killing the NPC cannot dupe items.
     */
    public HollowBody createHollowBody(Player player, Location location) {
        if (registry == null) {
            plugin.log("Cannot create Hollow Body - Citizens not initialized");
            return null;
        }

        try {
            // Deep-clone storage / armor / offhand separately (no overlap → no double drops)
            PlayerInventory playerInv = player.getInventory();
            ItemStack[] inventory = deepClone(playerInv.getStorageContents());
            ItemStack[] armor = deepClone(playerInv.getArmorContents());
            ItemStack offhand = cloneOrNull(playerInv.getItemInOffHand());

            // Create NPC name from config
            String npcName = config.getNpcNameFormat().replace("%player%", player.getName());

            // Create NPC
            NPC npc = registry.createNPC(EntityType.PLAYER, npcName);

            // Make NPC vulnerable (can be killed)
            npc.setProtected(false);

            // Copy player appearance
            npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, true);
            npc.data().setPersistent(NPC.Metadata.ALWAYS_USE_NAME_HOLOGRAM, false);
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);

            // Set skin to match player
            npc.getOrAddTrait(SkinTrait.class).setSkinName(player.getName());

            npc.spawn(location);

            // Calculate duration
            long durationMillis = config.getNpcDurationMinutes() * 60_000L;

            // Create HollowBody wrapper with transferred inventory
            HollowBody hollowBody = new HollowBody(
                    player.getUniqueId(),
                    player.getName(),
                    npc.getId(),
                    location,
                    durationMillis,
                    inventory,
                    armor,
                    offhand
            );

            // Transfer: clear player so only the hollow holds these items, then persist to disk
            InventoryUtils.clearPlayerInventory(player);
            player.saveData();

            // Store mappings
            hollowBodies.put(player.getUniqueId(), hollowBody);
            npcIdToPlayerId.put(npc.getId(), player.getUniqueId());

            plugin.log("Created Hollow Body NPC for " + player.getName() + " (ID: " + npc.getId() + ") - inventory transferred");
            return hollowBody;
        } catch (Exception e) {
            plugin.log("Error creating Hollow Body for " + player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Remove a Hollow Body NPC and forget reconnect state (caller must restore/drop first if needed)
     */
    public void removeHollowBody(UUID playerId) {
        HollowBody hollowBody = hollowBodies.remove(playerId);
        if (hollowBody != null) {
            if (!hollowBody.isNpcRemoved()) {
                removeNPC(hollowBody.getNpcId());
            }
            npcIdToPlayerId.remove(hollowBody.getNpcId());
            plugin.log("Removed Hollow Body for player " + playerId);
        }
    }

    /**
     * Remove an NPC by ID
     */
    private void removeNPC(int npcId) {
        if (registry == null) {
            return;
        }

        try {
            NPC npc = registry.getById(npcId);
            if (npc != null) {
                npc.destroy();
            }
        } catch (Exception e) {
            plugin.log("Error removing NPC " + npcId + ": " + e.getMessage());
        }
    }

    /**
     * Mark an NPC as killed and drop its inventory once
     */
    public void markNPCKilled(int npcId, org.bukkit.Location deathLocation) {
        markNPCKilled(npcId, deathLocation, null);
    }

    /**
     * Same as {@link #markNPCKilled(int, Location)}. {@code deathEvent} is the NPC's death event
     * (null when not available); it only feeds the killer facts of the audit row.
     */
    public void markNPCKilled(int npcId, org.bukkit.Location deathLocation, EntityDeathEvent deathEvent) {
        UUID playerId = npcIdToPlayerId.get(npcId);
        if (playerId != null) {
            HollowBody hollowBody = hollowBodies.get(playerId);
            if (hollowBody != null) {
                boolean alreadyDropped = hollowBody.isItemsDropped();
                hollowBody.markKilled(deathLocation);

                // Drop the player's inventory at death location (once)
                List<Map<String, Object>> droppedStacks = new ArrayList<>();
                dropInventory(hollowBody, deathLocation, droppedStacks);

                plugin.log("Hollow Body NPC " + npcId + " was killed (player: " + playerId + ") - items dropped");
                emitHollowBodyKilled(hollowBody, deathEvent, alreadyDropped, droppedStacks);
            }
        }
    }

    /**
     * Records the death of a combat-log body: owner, NPC, where it died, who or what killed it, and
     * the stacks dropped (material and amount, read from the stacks the drop loop holds). COMMITTED
     * once the stored items are dropped, FAILED while they are still held because no valid drop
     * location existed. Built from the hollow body and the death event already in hand.
     */
    private void emitHollowBodyKilled(HollowBody hollowBody, EntityDeathEvent deathEvent, boolean alreadyDropped,
                                      List<Map<String, Object>> droppedStacks) {
        try {
            int droppedAmount = 0;
            for (Map<String, Object> stack : droppedStacks) droppedAmount += (Integer) stack.get("amount");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", hollowBody.getPlayerName());
            metadata.put("npc_id", hollowBody.getNpcId());
            MysterriaAuditEmitter.putLocation(metadata, hollowBody.getDeathLocation());
            UUID ownerId = null;
            UUID attackerId = null;
            if (deathEvent != null) {
                LivingEntity body = deathEvent.getEntity();
                DeathAudit.KillerFacts facts = DeathAudit.putKillerFacts(metadata, body, deathEvent.getDamageSource(), body.getKiller());
                ownerId = facts.ownerId();
                attackerId = facts.attackerId();
            }
            metadata.put("items_already_dropped", alreadyDropped);
            metadata.put("items_dropped", hollowBody.isItemsDropped());
            metadata.put("dropped_stack_count", droppedStacks.size());
            metadata.put("dropped_item_count", droppedAmount);
            metadata.put("dropped", droppedStacks);

            boolean dropped = hollowBody.isItemsDropped();
            DeathAudit.emit(plugin, "incursion.hollow_body_killed",
                    dropped ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.NORMAL,
                    ownerId, hollowBody.getPlayerId(), attackerId, dropped ? null : "items_not_dropped", metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Drop a Hollow Body's stored inventory at a location, then clear the snapshot.
     */
    private void dropInventory(HollowBody hollowBody, org.bukkit.Location location) {
        dropInventory(hollowBody, location, null);
    }

    /**
     * Same drop; {@code droppedStacks}, when not null, receives material and amount of each dropped stack.
     */
    private void dropInventory(HollowBody hollowBody, org.bukkit.Location location, List<Map<String, Object>> droppedStacks) {
        if (hollowBody.isItemsDropped()) {
            plugin.log("Skipping hollow inventory drop for " + hollowBody.getPlayerName() + " - already dropped");
            return;
        }
        if (location == null || !location.isWorldLoaded()) {
            Location fallback = hollowBody.getSpawnLocation();
            if (fallback == null || !fallback.isWorldLoaded()) {
                plugin.log("Cannot drop inventory for " + hollowBody.getPlayerName()
                        + " - invalid death location and no spawn fallback");
                return;
            }
            plugin.log("Invalid death drop location for " + hollowBody.getPlayerName()
                    + " - falling back to hollow spawn");
            location = fallback;
        }

        org.bukkit.World world = location.getWorld();
        int droppedItems = 0;

        droppedItems += dropItemArray(world, location, hollowBody.getInventory(), droppedStacks);
        droppedItems += dropItemArray(world, location, hollowBody.getArmor(), droppedStacks);
        if (hollowBody.getOffhand() != null && hollowBody.getOffhand().getType() != Material.AIR) {
            world.dropItemNaturally(location, hollowBody.getOffhand());
            if (droppedStacks != null) droppedStacks.add(DeathAudit.stack(hollowBody.getOffhand()));
            droppedItems++;
        }

        hollowBody.clearStoredItems();
        plugin.log("Dropped " + droppedItems + " items from " + hollowBody.getPlayerName() + "'s Hollow Body");
    }

    private int dropItemArray(org.bukkit.World world, Location location, ItemStack[] items,
                              List<Map<String, Object>> droppedStacks) {
        if (items == null) {
            return 0;
        }
        int dropped = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                world.dropItemNaturally(location, item);
                if (droppedStacks != null) droppedStacks.add(DeathAudit.stack(item));
                dropped++;
            }
        }
        return dropped;
    }

    /**
     * Get Hollow Body for a player (includes pending reconnect state after NPC despawn)
     */
    public HollowBody getHollowBody(UUID playerId) {
        return hollowBodies.get(playerId);
    }

    /**
     * Check if player has an active Hollow Body / pending combat-log outcome
     */
    public boolean hasHollowBody(UUID playerId) {
        return hollowBodies.containsKey(playerId);
    }

    /**
     * Despawn expired Hollow Body NPC entities but keep outcome state until the player rejoins.
     * Prevents dupe when a killed hollow times out before reconnect, and item loss when an
     * unkilled hollow times out after inventory was transferred off the player.
     */
    public void cleanupExpired() {
        if (registry == null) {
            return;
        }

        long now = System.currentTimeMillis();
        List<UUID> toEvict = new ArrayList<>();

        for (HollowBody hollowBody : hollowBodies.values()) {
            if (hollowBody.shouldDespawn()) {
                removeNPC(hollowBody.getNpcId());
                npcIdToPlayerId.remove(hollowBody.getNpcId());
                hollowBody.markNpcRemoved();
                plugin.log("Hollow Body NPC for " + hollowBody.getPlayerName()
                        + " despawned (timeout) - pending reconnect state kept (killed="
                        + hollowBody.isWasKilled() + ")");
                emitHollowBodyDespawned(hollowBody, "timeout");
            }

            // Evict pending reconnect state after grace TTL so never-returning players
            // do not retain item snapshots for the whole server session.
            if (hollowBody.isNpcRemoved()
                    && now >= hollowBody.getDespawnTime() + PENDING_EVICT_GRACE_MILLIS) {
                toEvict.add(hollowBody.getPlayerId());
            }
        }

        for (UUID playerId : toEvict) {
            HollowBody hollowBody = hollowBodies.get(playerId);
            if (hollowBody == null) {
                continue;
            }
            boolean alreadyDropped = hollowBody.isItemsDropped();
            List<Map<String, Object>> droppedStacks = new ArrayList<>();
            if (!hollowBody.isItemsDropped()) {
                dropInventory(hollowBody, hollowBody.getSpawnLocation(), droppedStacks);
                if (!hollowBody.isItemsDropped()) {
                    // Spawn world not loaded: keep the pending state and retry on the next sweep
                    continue;
                }
            }
            hollowBodies.remove(playerId);
            npcIdToPlayerId.remove(hollowBody.getNpcId());
            plugin.log("Evicted pending hollow state for " + hollowBody.getPlayerName()
                    + " after grace TTL (killed=" + hollowBody.isWasKilled() + ")");
            emitHollowBodyEvicted(hollowBody, alreadyDropped, droppedStacks);
        }
    }

    /**
     * Records a combat-log body whose NPC was removed while the owner's reconnect state is kept
     * ({@code reason} is {@code timeout} or {@code event_end}): owner, NPC, where it was spawned
     * (the code holds no later position), whether it had been killed, and how many stacks and items
     * the body still holds for the owner's next join. Built from the hollow body already in hand;
     * Citizens swallows destroy errors in {@link #removeNPC}, so the row records the state change.
     */
    private void emitHollowBodyDespawned(HollowBody hollowBody, String reason) {
        try {
            int heldStacks = 0;
            int heldItems = 0;
            for (ItemStack[] items : new ItemStack[][]{hollowBody.getInventory(), hollowBody.getArmor()}) {
                if (items == null) continue;
                for (ItemStack item : items) {
                    if (item == null || item.getType() == Material.AIR) continue;
                    heldStacks++;
                    heldItems += item.getAmount();
                }
            }
            ItemStack offhand = hollowBody.getOffhand();
            if (offhand != null && offhand.getType() != Material.AIR) {
                heldStacks++;
                heldItems += offhand.getAmount();
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", hollowBody.getPlayerName());
            metadata.put("npc_id", hollowBody.getNpcId());
            MysterriaAuditEmitter.putLocation(metadata, hollowBody.getSpawnLocation());
            metadata.put("despawn_reason", reason);
            metadata.put("was_killed", hollowBody.isWasKilled());
            metadata.put("lifetime_seconds", (System.currentTimeMillis() - hollowBody.getSpawnTime()) / 1000L);
            metadata.put("items_already_dropped", hollowBody.isItemsDropped());
            metadata.put("held_stack_count", heldStacks);
            metadata.put("held_item_count", heldItems);
            metadata.put("reconnect_state_kept", true);

            DeathAudit.emit(plugin, "incursion.hollow_body_despawned", AuditOutcome.COMMITTED, AuditRisk.NORMAL,
                    null, hollowBody.getPlayerId(), null, reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Records the pending reconnect state of a combat-log body forgotten after the grace period:
     * what became of the stored items (already dropped when the body died, or dropped at the spawn
     * location now) and the stacks dropped. A body whose items cannot be dropped yet is not evicted
     * and writes no row; it is retried on the next sweep. Built from the hollow body and the drop
     * loop's stack list already in hand.
     */
    private void emitHollowBodyEvicted(HollowBody hollowBody, boolean alreadyDropped,
                                       List<Map<String, Object>> droppedStacks) {
        try {
            int droppedAmount = 0;
            for (Map<String, Object> stack : droppedStacks) droppedAmount += (Integer) stack.get("amount");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("player_name", hollowBody.getPlayerName());
            metadata.put("npc_id", hollowBody.getNpcId());
            MysterriaAuditEmitter.putLocation(metadata, hollowBody.getSpawnLocation());
            metadata.put("was_killed", hollowBody.isWasKilled());
            metadata.put("grace_minutes", PENDING_EVICT_GRACE_MILLIS / 60_000L);
            metadata.put("items_state", alreadyDropped ? "already_dropped" : "dropped_at_spawn");
            metadata.put("dropped_stack_count", droppedStacks.size());
            metadata.put("dropped_item_count", droppedAmount);
            metadata.put("dropped", droppedStacks);

            DeathAudit.emit(plugin, "incursion.hollow_body_evicted", AuditOutcome.COMMITTED, AuditRisk.NORMAL,
                    null, hollowBody.getPlayerId(), null, null, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    /**
     * Despawn all Hollow Body NPC entities (event end) but keep reconnect outcome state.
     */
    public void despawnAllHollowBodies() {
        if (registry == null) {
            return;
        }

        int despawnedCount = 0;
        for (HollowBody hollowBody : hollowBodies.values()) {
            if (!hollowBody.isNpcRemoved()) {
                removeNPC(hollowBody.getNpcId());
                npcIdToPlayerId.remove(hollowBody.getNpcId());
                hollowBody.markNpcRemoved();
                despawnedCount++;
                emitHollowBodyDespawned(hollowBody, "event_end");
            }
        }

        plugin.log("Force-despawned " + despawnedCount + " Hollow Body NPCs due to event end (reconnect state retained)");
    }

    /**
     * Check if Citizens is available
     */
    public boolean isAvailable() {
        return registry != null;
    }

    private static ItemStack[] deepClone(ItemStack[] source) {
        if (source == null) {
            return null;
        }
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = cloneOrNull(source[i]);
        }
        return copy;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        return item.clone();
    }

}
