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
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
    private final File recoveryFolder;

    private final Map<UUID, HollowBody> hollowBodies;
    private final Map<Integer, UUID> npcIdToPlayerId;
    private NPCRegistry registry;

    public CitizensToolkit(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigLoader().getConfig();
        this.hollowBodies = new HashMap<>();
        this.npcIdToPlayerId = new HashMap<>();
        this.recoveryFolder = new File(plugin.getDataFolder(), "hollow-recovery");
        loadRecovery();
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
            List<NPC> staleNpcs = new ArrayList<>();
            for (NPC npc : registry) {
                if (!npc.data().get("cosmos_hollow_owner", "").isEmpty()) staleNpcs.add(npc);
            }
            staleNpcs.forEach(NPC::destroy);
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
        if (hasHollowBody(player.getUniqueId())) return null;
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
            npc.data().setPersistent("cosmos_hollow_owner", player.getUniqueId().toString());

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

            // Write the inventory authority before saving an empty player inventory.
            try {
                saveRecovery(hollowBody);
            } catch (IllegalStateException e) {
                npc.destroy();
                throw e;
            }
            hollowBodies.put(player.getUniqueId(), hollowBody);
            npcIdToPlayerId.put(npc.getId(), player.getUniqueId());
            player.getPersistentDataContainer().set(plugin.getKey("hollow_transfer"),
                    PersistentDataType.LONG, hollowBody.getSpawnTime());
            InventoryUtils.clearPlayerInventory(player);
            player.saveData();

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
        HollowBody hollowBody = hollowBodies.get(playerId);
        if (hollowBody != null) {
            try {
                Files.deleteIfExists(new File(recoveryFolder, playerId + ".yml").toPath());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot consume hollow recovery for " + playerId, e);
            }
            hollowBodies.remove(playerId);
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
                saveRecovery(hollowBody);

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
            Location fallback = hollowBody.getSpawnLocation();
            if (fallback == null || fallback.getWorld() == null) {
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

        droppedItems += dropItemArray(world, location, hollowBody.getInventory());
        droppedItems += dropItemArray(world, location, hollowBody.getArmor());
        if (hollowBody.getOffhand() != null && hollowBody.getOffhand().getType() != Material.AIR) {
            world.dropItemNaturally(location, hollowBody.getOffhand());
            droppedItems++;
        }

        // Keep the stored items on disk until they are dropped, so a failure here is retried on restart
        hollowBody.clearStoredItems();
        saveRecovery(hollowBody);
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
     * Get the owner of a Hollow Body NPC entity, or null if the entity is not one
     */
    public UUID getHollowOwner(Entity entity) {
        if (registry == null) return null;
        NPC npc = registry.getNPC(entity);
        return npc == null ? null : npcIdToPlayerId.get(npc.getId());
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
            if (!hollowBody.isItemsDropped()) {
                dropInventory(hollowBody, hollowBody.getSpawnLocation());
                if (!hollowBody.isItemsDropped()) {
                    plugin.log("WARNING: voiding undroppable hollow items for "
                            + hollowBody.getPlayerName() + " during grace eviction");
                    hollowBody.clearStoredItems();
                }
            }
            removeHollowBody(playerId);
            plugin.log("Evicted pending hollow state for " + hollowBody.getPlayerName()
                    + " after grace TTL (killed=" + hollowBody.isWasKilled() + ")");
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

    private void saveRecovery(HollowBody body) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("player-name", body.getPlayerName());
        data.set("npc-id", body.getNpcId());
        data.set("spawn-location", body.getSpawnLocation());
        data.set("spawn-time", body.getSpawnTime());
        data.set("despawn-time", body.getDespawnTime());
        data.set("inventory", body.getInventory() == null ? null : Arrays.asList(body.getInventory()));
        data.set("armor", body.getArmor() == null ? null : Arrays.asList(body.getArmor()));
        data.set("offhand", body.getOffhand());
        data.set("killed", body.isWasKilled());
        data.set("items-dropped", body.isItemsDropped());
        data.set("death-location", body.getDeathLocation());
        File target = new File(recoveryFolder, body.getPlayerId() + ".yml");
        File temporary = new File(recoveryFolder, body.getPlayerId() + ".tmp");
        try {
            Files.createDirectories(recoveryFolder.toPath());
            data.save(temporary);
            try (FileChannel channel = FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save hollow recovery for " + body.getPlayerId(), e);
        }
    }

    private void loadRecovery() {
        File[] files = recoveryFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            try {
                YamlConfiguration data = new YamlConfiguration();
                data.load(file);
                UUID playerId = UUID.fromString(file.getName().replace(".yml", ""));
                long spawnTime = data.getLong("spawn-time");
                HollowBody body = new HollowBody(playerId, data.getString("player-name"), data.getInt("npc-id"),
                        data.getLocation("spawn-location"), spawnTime, data.getLong("despawn-time") - spawnTime,
                        readItems(data, "inventory"), readItems(data, "armor"), data.getItemStack("offhand"));
                if (data.getBoolean("killed")) body.markKilled(data.getLocation("death-location"));
                if (data.getBoolean("items-dropped")) body.clearStoredItems();
                body.markNpcRemoved();
                hollowBodies.put(playerId, body);
                if (body.isWasKilled() && !body.isItemsDropped()) {
                    dropInventory(body, body.getDeathLocation());
                }
            } catch (Exception e) {
                plugin.log("Skipping unreadable hollow recovery " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private static ItemStack[] readItems(YamlConfiguration data, String key) {
        List<?> items = data.getList(key);
        return items == null ? null : items.toArray(new ItemStack[0]);
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
