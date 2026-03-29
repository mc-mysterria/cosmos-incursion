package net.mysterria.cosmos.domain.guide;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.TownsToolkit;
import net.william278.husktowns.town.Town;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-game guide and town balance viewer accessible to all players via /cosmos guide.
 *
 * Structure:
 *   Main menu → Incursion Guide sub-GUI
 *             → Extraction Zone Guide sub-GUI
 *             → Town Resources viewer
 */
public class CosmosGuideGUI {

    private final CosmosIncursion plugin;

    public CosmosGuideGUI(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    // ── Main menu ────────────────────────────────────────────────────────────────

    public void openMainMenu(Player player) {
        Gui gui = Gui.gui()
            .title(Component.text("✦ Cosmos Incursion — Guide", NamedTextColor.GOLD, TextDecoration.BOLD))
            .rows(3)
            .disableAllInteractions()
            .create();

        fillBorder(gui, 3);

        gui.setItem(10, clickItem(
            Material.BEACON,
            Component.text("⚔  Incursion Zones", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                line("Timed server-wide PvP events with", NamedTextColor.GRAY),
                line("Spirit Beacons, zone tiers, and", NamedTextColor.GRAY),
                line("rewards for the winning town.", NamedTextColor.GRAY),
                Component.empty(),
                line("Click to learn more.", NamedTextColor.YELLOW)
            ),
            () -> openIncursionGuide(player)
        ));

        gui.setItem(13, clickItem(
            Material.LODESTONE,
            Component.text("⚗  Extraction Zones", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                line("Permanent PvP zones active at all times.", NamedTextColor.GRAY),
                line("Gather resources, survive, and extract.", NamedTextColor.GRAY),
                Component.empty(),
                line("Click to learn more.", NamedTextColor.YELLOW)
            ),
            () -> openExtractionGuide(player)
        ));

        gui.setItem(16, clickItem(
            Material.GOLD_INGOT,
            Component.text("⚖  Town Resources", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                line("View your town's accumulated resource", NamedTextColor.GRAY),
                line("balance from extraction zones.", NamedTextColor.GRAY),
                Component.empty(),
                line("Click to view.", NamedTextColor.YELLOW)
            ),
            () -> openTownResources(player)
        ));

        gui.open(player);
    }

    // ── Incursion guide ──────────────────────────────────────────────────────────

    private void openIncursionGuide(Player player) {
        Gui gui = Gui.gui()
            .title(Component.text("⚔  Incursion Zones", NamedTextColor.RED, TextDecoration.BOLD))
            .rows(5)
            .disableAllInteractions()
            .create();

        fillBorder(gui, 5);

        // Overview
        gui.setItem(4, infoItem(
            Material.BOOK,
            Component.text("What is an Incursion?", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(
                line("Incursions are timed server-wide PvP events.", NamedTextColor.GRAY),
                line("When triggered, several zones appear across", NamedTextColor.GRAY),
                line("the world, marked by colored particle walls.", NamedTextColor.GRAY),
                Component.empty(),
                line("Objective:", NamedTextColor.WHITE),
                line("  Spirit Beacons spawn inside each zone.", NamedTextColor.GRAY),
                line("  Stand near a beacon to capture it for", NamedTextColor.GRAY),
                line("  your town. The more you hold at event", NamedTextColor.GRAY),
                line("  end, the more likely your town wins.", NamedTextColor.GRAY),
                Component.empty(),
                line("Before entering a zone you will be shown", NamedTextColor.GRAY),
                line("a consent GUI explaining the risks.", NamedTextColor.GRAY)
            )
        ));

        // GREEN tier
        gui.setItem(19, infoItem(
            Material.LIME_WOOL,
            Component.text("GREEN Zone", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(
                line("The safest incursion tier.", NamedTextColor.GRAY),
                Component.empty(),
                line("✔  No items drop on death", NamedTextColor.GREEN),
                line("✔  No sequence regression", NamedTextColor.GREEN),
                line("✗  Low rewards", NamedTextColor.YELLOW)
            )
        ));

        // YELLOW tier
        gui.setItem(21, infoItem(
            Material.YELLOW_WOOL,
            Component.text("YELLOW Zone", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(
                line("Moderate risk, moderate reward.", NamedTextColor.GRAY),
                Component.empty(),
                line("⚠  ~33% of items may drop on death", NamedTextColor.YELLOW),
                line("✔  No sequence regression", NamedTextColor.GREEN),
                line("✗  Medium rewards", NamedTextColor.YELLOW)
            )
        ));

        // RED tier
        gui.setItem(23, infoItem(
            Material.RED_WOOL,
            Component.text("RED Zone", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                line("High risk, high reward.", NamedTextColor.GRAY),
                Component.empty(),
                line("✗  ALL items drop on death", NamedTextColor.RED),
                line("✔  No sequence regression", NamedTextColor.GREEN),
                line("✗  High rewards", NamedTextColor.GOLD)
            )
        ));

        // DEATH tier
        gui.setItem(25, infoItem(
            Material.BLACK_WOOL,
            Component.text("DEATH Zone", NamedTextColor.DARK_RED, TextDecoration.BOLD),
            List.of(
                line("Extreme risk. Enter at your own peril.", NamedTextColor.DARK_GRAY),
                Component.empty(),
                line("✗  ALL items drop on death", NamedTextColor.RED),
                line("✗  Sequence 6+ players may REGRESS", NamedTextColor.DARK_RED),
                line("   on death (lose one sequence level)", NamedTextColor.DARK_RED),
                line("✗  Best rewards in the game", NamedTextColor.GOLD),
                Component.empty(),
                line("Spirit Weight:", NamedTextColor.DARK_GRAY),
                line("  High-sequence players suffer a passive", NamedTextColor.DARK_GRAY),
                line("  damage-over-time effect inside.", NamedTextColor.DARK_GRAY)
            )
        ));

        // Victory buff
        gui.setItem(31, infoItem(
            Material.TOTEM_OF_UNDYING,
            Component.text("★  Victory Buff", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                line("Awarded to the winning town at event end.", NamedTextColor.GRAY),
                Component.empty(),
                line("Reward:", NamedTextColor.WHITE),
                line("  Acting Speed +10% for all town members", NamedTextColor.GOLD),
                line("  Duration: 24 hours", NamedTextColor.YELLOW),
                Component.empty(),
                line("Only one town holds the buff at a time.", NamedTextColor.GRAY),
                line("Winning again refreshes the duration.", NamedTextColor.GRAY)
            )
        ));

        // Paper Angel
        gui.setItem(33, infoItem(
            Material.FEATHER,
            Component.text("Paper Angel", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(
                line("A rare protection item obtainable from", NamedTextColor.GRAY),
                line("events or admins.", NamedTextColor.GRAY),
                Component.empty(),
                line("Right-click to arm it.", NamedTextColor.GRAY),
                line("When armed, it absorbs one death penalty", NamedTextColor.GRAY),
                line("(preventing sequence regression).", NamedTextColor.GRAY),
                Component.empty(),
                line("Single use — consumed on activation.", NamedTextColor.YELLOW)
            )
        ));

        gui.setItem(36, backItem(() -> openMainMenu(player)));
        gui.open(player);
    }

    // ── Extraction zone guide ─────────────────────────────────────────────────────

    private void openExtractionGuide(Player player) {
        Gui gui = Gui.gui()
            .title(Component.text("⚗  Extraction Zones", NamedTextColor.AQUA, TextDecoration.BOLD))
            .rows(5)
            .disableAllInteractions()
            .create();

        fillBorder(gui, 5);

        // Overview
        gui.setItem(4, infoItem(
            Material.LODESTONE,
            Component.text("What is an Extraction Zone?", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(
                line("Permanent PvP zones always active on the map,", NamedTextColor.GRAY),
                line("marked by dark red particle walls along their", NamedTextColor.GRAY),
                line("polygon boundary.", NamedTextColor.GRAY),
                Component.empty(),
                line("The loop:", NamedTextColor.WHITE),
                line("  Enter → gather resources at PoIs →", NamedTextColor.GRAY),
                line("  reach an extraction point → extract →", NamedTextColor.GRAY),
                line("  get teleported out with resources deposited", NamedTextColor.GRAY),
                line("  to your town's balance.", NamedTextColor.GRAY),
                Component.empty(),
                line("PvP is always enabled inside.", NamedTextColor.RED),
                line("Other players will try to kill you before", NamedTextColor.RED),
                line("you can extract!", NamedTextColor.RED)
            )
        ));

        // Points of Interest
        gui.setItem(19, infoItem(
            Material.ENDER_EYE,
            Component.text("Points of Interest (PoIs)", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                line("Glowing beacons scattered inside the zone.", NamedTextColor.GRAY),
                Component.empty(),
                line("Visuals:", NamedTextColor.WHITE),
                line("  Vertical colored particle beam", NamedTextColor.GRAY),
                line("  Rotating resource item floating above", NamedTextColor.GRAY),
                line("  Ring of particles at ground level", NamedTextColor.GRAY),
                Component.empty(),
                line("Beam color = resource type:", NamedTextColor.WHITE),
                Component.text("  Gold beam", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false)
                    .append(line(" → Gold resource", NamedTextColor.GRAY)),
                Component.text("  White beam", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                    .append(line(" → Silver resource", NamedTextColor.GRAY)),
                Component.text("  Green beam", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                    .append(line(" → Gems resource", NamedTextColor.GRAY)),
                Component.empty(),
                line("PoIs rotate every ~5 minutes.", NamedTextColor.DARK_GRAY)
            )
        ));

        // Resources
        gui.setItem(22, infoItem(
            Material.GOLD_INGOT,
            Component.text("Resources", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                line("Stand near a PoI to fill your buffer:", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("  Gold Ingot  ", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                    .append(line("→ 1.0 / second", NamedTextColor.GRAY)),
                Component.text("  Iron Ingot  ", NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                    .append(line("→ 2.0 / second  (Silver)", NamedTextColor.GRAY)),
                Component.text("  Emerald     ", NamedTextColor.GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
                    .append(line("→ 0.5 / second  (Gems)", NamedTextColor.GRAY)),
                Component.empty(),
                line("Your buffer is lost entirely if you die!", NamedTextColor.RED),
                line("Resources are only safe once extracted.", NamedTextColor.YELLOW)
            )
        ));

        // Extraction points
        gui.setItem(21, infoItem(
            Material.COMPASS,
            Component.text("Extraction Points", NamedTextColor.AQUA, TextDecoration.BOLD),
            List.of(
                line("Teal beacons located near zone boundaries.", NamedTextColor.GRAY),
                line("Rotate every ~3 minutes.", NamedTextColor.GRAY),
                Component.empty(),
                line("How to extract:", NamedTextColor.WHITE),
                line("  1. Walk into the teal particle ring", NamedTextColor.GRAY),
                line("  2. Stand still — a channel begins", NamedTextColor.GRAY),
                line("  3. Watch the progress bar (≈10 seconds)", NamedTextColor.GRAY),
                line("  4. All resources deposit to town balance", NamedTextColor.GRAY),
                line("  5. You are teleported safely outside", NamedTextColor.GRAY),
                Component.empty(),
                line("Moving out of the ring cancels the channel.", NamedTextColor.RED),
                line("Taking damage does NOT cancel it.", NamedTextColor.YELLOW)
            )
        ));

        // Exit enforcement
        gui.setItem(23, infoItem(
            Material.BARRIER,
            Component.text("⚠  You Cannot Simply Leave", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                line("While carrying unextracted resources,", NamedTextColor.GRAY),
                line("ALL exits are blocked:", NamedTextColor.GRAY),
                Component.empty(),
                line("✗  Walking out", NamedTextColor.RED),
                line("✗  /tp and /spawn commands", NamedTextColor.RED),
                line("✗  Ender pearls", NamedTextColor.RED),
                line("✗  Chorus fruit", NamedTextColor.RED),
                line("✗  Nether / End portals", NamedTextColor.RED),
                line("✗  Plugin teleports", NamedTextColor.RED),
                Component.empty(),
                line("The ONLY way out is the extraction point.", NamedTextColor.YELLOW),
                line("Or die and lose everything.", NamedTextColor.DARK_GRAY)
            )
        ));

        // Death penalty
        gui.setItem(31, infoItem(
            Material.SKELETON_SKULL,
            Component.text("Dying Inside", NamedTextColor.DARK_RED, TextDecoration.BOLD),
            List.of(
                line("Death inside an extraction zone:", NamedTextColor.GRAY),
                Component.empty(),
                line("✗  Your entire resource buffer is lost", NamedTextColor.RED),
                line("✗  Normal item drop rules apply (PvP)", NamedTextColor.RED),
                Component.empty(),
                line("After death, your buffer is cleared and", NamedTextColor.GRAY),
                line("you may leave the zone freely.", NamedTextColor.GRAY)
            )
        ));

        gui.setItem(36, backItem(() -> openMainMenu(player)));
        gui.open(player);
    }

    // ── Town resources ────────────────────────────────────────────────────────────

    public void openTownResources(Player player) {
        Optional<Town> townOpt = TownsToolkit.getPlayerTown(player);

        if (townOpt.isEmpty()) {
            Gui gui = Gui.gui()
                .title(Component.text("Town Resources", NamedTextColor.GOLD, TextDecoration.BOLD))
                .rows(3)
                .disableAllInteractions()
                .create();
            fillBorder(gui, 3);
            gui.setItem(13, infoItem(
                Material.BARRIER,
                Component.text("No Town Found", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(line("You are not a member of any town.", NamedTextColor.GRAY))
            ));
            gui.setItem(22, backItem(() -> openMainMenu(player)));
            gui.open(player);
            return;
        }

        Town town = townOpt.get();
        Map<ResourceType, Double> balance = plugin.getPermanentZoneManager().getTownBalance(town.getId());

        double gold   = balance.getOrDefault(ResourceType.GOLD,   0.0);
        double silver = balance.getOrDefault(ResourceType.SILVER, 0.0);
        double gems   = balance.getOrDefault(ResourceType.GEMS,   0.0);

        Gui gui = Gui.gui()
            .title(Component.text("⚖  " + town.getName(), NamedTextColor.GOLD, TextDecoration.BOLD))
            .rows(3)
            .disableAllInteractions()
            .create();

        fillBorder(gui, 3);

        gui.setItem(10, infoItem(
            Material.GOLD_INGOT,
            Component.text("Gold", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                line("Accumulated from extraction zones.", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Balance: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.1f", gold), NamedTextColor.GOLD, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false))
            )
        ));

        gui.setItem(13, infoItem(
            Material.IRON_INGOT,
            Component.text("Silver", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(
                line("Accumulated from extraction zones.", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Balance: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.1f", silver), NamedTextColor.WHITE, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false))
            )
        ));

        gui.setItem(16, infoItem(
            Material.EMERALD,
            Component.text("Gems", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(
                line("Accumulated from extraction zones.", NamedTextColor.GRAY),
                Component.empty(),
                Component.text("Balance: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(String.format("%.1f", gems), NamedTextColor.GREEN, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false))
            )
        ));

        gui.setItem(22, backItem(() -> openMainMenu(player)));
        gui.open(player);
    }

    // ── Item builders ─────────────────────────────────────────────────────────────

    private GuiItem clickItem(Material mat, Component name, List<Component> lore, Runnable onClick) {
        return new GuiItem(buildStack(mat, name, lore), event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    private GuiItem infoItem(Material mat, Component name, List<Component> lore) {
        return new GuiItem(buildStack(mat, name, lore), event -> event.setCancelled(true));
    }

    private GuiItem backItem(Runnable onClick) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("← Back", NamedTextColor.GRAY, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    private ItemStack buildStack(Material mat, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillBorder(Gui gui, int rows) {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            glass.setItemMeta(meta);
        }
        GuiItem filler = new GuiItem(glass, event -> event.setCancelled(true));

        for (int i = 0; i < 9; i++) gui.setItem(i, filler);
        for (int i = (rows - 1) * 9; i < rows * 9; i++) gui.setItem(i, filler);
        for (int row = 1; row < rows - 1; row++) {
            gui.setItem(row * 9,     filler);
            gui.setItem(row * 9 + 8, filler);
        }
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
}
