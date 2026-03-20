package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.event.source.EventState;
import net.mysterria.cosmos.domain.item.PaperAngelItem;
import net.mysterria.cosmos.domain.permanent.PermanentZone;
import net.mysterria.cosmos.domain.permanent.PermanentZoneManager;
import net.mysterria.cosmos.domain.permanent.ResourceType;
import net.mysterria.cosmos.domain.zone.IncursionZone;
import net.mysterria.cosmos.domain.zone.ZoneManager;
import net.mysterria.cosmos.domain.zone.ZoneTier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Command(name = "cosmos")
public class CosmosCommand {

    private final CosmosIncursion plugin;

    public CosmosCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Execute
    public void help(@Context CommandSender sender) {
        sender.sendMessage(Component.text("=== Cosmos Incursion ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/cosmos status").color(NamedTextColor.YELLOW).append(Component.text(" - Show current event status").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin reload").color(NamedTextColor.YELLOW).append(Component.text(" - Reload configuration").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin start").color(NamedTextColor.YELLOW).append(Component.text(" - Force start event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin stop").color(NamedTextColor.YELLOW).append(Component.text(" - Force stop event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin give paperangel <player> [amount]").color(NamedTextColor.YELLOW).append(Component.text(" - Give Paper Angel items").color(NamedTextColor.WHITE)));
    }

    @Execute(name = "status")
    public void status(@Context CommandSender sender) {
        var eventManager = plugin.getEventManager();
        if (eventManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("EventManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        var state = eventManager.getState();
        var activeEvent = eventManager.getActiveEvent();

        sender.sendMessage(Component.text("=== Cosmos Incursion Status ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("State: ").color(NamedTextColor.YELLOW).append(Component.text(state.name()).color(getStateColor(state))));

        if (state == EventState.IDLE) {
            long cooldownSeconds = eventManager.getRemainingCooldownSeconds();
            if (cooldownSeconds > 0) {
                long minutes = cooldownSeconds / 60;
                sender.sendMessage(Component.text("Cooldown: ").color(NamedTextColor.YELLOW).append(Component.text(minutes + " minutes remaining").color(NamedTextColor.WHITE)));
            } else {
                sender.sendMessage(Component.text("Status: ").color(NamedTextColor.YELLOW).append(Component.text("Ready to start").color(NamedTextColor.GREEN)));
            }
        } else if (activeEvent != null) {
            if (state == EventState.STARTING) {
                sender.sendMessage(Component.text("Countdown: ").color(NamedTextColor.YELLOW).append(Component.text(activeEvent.getCountdownRemaining() + " seconds").color(NamedTextColor.WHITE)));
            } else if (state == EventState.ACTIVE) {
                long remainingSeconds = activeEvent.getRemainingTime() / 1000;
                long minutes = remainingSeconds / 60;
                sender.sendMessage(Component.text("Time Remaining: ").color(NamedTextColor.YELLOW).append(Component.text(minutes + " minutes").color(NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Active Zones: ").color(NamedTextColor.YELLOW).append(Component.text(String.valueOf(activeEvent.getIncursionZones().size())).color(NamedTextColor.WHITE)));
            }
        }
    }

    // ── Permanent Zone Commands ──────────────────────────────────────────────────

    @Execute(name = "pzone list")
    @Permission("cosmos.admin")
    public void pzoneList(@Context CommandSender sender) {
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
                ? "(" + (int)centroid.getX() + ", " + (int)centroid.getZ() + ")"
                : "(no vertices)";
            sender.sendMessage(Component.text("- " + zone.getName(), NamedTextColor.YELLOW)
                .append(Component.text(" centroid=" + pos, NamedTextColor.WHITE))
                .append(Component.text(" verts=" + zone.getVertices().size(), NamedTextColor.GRAY))
                .append(Component.text(" PoIs=" + mgr.getActivePoIs(zone).size() + " EPs=" + mgr.getActiveExtractionPoints(zone).size(), NamedTextColor.AQUA)));
        }
    }

    @Execute(name = "pzone add")
    @Permission("cosmos.admin")
    public void pzoneAdd(@Context CommandSender sender, @Arg String name) {
        PermanentZoneManager mgr = plugin.getPermanentZoneManager();
        if (mgr.getZone(name).isPresent()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Zone '" + name + "' already exists. Use /cosmos pzone vertex add " + name + " to add vertices.", NamedTextColor.RED)));
            return;
        }
        PermanentZone zone = new PermanentZone(name, new java.util.ArrayList<>());
        mgr.addZone(zone);
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
            .append(Component.text("Permanent zone '" + name + "' created. Add vertices with /cosmos pzone vertex add " + name + ".", NamedTextColor.GREEN)));
    }

    @Execute(name = "pzone vertex add")
    @Permission("cosmos.admin")
    public void pzoneVertexAdd(@Context CommandSender sender, @Arg String name) {
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
            .append(Component.text("(" + (int)player.getX() + ", " + (int)player.getZ() + ")", NamedTextColor.GRAY)));
        if (count >= 3) {
            // Spawn PoIs once the polygon is valid
            mgr.spawnPoIsForZone(zone);
            mgr.spawnExtractionPoints(zone);
        }
    }

    @Execute(name = "pzone vertex remove")
    @Permission("cosmos.admin")
    public void pzoneVertexRemove(@Context CommandSender sender, @Arg String name, @Arg int index) {
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

    @Execute(name = "pzone vertex list")
    @Permission("cosmos.admin")
    public void pzoneVertexList(@Context CommandSender sender, @Arg String name) {
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
                .append(Component.text("(" + (int)v.getX() + ", " + (int)v.getY() + ", " + (int)v.getZ() + ")", NamedTextColor.WHITE)));
        }
        if (verts.size() < 3) {
            sender.sendMessage(Component.text("  [Need at least 3 vertices for a valid polygon]", NamedTextColor.RED));
        }
    }

    @Execute(name = "pzone remove")
    @Permission("cosmos.admin")
    public void pzoneRemove(@Context CommandSender sender, @Arg String name) {
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

    @Execute(name = "pzone tp")
    @Permission("cosmos.admin")
    public void pzoneTp(@Context CommandSender sender, @Arg String name) {
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

    @Execute(name = "pzone info")
    @Permission("cosmos.admin")
    public void pzoneInfo(@Context CommandSender sender, @Arg String name) {
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

    @Execute(name = "pzone reload")
    @Permission("cosmos.admin")
    public void pzoneReload(@Context CommandSender sender) {
        plugin.getPermanentZoneManager().loadZones();
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
            .append(Component.text("Permanent zones reloaded from file.", NamedTextColor.GREEN)));
    }

    @Execute(name = "pzone balance")
    @Permission("cosmos.admin")
    public void pzoneBalance(@Context CommandSender sender, @Arg String townName) {
        Optional<net.william278.husktowns.town.Town> townOpt = net.mysterria.cosmos.toolkit.TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        int townId = townOpt.get().getId();
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(townId);
        sender.sendMessage(Component.text("=== " + townName + " Balance ===").color(NamedTextColor.GOLD));
        for (ResourceType type : ResourceType.values()) {
            double amount = balance.getOrDefault(type, 0.0);
            sender.sendMessage(Component.text("  " + type.name() + ": ", NamedTextColor.YELLOW)
                .append(Component.text(String.format("%.1f", amount), NamedTextColor.WHITE)));
        }
    }

    private NamedTextColor getStateColor(EventState state) {
        return switch (state) {
            case IDLE -> NamedTextColor.GRAY;
            case STARTING -> NamedTextColor.YELLOW;
            case ACTIVE -> NamedTextColor.GREEN;
            case ENDING -> NamedTextColor.RED;
        };
    }

    @Execute(name = "admin reload")
    @Permission("cosmos.admin")
    public void reload(@Context CommandSender sender) {
        try {
            plugin.reloadPlugin();
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Plugin reloaded successfully!").color(NamedTextColor.GREEN)));
            sender.sendMessage(Component.text("  ").append(Component.text("✓ Configuration refreshed").color(NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("  ").append(Component.text("✓ Config-dependent tasks restarted").color(NamedTextColor.GRAY)));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Failed to reload: " + e.getMessage()).color(NamedTextColor.RED)));
            e.printStackTrace();
        }
    }

    @Execute(name = "admin start")
    @Permission("cosmos.admin")
    public void start(@Context CommandSender sender) {
        var eventManager = plugin.getEventManager();
        if (eventManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("EventManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        if (eventManager.startEvent(true)) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Event force-started successfully").color(NamedTextColor.GREEN)));
        } else {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Cannot start event - event already running").color(NamedTextColor.RED)));
        }
    }

    @Execute(name = "admin stop")
    @Permission("cosmos.admin")
    public void stop(@Context CommandSender sender) {
        var eventManager = plugin.getEventManager();
        if (eventManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("EventManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        if (eventManager.forceStop()) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Event stopped successfully").color(NamedTextColor.GREEN)));
        } else {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("No event is currently active").color(NamedTextColor.RED)));
        }
    }

    @Execute(name = "admin zone list")
    @Permission("cosmos.admin")
    public void zoneList(@Context CommandSender sender) {
        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("ZoneManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        var zones = zoneManager.getAllZones();
        if (zones.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("No zones registered").color(NamedTextColor.YELLOW)));
            return;
        }

        sender.sendMessage(Component.text("=== Registered Zones ===").color(NamedTextColor.GOLD));
        for (IncursionZone incursionZone : zones) {
            Location center = incursionZone.getCenter();
            NamedTextColor tierColor = switch (incursionZone.getTier()) {
                case GREEN  -> NamedTextColor.GREEN;
                case YELLOW -> NamedTextColor.YELLOW;
                case RED    -> NamedTextColor.RED;
                case DEATH  -> NamedTextColor.DARK_RED;
            };
            sender.sendMessage(
                Component.text("- " + incursionZone.getName()).color(NamedTextColor.YELLOW)
                    .append(Component.text(" [" + incursionZone.getTier() + "]").color(tierColor))
                    .append(Component.text(" at (" + (int) center.getX() + ", " + (int) center.getY() + ", " + (int) center.getZ() + ")").color(NamedTextColor.WHITE))
                    .append(Component.text(" [Radius: " + (int) incursionZone.getRadius() + "]").color(NamedTextColor.GRAY))
                    .append(Component.text(incursionZone.isActive() ? " [ACTIVE]" : " [INACTIVE]").color(incursionZone.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED))
            );
        }
    }

    @Execute(name = "admin zone add")
    @Permission("cosmos.admin")
    public void zoneAdd(@Context CommandSender sender, @Arg String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Only players can use this command").color(NamedTextColor.RED)));
            return;
        }

        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("ZoneManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        double radius = plugin.getConfigManager().getConfig().getZoneRadius();
        Location location = player.getLocation();

        // Admin-placed zones default to GREEN tier (safest)
        IncursionZone incursionZone = new IncursionZone(name, location, radius, ZoneTier.GREEN);
        zoneManager.registerZone(incursionZone);

        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
            .append(Component.text("Zone '" + name + "' [GREEN] created at your location (use /cosmos admin zone tier to change)").color(NamedTextColor.GREEN)));
    }

    @Execute(name = "admin zone remove")
    @Permission("cosmos.admin")
    public void zoneRemove(@Context CommandSender sender, @Arg String name) {
        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("ZoneManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        Optional<IncursionZone> zone = zoneManager.getZone(name);
        if (zone.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Zone '" + name + "' not found").color(NamedTextColor.RED)));
            return;
        }

        zoneManager.unregisterZone(zone.get().getId());
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Zone '" + name + "' removed").color(NamedTextColor.GREEN)));
    }

    @Execute(name = "admin zone tp")
    @Permission("cosmos.admin")
    public void zoneTp(@Context CommandSender sender, @Arg String name) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Only players can use this command").color(NamedTextColor.RED)));
            return;
        }

        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("ZoneManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        Optional<IncursionZone> zone = zoneManager.getZone(name);
        if (zone.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Zone '" + name + "' not found").color(NamedTextColor.RED)));
            return;
        }

        player.teleport(zone.get().getCenter());
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Teleported to zone '" + name + "'").color(NamedTextColor.GREEN)));
    }

    @Execute(name = "admin zone tier")
    @Permission("cosmos.admin")
    public void zoneTier(@Context CommandSender sender, @Arg String name, @Arg String tierName) {
        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("ZoneManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        Optional<IncursionZone> zone = zoneManager.getZone(name);
        if (zone.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Zone '" + name + "' not found").color(NamedTextColor.RED)));
            return;
        }

        ZoneTier tier;
        try {
            tier = ZoneTier.valueOf(tierName.toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("Invalid tier. Use: green, yellow, red, death").color(NamedTextColor.RED)));
            return;
        }

        zone.get().setTier(tier);
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
            .append(Component.text("Zone '" + name + "' tier set to " + tier.name()).color(NamedTextColor.GREEN)));
    }

