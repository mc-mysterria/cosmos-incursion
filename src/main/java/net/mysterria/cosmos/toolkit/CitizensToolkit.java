package net.mysterria.cosmos.toolkit;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.trait.SkinTrait;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.combat.model.HollowBody;
import net.mysterria.cosmos.config.CosmosConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles Citizens API integration for Hollow Body NPCs
 * Creates NPCs that represent combat-logged players
 */
public class CitizensToolkit {

    private final CosmosIncursion plugin;
    private final CosmosConfig config;
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
            clearPlayerInventory(player);
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
        UUID playerId = npcIdToPlayerId.get(npcId);
        if (playerId != null) {
            HollowBody hollowBody = hollowBodies.get(playerId);
            if (hollowBody != null) {
                hollowBody.markKilled(deathLocation);

                // Drop the player's inventory at death location (once)
                dropInventory(hollowBody, deathLocation);

                plugin.log("Hollow Body NPC " + npcId + " was killed (player: " + playerId + ") - items dropped");
            }
        }
    }

    /**
     * Drop a Hollow Body's stored inventory at a location, then clear the snapshot.
     */
    private void dropInventory(HollowBody hollowBody, org.bukkit.Location location) {
        if (hollowBody.isItemsDropped()) {
            plugin.log("Skipping hollow inventory drop for " + hollowBody.getPlayerName() + " - already dropped");
            return;
        }
        if (location == null || location.getWorld() == null) {
            plugin.log("Cannot drop inventory - invalid location");
            return;
        }

        org.bukkit.World world = location.getWorld();
        int droppedItems = 0;

        droppedItems += dropItemArray(world, location, hollowBody.getInventory());
        droppedItems += dropItemArray(world, location, hollowBody.getArmor());
        if (hollowBody.getOffhand() != null && hollowBody.getOffhand().getType() != Material.AIR) {
            world.dropItemNaturally(location, hollowBody.getOffhand());
            droppedItems++;
        }

        hollowBody.clearStoredItems();
        plugin.log("Dropped " + droppedItems + " items from " + hollowBody.getPlayerName() + "'s Hollow Body");
    }

    private int dropItemArray(org.bukkit.World world, Location location, ItemStack[] items) {
        if (items == null) {
            return 0;
        }
        int dropped = 0;
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                world.dropItemNaturally(location, item);
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

        for (HollowBody hollowBody : hollowBodies.values()) {
            if (hollowBody.shouldDespawn()) {
                removeNPC(hollowBody.getNpcId());
                npcIdToPlayerId.remove(hollowBody.getNpcId());
                hollowBody.markNpcRemoved();
                plugin.log("Hollow Body NPC for " + hollowBody.getPlayerName()
                        + " despawned (timeout) - pending reconnect state kept (killed="
                        + hollowBody.isWasKilled() + ")");
            }
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

    private static void clearPlayerInventory(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
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
