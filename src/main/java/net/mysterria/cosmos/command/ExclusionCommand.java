package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;

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
        mgr.addZone(zone);
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
        mgr.saveZones();
        int count = zone.getVertices().size();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Vertex #" + count + " added to '" + name + "'. ", NamedTextColor.GREEN))
                .append(Component.text("(" + (int) player.getX() + ", " + (int) player.getZ() + ")", NamedTextColor.GRAY)));
        if (count >= 3) {
            // Spawn PoIs once the polygon is valid
            mgr.spawnPoIsForZone(zone);
            mgr.spawnExtractionPoints(zone);
        }
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
        zone.removeVertex(index - 1); // 1-based → 0-based
        mgr.saveZones();
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
        mgr.removeZone(zone.get().getId());
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
        sender.sendMessage(Component.text("Active PoIs: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(mgr.getActivePoIs(zone).size()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Active Extraction Points: ", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(mgr.getActiveExtractionPoints(zone).size()), NamedTextColor.WHITE)));
        mgr.getActivePoIs(zone).forEach(poi ->
                sender.sendMessage(Component.text("  PoI: ", NamedTextColor.GRAY)
                        .append(Component.text(poi.getResourceType().name(), NamedTextColor.AQUA))
                        .append(Component.text(" expires in " + ((poi.getActiveUntil() - System.currentTimeMillis()) / 1000) + "s", NamedTextColor.DARK_GRAY))));
    }

    @Execute(name = "exclusion reload")
    @Permission("cosmos.admin")
    public void exclusionReload(@Context CommandSender sender) {
        plugin.getPermanentZoneManager().loadZones();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Permanent zones reloaded from file.", NamedTextColor.GREEN)));
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