    @Execute(name = "admin give paperangel")
    @Permission("cosmos.admin")
    public void givePaperAngel(@Context CommandSender sender, @Arg String playerName, @Arg(value = "1") int amount) {
        // Validate amount
        if (amount < 1 || amount > 64) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Amount must be between 1 and 64").color(NamedTextColor.RED)));
            return;
        }

        // Find target player
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Player '" + playerName + "' not found").color(NamedTextColor.RED)));
            return;
        }

        // Create Paper Angel item
        ItemStack paperAngel = PaperAngelItem.create(plugin.getKey("paper_angel_item"), amount);

        // Give to player
        var leftover = target.getInventory().addItem(paperAngel);

        if (leftover.isEmpty()) {
            // All items fit in inventory
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Gave " + amount + " Paper Angel(s) to " + target.getName()).color(NamedTextColor.GREEN)));
            target.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("You received " + amount + " Paper Angel(s)!").color(NamedTextColor.GREEN)));
        } else {
            // Some items didn't fit
            int given = amount - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Gave " + given + " Paper Angel(s) to " + target.getName() + " (inventory full)").color(NamedTextColor.YELLOW)));
            target.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("You received " + given + " Paper Angel(s)! (inventory full)").color(NamedTextColor.YELLOW)));
        }
    }

}
