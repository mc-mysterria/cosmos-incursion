package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.event.source.EventState;
import net.mysterria.cosmos.item.PaperAngelItem;
import net.mysterria.cosmos.zone.IncursionZone;
import net.mysterria.cosmos.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
            plugin.getConfigManager().reload();
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Configuration reloaded successfully!").color(NamedTextColor.GREEN)));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Failed to reload configuration: " + e.getMessage()).color(NamedTextColor.RED)));
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
            sender.sendMessage(Component.text("- " + incursionZone.getName()).color(NamedTextColor.YELLOW).append(Component.text(" at (" + (int) center.getX() + ", " + (int) center.getY() + ", " + (int) center.getZ() + ")").color(NamedTextColor.WHITE)).append(Component.text(" [Radius: " + (int) incursionZone.getRadius() + "]").color(NamedTextColor.GRAY)).append(Component.text(incursionZone.isActive() ? " [ACTIVE]" : " [INACTIVE]").color(incursionZone.isActive() ? NamedTextColor.GREEN : NamedTextColor.RED)));
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

        IncursionZone incursionZone = new IncursionZone(name, location, radius);
        zoneManager.registerZone(incursionZone);

        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("Zone '" + name + "' created at your location with radius " + (int) radius).color(NamedTextColor.GREEN)));
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
