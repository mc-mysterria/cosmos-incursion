package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.argument.Arg;
import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
import net.mysterria.cosmos.toolkit.CoiItemResolver;
import net.mysterria.cosmos.toolkit.item.PaperAngelToolkit;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.domain.incursion.service.ZoneManager;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Command(name = "cosmos")
public class AdminCommand {

    private final CosmosIncursion plugin;

    public AdminCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
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

        double radius = plugin.getConfigLoader().getConfig().getZoneRadius();
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

    // ── Balance admin commands ───────────────────────────────────────────────────

    @Execute(name = "admin balance set")
    @Permission("cosmos.admin")
    public void balanceSet(@Context CommandSender sender,
            @Arg String townName, @Arg String resourceName, @Arg double amount) {
        ResourceType type = parseResourceType(sender, resourceName);
        if (type == null) return;
        Optional<TownData> townOpt = TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        TownData town = townOpt.get();
        plugin.getPermanentZoneManager().setTownBalance(town.id(), type, amount);
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
            .append(Component.text("Set " + town.name() + "'s " + type.displayName()
                + " balance to " + String.format("%.1f", amount) + ".", NamedTextColor.GREEN)));
    }

    @Execute(name = "admin balance add")
    @Permission("cosmos.admin")
    public void balanceAdd(@Context CommandSender sender,
            @Arg String townName, @Arg String resourceName, @Arg double amount) {
        ResourceType type = parseResourceType(sender, resourceName);
        if (type == null) return;
        Optional<TownData> townOpt = TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        TownData town = townOpt.get();
        plugin.getPermanentZoneManager().adjustTownBalance(town.id(), type, amount);
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(town.id());
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
            .append(Component.text("Added " + String.format("%.1f", amount) + " " + type.displayName()
                + " to " + town.name() + ". New balance: "
                + String.format("%.1f", balance.getOrDefault(type, 0.0)) + ".", NamedTextColor.GREEN)));
    }

    @Execute(name = "admin balance remove")
    @Permission("cosmos.admin")
    public void balanceRemove(@Context CommandSender sender,
            @Arg String townName, @Arg String resourceName, @Arg double amount) {
        ResourceType type = parseResourceType(sender, resourceName);
        if (type == null) return;
        Optional<TownData> townOpt = TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        TownData town = townOpt.get();
        plugin.getPermanentZoneManager().adjustTownBalance(town.id(), type, -amount);
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(town.id());
        sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
            .append(Component.text("Removed " + String.format("%.1f", amount) + " " + type.displayName()
                + " from " + town.name() + ". New balance: "
                + String.format("%.1f", balance.getOrDefault(type, 0.0)) + ".", NamedTextColor.GREEN)));
    }

    @Execute(name = "admin balance view")
    @Permission("cosmos.admin")
    public void balanceView(@Context CommandSender sender, @Arg String townName) {
        Optional<TownData> townOpt = TownsToolkit.getTown(townName);
        if (townOpt.isEmpty()) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Town '" + townName + "' not found.", NamedTextColor.RED)));
            return;
        }
        TownData town = townOpt.get();
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(town.id());
        sender.sendMessage(Component.text("=== " + town.name() + " Balance ===").color(NamedTextColor.GOLD));
        for (ResourceType rt : ResourceType.values()) {
            NamedTextColor col = switch (rt) {
                case GOLD -> NamedTextColor.GOLD;
                case SILVER -> NamedTextColor.WHITE;
                case GEMS -> NamedTextColor.GREEN;
            };
            sender.sendMessage(Component.text("  " + rt.displayName() + ": ", col)
                .append(Component.text(String.format("%.1f", balance.getOrDefault(rt, 0.0)), NamedTextColor.WHITE)));
        }
    }

    // ── Shop admin commands ──────────────────────────────────────────────────────

    @Execute(name = "admin shop")
    @Permission("cosmos.admin")
    public void adminShop(@Context CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Only players can open the shop editor.", NamedTextColor.RED)));
            return;
        }
        plugin.getZoneShopAdminGUI().open(player);
    }

    /**
     * Adds a COI item directly to the shop with no initial price.
     * Use the shop editor GUI afterward to set prices.
     *
     * <p>Examples:
     * <pre>
     *   /cosmos admin shop addcoi char-fool-9
     *   /cosmos admin shop addcoi ingredients-hermit-8
     *   /cosmos admin shop addcoi main-ingredients-hermit-8
     * </pre>
     */
    @Execute(name = "admin shop addcoi")
    @Permission("cosmos.admin")
    public void adminShopAddCoi(@Context CommandSender sender, @Arg String coiId) {
        // Validate by attempting resolution
        boolean valid;
        if (CoiItemResolver.isMultiItem(coiId)) {
            valid = !CoiItemResolver.resolveItems(coiId).isEmpty();
        } else {
            valid = CoiItemResolver.resolveItem(coiId) != null;
        }

        if (!valid) {
            sender.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text("Unknown COI item ID: ", NamedTextColor.RED))
                .append(Component.text(coiId, NamedTextColor.YELLOW))
                .append(Component.text(". Check pathway name and sequence (0-9).", NamedTextColor.RED)));
            return;
        }

        ShopItem item = new ShopItem(UUID.randomUUID(), coiId, new EnumMap<>(ResourceType.class));
        plugin.getZoneShopManager().addItem(item);
        plugin.getZoneShopManager().save();

        sender.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
            .append(Component.text("Added COI item ", NamedTextColor.GREEN))
            .append(Component.text(coiId, NamedTextColor.AQUA))
            .append(Component.text(". Open /cosmos admin shop to set prices.", NamedTextColor.GREEN)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private ResourceType parseResourceType(CommandSender sender, String input) {
        ResourceType type = switch (input.toLowerCase()) {
            case "iron", "silver" -> ResourceType.SILVER;
            case "gold" -> ResourceType.GOLD;
            case "gems", "gem", "emerald" -> ResourceType.GEMS;
            default -> null;
        };
        if (type == null) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Unknown resource type '" + input + "'. Use: iron, gold, gems", NamedTextColor.RED)));
        }
        return type;
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
        ItemStack paperAngel = PaperAngelToolkit.create(plugin.getKey("paper_angel_item"), amount);

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
