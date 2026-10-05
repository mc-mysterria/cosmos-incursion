package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Command(name = "cosmos")
public class ExclusionCommand {

    private final CosmosIncursion plugin;

    public ExclusionCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Execute(name = "exclusion list")
    @Permission("cosmos.admin")
    public void exclusionList(@Context CommandSender sender) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        var zones = mgr.getAllZones();
        if (zones.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("No permanent zones registered.", NamedTextColor.YELLOW)));
            return;
        }
        sender.sendMessage(Component.text("=== Permanent Zones ===").color(NamedTextColor.GOLD));
        for (PermanentZone zone : zones) {
            Location centroid = zone.getCentroid();
            String pos = centroid != null
                    ? "(" + (int) centroid.getX() + ", " + (int) centroid.getZ() + ")"
                    : "(no vertices)";
            sender.sendMessage(Component.text("- " + zone.getName(), NamedTextColor.YELLOW)
                    .append(Component.text(" [" + zone.getTier().name() + "]", tierColor(zone.getTier())))
                    .append(Component.text(" centroid=" + pos, NamedTextColor.WHITE))
                    .append(Component.text(" verts=" + zone.getVertices().size(), NamedTextColor.GRAY))
                    .append(Component.text(" PoIs=" + mgr.getActivePoIs(zone).size() + " EPs=" + mgr.getActiveExtractionPoints(zone).size(), NamedTextColor.AQUA)));
        }
    }

    @Execute(name = "exclusion add")
    @Permission("cosmos.admin")
    public void exclusionAdd(@Context CommandSender sender, @Arg String name) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        if (mgr.getZone(name).isPresent()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' already exists. Use /cosmos exclusion vertex add " + name + " to add vertices.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = new PermanentZone(name, new java.util.ArrayList<>());
        boolean saved = mgr.tryAddZone(zone);
        emitZoneChange(sender, "admin.exclusion_zone_added", AuditRisk.NORMAL, saved, zoneRow(zone));
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Permanent zone '" + name + "' created. Add vertices with /cosmos exclusion vertex add " + name + ".", NamedTextColor.GREEN)));
    }

    @Execute(name = "exclusion vertex add")
    @Permission("cosmos.admin")
    public void exclusionVertexAdd(@Context CommandSender sender, @Arg String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        Optional<PermanentZone> zoneOpt = mgr.getZone(name);
        if (zoneOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = zoneOpt.get();
        zone.addVertex(player.getLocation());
        boolean saved = mgr.trySaveZones();
        int count = zone.getVertices().size();
        Map<String, Object> vertexRow = zoneRow(zone);
        vertexRow.put("vertex_index", count);
        putVertex(vertexRow, player.getLocation());
        // Once the polygon is valid the code below spawns the zone's PoIs and extraction points.
        vertexRow.put("spawn_triggered", count >= 3);
        emitZoneChange(sender, "admin.exclusion_vertex_added", AuditRisk.NORMAL, saved, vertexRow);
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Vertex #" + count + " added to '" + name + "'. ", NamedTextColor.GREEN))
                .append(Component.text("(" + (int) player.getX() + ", " + (int) player.getZ() + ")", NamedTextColor.GRAY)));
        if (count >= 3) {
            // Spawn PoIs once the polygon is valid
            mgr.spawnPoIsForZone(zone);
            mgr.spawnExtractionPoints(zone);
        }
        plugin.refreshPermanentZoneMarkers();
    }

    @Execute(name = "exclusion vertex remove")
    @Permission("cosmos.admin")
    public void exclusionVertexRemove(@Context CommandSender sender, @Arg String name, @Arg int index) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        Optional<PermanentZone> zoneOpt = mgr.getZone(name);
        if (zoneOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = zoneOpt.get();
        int size = zone.getVertices().size();
        if (index < 1 || index > size) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Index out of range (1-" + size + ").", NamedTextColor.RED)));
            return;
        }
        Location removedVertex = zone.getVertices().get(index - 1);
        zone.removeVertex(index - 1); // 1-based → 0-based
        boolean saved = mgr.trySaveZones();
        Map<String, Object> vertexRow = zoneRow(zone);
        vertexRow.put("vertex_index", index);
        putVertex(vertexRow, removedVertex);
        emitZoneChange(sender, "admin.exclusion_vertex_removed", AuditRisk.NORMAL, saved, vertexRow);
        plugin.refreshPermanentZoneMarkers();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Vertex #" + index + " removed from '" + name + "'.", NamedTextColor.GREEN)));
    }

    @Execute(name = "exclusion vertex list")
    @Permission("cosmos.admin")
    public void exclusionVertexList(@Context CommandSender sender, @Arg String name) {
        Optional<PermanentZone> zoneOpt = plugin.getPermanentZoneManager().getZone(name);
        if (zoneOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = zoneOpt.get();
        sender.sendMessage(Component.text("=== " + name + " vertices ===").color(NamedTextColor.GOLD));
        List<Location> verts = zone.getVertices();
        for (int i = 0; i < verts.size(); i++) {
            Location v = verts.get(i);
            sender.sendMessage(Component.text("  #" + (i + 1) + " ", NamedTextColor.YELLOW)
                    .append(Component.text("(" + (int) v.getX() + ", " + (int) v.getY() + ", " + (int) v.getZ() + ")", NamedTextColor.WHITE)));
        }
        if (verts.size() < 3) {
            sender.sendMessage(Component.text("  [Need at least 3 vertices for a valid polygon]", NamedTextColor.RED));
        }
    }

    @Execute(name = "exclusion remove")
    @Permission("cosmos.admin")
    public void exclusionRemove(@Context CommandSender sender, @Arg String name) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        Optional<PermanentZone> zone = mgr.getZone(name);
        if (zone.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        Map<String, Object> removedRow = zoneRow(zone.get());
        boolean saved = mgr.tryRemoveZone(zone.get().getId());
        emitZoneChange(sender, "admin.exclusion_zone_removed", AuditRisk.HIGH, saved, removedRow);
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Permanent zone '" + name + "' removed.", NamedTextColor.GREEN)));
    }

    @Execute(name = "exclusion tp")
    @Permission("cosmos.admin")
    public void exclusionTp(@Context CommandSender sender, @Arg String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Only players can use this command.", NamedTextColor.RED)));
            return;
        }
        Optional<PermanentZone> zone = plugin.getPermanentZoneManager().getZone(name);
        if (zone.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        Location centroid = zone.get().getCentroid();
        if (centroid == null) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' has no vertices yet.", NamedTextColor.RED)));
            return;
        }
        player.teleport(centroid);
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Teleported to '" + name + "'.", NamedTextColor.GREEN)));
    }

    @Execute(name = "exclusion info")
    @Permission("cosmos.admin")
    public void exclusionInfo(@Context CommandSender sender, @Arg String name) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        Optional<PermanentZone> zoneOpt = mgr.getZone(name);
        if (zoneOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = zoneOpt.get();
        sender.sendMessage(Component.text("=== " + zone.getName() + " ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Tier: ", NamedTextColor.YELLOW)
                .append(Component.text(zone.getTier().name(), tierColor(zone.getTier()))));
        sender.sendMessage(Component.text("Active PoIs: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(mgr.getActivePoIs(zone).size()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Active Extraction Points: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(mgr.getActiveExtractionPoints(zone).size()), NamedTextColor.WHITE)));

        Map<net.mysterria.cosmos.domain.exclusion.model.source.ResourceType, Double> remaining = mgr.getDailyBudgetRemaining(zone);
        if (!remaining.isEmpty()) {
            sender.sendMessage(Component.text("Daily Budget Remaining: ", NamedTextColor.YELLOW));
            for (net.mysterria.cosmos.domain.exclusion.model.source.ResourceType type : net.mysterria.cosmos.domain.exclusion.model.source.ResourceType.values()) {
                double rem = remaining.getOrDefault(type, 0.0);
                sender.sendMessage(Component.text("  " + type.name() + ": ", NamedTextColor.GRAY)
                        .append(Component.text(String.format("%.2f", rem), NamedTextColor.WHITE)));
            }
        } else {
            sender.sendMessage(Component.text("Daily Budget Remaining: ", NamedTextColor.YELLOW)
                    .append(Component.text("(resets on first PoI spawn)", NamedTextColor.DARK_GRAY)));
        }

        mgr.getActivePoIs(zone).forEach(poi ->
                sender.sendMessage(Component.text("  PoI: ", NamedTextColor.GRAY)
                        .append(Component.text(poi.getResourceType().name(), NamedTextColor.AQUA))
                        .append(Component.text(" cap=" + String.format("%.2f", poi.getResourceCap()), NamedTextColor.WHITE))
                        .append(Component.text(" rem=" + String.format("%.2f", poi.getResourcesRemaining()), NamedTextColor.GREEN))
                        .append(Component.text(" expires in " + ((poi.getActiveUntil() - System.currentTimeMillis()) / 1000) + "s", NamedTextColor.DARK_GRAY))));
    }

    @Execute(name = "exclusion tier")
    @Permission("cosmos.admin")
    public void exclusionTier(@Context CommandSender sender, @Arg String name, @Arg String tierName) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        Optional<PermanentZone> zoneOpt = mgr.getZone(name);
        if (zoneOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Zone '" + name + "' not found.", NamedTextColor.RED)));
            return;
        }
        ExclusionZoneTier tier;
        try {
            tier = ExclusionZoneTier.valueOf(tierName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Unknown tier '" + tierName + "'. Valid: SAFE, MEDIUM, HARD", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = zoneOpt.get();
        ExclusionZoneTier oldTier = zone.getTier();
        zone.setTier(tier);
        boolean saved = mgr.trySaveZones();
        Map<String, Object> tierRow = zoneRow(zone);
        tierRow.put("old_tier", oldTier == null ? null : oldTier.name());
        tierRow.put("new_tier", tier.name());
        emitZoneChange(sender, "admin.exclusion_tier_changed", AuditRisk.NORMAL, saved, tierRow);
        plugin.refreshPermanentZoneMarkers();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Zone '" + name + "' tier set to ", NamedTextColor.GREEN))
                .append(Component.text(tier.name(), NamedTextColor.YELLOW))
                .append(Component.text(".", NamedTextColor.GREEN)));
    }

    @Execute(name = "exclusion reload")
    @Permission("cosmos.admin")
    public void exclusionReload(@Context CommandSender sender) {
        int zonesBefore = plugin.getPermanentZoneManager().getAllZones().size();
        plugin.getPermanentZoneManager().loadZones();
        // loadZones() reports no result, so this row can only observe the zone counts around the call.
        Map<String, Object> reloadRow = new LinkedHashMap<>();
        reloadRow.put("zones_before", zonesBefore);
        reloadRow.put("zones_after", plugin.getPermanentZoneManager().getAllZones().size());
        MysterriaAuditEmitter.emitAdmin(plugin, "admin.exclusion_reloaded", AuditOutcome.OBSERVED,
                AuditRisk.NORMAL, sender, null, null, reloadRow);
        plugin.refreshPermanentZoneMarkers();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Permanent zones reloaded from file.", NamedTextColor.GREEN)));
    }

    /** Zone-file mutations: COMMITTED when the file was written, FAILED when only memory changed. */
    private void emitZoneChange(CommandSender sender, String event, AuditRisk risk, boolean saved,
                                Map<String, Object> row) {
        row.put("applied_in_memory", true);
        row.put("persisted", saved);
        MysterriaAuditEmitter.emitAdmin(plugin, event,
                saved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, risk, sender, null,
                saved ? null : "zones_persistence_failed", row);
    }

    private static Map<String, Object> zoneRow(PermanentZone zone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("zone_id", zone.getId().toString());
        row.put("zone_name", zone.getName());
        row.put("tier", zone.getTier() == null ? null : zone.getTier().name());
        row.put("vertex_count", zone.getVertices().size());
        return row;
    }

    /** Vertex position, keyed apart from the actor's own world/x/y/z. */
    private static void putVertex(Map<String, Object> row, Location vertex) {
        if (vertex == null || vertex.getWorld() == null) return;
        row.put("vertex_world", vertex.getWorld().getName());
        row.put("vertex_x", vertex.getBlockX());
        row.put("vertex_y", vertex.getBlockY());
        row.put("vertex_z", vertex.getBlockZ());
    }

    private NamedTextColor tierColor(ExclusionZoneTier tier) {
        return switch (tier) {
            case SAFE -> NamedTextColor.GREEN;
            case MEDIUM -> NamedTextColor.YELLOW;
            case HARD -> NamedTextColor.RED;
        };
    }

    @Execute(name = "exclusion balance")
    @Permission("cosmos.admin")
    public void exclusionBalance(@Context CommandSender sender, @Arg String townName) {
        Optional<TownData> townOpt = TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                    .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        int townId = townOpt.get().id();
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(townId);
        sender.sendMessage(Component.text("=== " + townName + " Balance ===").color(NamedTextColor.GOLD));
        for (ResourceType type : ResourceType.values()) {
            double amount = balance.getOrDefault(type, 0.0);
            sender.sendMessage(Component.text("  " + type.name() + ": ", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.1f", amount), NamedTextColor.WHITE)));
        }
    }

}
