package net.mysterria.cosmos.domain.exclusion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.listener.ExclusionZoneCompassListener;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.PointOfInterest;
import net.mysterria.cosmos.domain.incursion.listener.GSitZoneListener;
import net.mysterria.cosmos.domain.incursion.listener.IncursionZoneHorseListener;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Runs every 5 ticks. Handles zone entry/exit tracking, compass updates,
 * squaremap visibility, and acts as a last-resort backup for escaped players.
 */
public class PermanentZonePlayerTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;
    private final IncursionZoneHorseListener horseListener;
    private final GSitZoneListener gsitZoneListener;
    private final Map<UUID, BossBar> zoneBossBars = new HashMap<>();
    private final Set<UUID> compassHolders = new HashSet<>();
    private int tickCount = 0;

    public PermanentZonePlayerTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager,
                                   IncursionZoneHorseListener horseListener, GSitZoneListener gsitZoneListener) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
        this.horseListener = horseListener;
        this.gsitZoneListener = gsitZoneListener;
    }

    @Override
    public void run() {
        tickCount++;
        boolean checkHorses = (tickCount % 4 == 0);
        Set<UUID> checkedHorses = checkHorses ? new HashSet<>() : null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            PermanentZone currentZone = permanentZoneManager.getZoneAt(player.getLocation());
            PermanentZone trackedZone = permanentZoneManager.getPlayerZone(player.getUniqueId());

            // Backup enforcement: player escaped with resources or while PvP tagged — return them immediately
            if (trackedZone != null && currentZone == null) {
                PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                boolean inCombat = TownsToolkit.isPlayerInCombat(player);
                if (!buffer.isEmpty() || inCombat) {
                    forceTeleportBack(player, trackedZone, buffer.isEmpty() && inCombat);
                    continue;
                }
            }

            // Normal zone entry/exit tracking
            if (!zonesEqual(currentZone, trackedZone)) {
                if (trackedZone != null) onExit(player, trackedZone);
                if (currentZone != null) {
                    if (permanentZoneManager.isOnZoneDeathCooldown(player.getUniqueId(), currentZone.getId())) {
                        pushOutsideZone(player, currentZone);
                        long remaining = permanentZoneManager.getZoneDeathCooldownRemainingSeconds(player.getUniqueId(), currentZone.getId());
                        player.sendActionBar(Component.text("Cannot enter — cooldown: ", NamedTextColor.RED)
                            .append(Component.text(formatCooldown(remaining), NamedTextColor.YELLOW)));
                        // Do NOT update tracked zone — keep it null so this check fires every tick
                        continue;
                    }
                    onEnter(player, currentZone);
                }
                permanentZoneManager.updatePlayerZone(player.getUniqueId(), currentZone);
            }

            // Force-dismount if player enters combat while riding a cosmos horse
            if (currentZone != null && horseListener.hasActiveHorse(player) && TownsToolkit.isPlayerInCombat(player)) {
                horseListener.dismountForCombat(player);
            }

            // Update compass and ensure saddle for players already tracked in a zone
            if (currentZone != null && zonesEqual(currentZone, trackedZone)) {
                updateCompass(player, currentZone);
                horseListener.giveSaddle(player); // idempotent — no-ops if already held
            } else if (currentZone != null) {
                updateCompass(player, currentZone);
            } else {
                // Player left without going through onExit (edge case) — clean up all zone items
                removeCompass(player);
                horseListener.cleanupPlayer(player);
                if (permanentZoneManager.clearMapHidden(player.getUniqueId())) {
                    plugin.getMapIntegration().showPlayerOnMap(player);
                }
            }

            // Check nearby horses to see if they are Zone Mounts outside of any zone
            if (checkHorses) {
                for (Entity entity : player.getNearbyEntities(80, 80, 80)) {
                    if (entity instanceof Horse horse && checkedHorses.add(horse.getUniqueId())) {
                        if (horse.getPersistentDataContainer().has(plugin.getKey("cosmos_incursion_horse"), PersistentDataType.BOOLEAN)) {
                            if (!permanentZoneManager.isInsideAnyZone(horse.getLocation())) {
                                horse.eject();
                                horse.remove();
                            }
                        }
                    }
                }
            }

            // Invisibility blocking check
            if (currentZone != null) {
                PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                if (buffer != null && !buffer.isEmpty()) {
                    if (player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                        player.removePotionEffect(PotionEffectType.INVISIBILITY);
                        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
                            .append(Component.text("gaze of cosmos doesnt allow you to become invisible", NamedTextColor.RED)));
                    }
                }
            }

            // Dynamic map visibility check
            updateMapVisibility(player, currentZone);

            // Update carrying resources metadata (exposed for other plugins without class dependencies)
            boolean carrying = (currentZone != null);
            if (carrying) {
                PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
                carrying = (buffer != null && !buffer.isEmpty());
            }

            if (carrying) {
                if (!player.hasMetadata("cosmos_carrying_resources")) {
                    player.setMetadata("cosmos_carrying_resources", new FixedMetadataValue(plugin, true));
                }
            } else {
                if (player.hasMetadata("cosmos_carrying_resources")) {
                    player.removeMetadata("cosmos_carrying_resources", plugin);
                }
            }
        }
    }

    private void updateMapVisibility(Player player, PermanentZone currentZone) {
        if (currentZone == null) {
            if (permanentZoneManager.clearMapHidden(player.getUniqueId())) {
                plugin.getMapIntegration().showPlayerOnMap(player);
            }
            return;
        }

        boolean shouldShow = false;
        PlayerResourceBuffer playerBuffer = permanentZoneManager.getBuffer(player.getUniqueId());
        if (playerBuffer != null && !playerBuffer.isEmpty()) {
            shouldShow = true;
        } else {
            Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);
            if (townOpt.isPresent()) {
                java.util.Set<UUID> members = townOpt.get().memberUuids();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.hasMetadata("NPC")) continue;
                    if (members.contains(online.getUniqueId())) {
                        PermanentZone mateZone = permanentZoneManager.getPlayerZone(online.getUniqueId());
                        if (mateZone != null && mateZone.getId().equals(currentZone.getId())) {
                            PlayerResourceBuffer mateBuffer = permanentZoneManager.getBuffer(online.getUniqueId());
                            if (mateBuffer != null && !mateBuffer.isEmpty()) {
                                shouldShow = true;
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (shouldShow) {
            if (permanentZoneManager.clearMapHidden(player.getUniqueId())) {
                plugin.getMapIntegration().showPlayerOnMap(player);
            }
        } else {
            if (!permanentZoneManager.isMapHidden(player.getUniqueId())) {
                permanentZoneManager.markMapHidden(player.getUniqueId());
                plugin.getMapIntegration().hidePlayerOnMap(player);
            }
        }
    }

    private void forceTeleportBack(Player player, PermanentZone zone, boolean combatTagged) {
        Location outside = player.getLocation();
        Location centroid = zone.getCentroid();
        if (centroid == null || centroid.getWorld() == null) return;

        double dx = centroid.getX() - outside.getX();
        double dz = centroid.getZ() - outside.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) return;
        dx /= dist;
        dz /= dist;

        Component reason = combatTagged
            ? Component.text(" while PvP tagged!", NamedTextColor.RED)
            : Component.text(" while carrying resources!", NamedTextColor.RED);

        for (double step = 1.0; step <= dist + 1; step += 1.0) {
            double x = outside.getX() + dx * step;
            double z = outside.getZ() + dz * step;
            if (zone.contains(new Location(outside.getWorld(), x, 64, z))) {
                double y = outside.getWorld().getHighestBlockYAt((int) x, (int) z) + 1.0;
                player.teleport(new Location(outside.getWorld(), x, y, z,
                    outside.getYaw(), outside.getPitch()));
                player.sendActionBar(Component.text("You cannot leave ", NamedTextColor.RED)
                    .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.YELLOW))
                    .append(reason));
                return;
            }
        }

        double y = centroid.getWorld().getHighestBlockYAt(centroid) + 1.0;
        player.teleport(new Location(centroid.getWorld(), centroid.getX(), y, centroid.getZ()));
        player.sendActionBar(Component.text("You cannot leave ", NamedTextColor.RED)
            .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.YELLOW))
            .append(reason));
    }

    private void onEnter(Player player, PermanentZone zone) {
        showZoneBossBar(player, zone);
        giveCompass(player, zone);
        horseListener.giveSaddle(player);
        if (gsitZoneListener != null) gsitZoneListener.dismountIfSitting(player);
        permanentZoneManager.markMapHidden(player.getUniqueId());
        plugin.getMapIntegration().hidePlayerOnMap(player);
        player.sendActionBar(
            Component.text("Entered extraction zone: ", NamedTextColor.DARK_RED)
                .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.RED))
                .append(Component.text(" | You cannot leave while carrying resources!", NamedTextColor.DARK_RED))
        );
    }

    private void onExit(Player player, PermanentZone zone) {
        permanentZoneManager.cancelExtractionChannel(player.getUniqueId());
        removeZoneBossBar(player);
        removeCompass(player);
        horseListener.cleanupPlayer(player);
        if (permanentZoneManager.clearMapHidden(player.getUniqueId())) {
            plugin.getMapIntegration().showPlayerOnMap(player);
        }
        player.sendActionBar(
            Component.text("You have left ", NamedTextColor.GRAY)
                .append(Component.text(formatZoneName(zone.getName()), NamedTextColor.WHITE))
        );
    }

    // ── Compass management ────────────────────────────────────────────────────────

    private void giveCompass(Player player, PermanentZone zone) {
        if (hasCosmosCompass(player)) return;
        ItemStack compass = buildCompass(zone, player.getLocation(),
            permanentZoneManager.getActivePoIs(zone));
        player.getInventory().addItem(compass);
        compassHolders.add(player.getUniqueId());
        player.sendMessage(Component.text("[Cosmos] ", NamedTextColor.DARK_RED)
            .append(Component.text("You received a ", NamedTextColor.GRAY))
            .append(Component.text("Zone Compass", NamedTextColor.AQUA))
            .append(Component.text(" that points to the nearest Point of Interest.", NamedTextColor.GRAY)));
    }

    private void removeCompass(Player player) {
        if (!compassHolders.remove(player.getUniqueId())) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isCosmosCompass(contents[i])) {
                player.getInventory().setItem(i, null);
            }
        }
    }

    private void updateCompass(Player player, PermanentZone zone) {
        if (!compassHolders.contains(player.getUniqueId())) return;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (!isCosmosCompass(item)) continue;

            CompassMeta meta = (CompassMeta) item.getItemMeta();
            if (ExclusionZoneCompassListener.isExtractionMode(plugin, item)) {
                updateCompassToExtraction(meta, player, zone);
            } else {
                updateCompassToPoi(meta, player, zone);
            }
            item.setItemMeta(meta);
            player.getInventory().setItem(i, item);
            break;
        }
    }

    private void updateCompassToPoi(CompassMeta meta, Player player, PermanentZone zone) {
        List<PointOfInterest> pois = permanentZoneManager.getActivePoIs(zone);
        PointOfInterest nearest = nearestActivePoi(player.getLocation(), pois);
        if (nearest != null) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(nearest.getLocation());
            meta.displayName(Component.text("Zone Compass → ", NamedTextColor.AQUA)
                .append(Component.text(nearest.getResourceType().name(), resourceColor(nearest.getResourceType())))
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Points to the nearest active PoI.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.setLodestone(null);
            meta.setLodestoneTracked(false);
            meta.displayName(Component.text("Zone Compass", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("No active Points of Interest in this zone.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        }
    }

    private void updateCompassToExtraction(CompassMeta meta, Player player, PermanentZone zone) {
        List<ExtractionPoint> eps = permanentZoneManager.getActiveExtractionPoints(zone);
        ExtractionPoint nearest = nearestActiveExtractionPoint(player.getLocation(), eps);
        if (nearest != null) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(nearest.getLocation());
            meta.displayName(Component.text("Zone Compass → ", NamedTextColor.GREEN)
                .append(Component.text("Extraction Point", NamedTextColor.DARK_GREEN))
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Points to the nearest Extraction Point.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Stand here to deposit your resources.", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.setLodestone(null);
            meta.setLodestoneTracked(false);
            meta.displayName(Component.text("Zone Compass", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("No active Extraction Points in this zone.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        }
    }

    private ItemStack buildCompass(PermanentZone zone, Location playerLoc, List<PointOfInterest> pois) {
        ItemStack compass = new ItemStack(Material.COMPASS);
        CompassMeta meta = (CompassMeta) compass.getItemMeta();
        meta.getPersistentDataContainer().set(
            plugin.getKey("cosmos_zone_compass"), PersistentDataType.BOOLEAN, true);

        PointOfInterest nearest = nearestActivePoi(playerLoc, pois);
        if (nearest != null) {
            meta.setLodestoneTracked(false);
            meta.setLodestone(nearest.getLocation());
            meta.displayName(Component.text("Zone Compass → ", NamedTextColor.AQUA)
                .append(Component.text(nearest.getResourceType().name(), resourceColor(nearest.getResourceType())))
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Points to the nearest active PoI.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        } else {
            meta.setLodestone(null);
            meta.setLodestoneTracked(false);
            meta.displayName(Component.text("Zone Compass", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("No active Points of Interest in this zone.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
            ));
        }
        compass.setItemMeta(meta);
        return compass;
    }

    private ExtractionPoint nearestActiveExtractionPoint(Location from, List<ExtractionPoint> eps) {
        ExtractionPoint nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (ExtractionPoint ep : eps) {
            if (!ep.isActive()) continue;
            double dist = from.distanceSquared(ep.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                nearest = ep;
            }
        }
        return nearest;
    }

    private PointOfInterest nearestActivePoi(Location from, List<PointOfInterest> pois) {
        PointOfInterest nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (PointOfInterest poi : pois) {
            if (!poi.isActive()) continue;
            double dist = from.distanceSquared(poi.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                nearest = poi;
            }
        }
        return nearest;
    }

    private boolean hasCosmosCompass(Player player) {
        UUID uuid = player.getUniqueId();
        if (compassHolders.contains(uuid)) return true;
        // Slow path: re-sync set after server restart
        for (ItemStack item : player.getInventory().getContents()) {
            if (isCosmosCompass(item)) {
                compassHolders.add(uuid);
                return true;
            }
        }
        return false;
    }

    private boolean isCosmosCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(plugin.getKey("cosmos_zone_compass"), PersistentDataType.BOOLEAN);
    }

    private static NamedTextColor resourceColor(net.mysterria.cosmos.domain.exclusion.model.source.ResourceType type) {
        return switch (type) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.WHITE;
            case GEMS -> NamedTextColor.GREEN;
        };
    }

    // ── Boss bar management ───────────────────────────────────────────────────────

    private void showZoneBossBar(Player player, PermanentZone zone) {
        removeZoneBossBar(player);

        BarColor barColor = switch (zone.getTier()) {
            case SAFE   -> BarColor.GREEN;
            case MEDIUM -> BarColor.YELLOW;
            case HARD   -> BarColor.RED;
        };

        String barTitle = switch (zone.getTier()) {
            case SAFE   -> "PvP Enabled | No item drops on death";
            case MEDIUM -> "PvP Enabled | ~33% item drop on death";
            case HARD   -> "PvP Enabled | All items drop on death";
        };

        BossBar bar = Bukkit.createBossBar(barTitle, barColor, BarStyle.SOLID);
        bar.setProgress(1.0);
        bar.addPlayer(player);
        zoneBossBars.put(player.getUniqueId(), bar);
    }

    private void removeZoneBossBar(Player player) {
        BossBar bar = zoneBossBars.remove(player.getUniqueId());
        if (bar != null) bar.removePlayer(player);
    }

    private static String formatZoneName(String name) {
        return name.replace('_', ' ');
    }

    private boolean zonesEqual(PermanentZone a, PermanentZone b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.getId().equals(b.getId());
    }

    /** Push player out of a polygon zone by moving them away from the centroid. */
    private void pushOutsideZone(Player player, PermanentZone zone) {
        Location loc = player.getLocation();
        Location centroid = zone.getCentroid();
        if (centroid == null || centroid.getWorld() == null) return;

        double dx = loc.getX() - centroid.getX();
        double dz = loc.getZ() - centroid.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.1) { dx = 1; dz = 0; } else { dx /= dist; dz /= dist; }

        // Walk outward from centroid in 1-block steps until outside the polygon
        for (double step = dist + 1; step <= dist + 200; step += 1.0) {
            double tx = centroid.getX() + dx * step;
            double tz = centroid.getZ() + dz * step;
            if (!zone.contains(new Location(loc.getWorld(), tx, 64, tz))) {
                double ty = loc.getWorld().getHighestBlockYAt((int) tx, (int) tz) + 1.0;
                player.teleport(new Location(loc.getWorld(), tx, ty, tz, loc.getYaw(), loc.getPitch()));
                return;
            }
        }
        // Fallback: teleport to centroid (shouldn't happen)
        double ty = centroid.getWorld().getHighestBlockYAt(centroid) + 1.0;
        player.teleport(new Location(centroid.getWorld(), centroid.getX(), ty, centroid.getZ()));
    }

    private static String formatCooldown(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return h + "h " + m + "m " + s + "s";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
