package net.mysterria.cosmos.domain.market.gui;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.toolkit.CoiItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Three-level GUI for browsing and selecting COI items to add to the Zone Shop.
 *
 * <p>Level 1 — Category (Char / Potion / Recipe / Ingredients / Main / Supplementary)<br>
 * Level 2 — Pathway selection (21 pathways, resolved from COI API)<br>
 * Level 3 — Sequence selection (9 → 1), shows actual COI items
 */
public class CoiCatalogGUI {

    private static final List<String> PATHWAYS = List.of(
        "emperor", "abyss", "door", "sun", "tyrant", "giant", "fool", "priest",
        "demoness", "error", "visionary", "fortune", "hanged", "hermit", "darkness",
        "death", "paragon", "moon", "justiciar", "chained", "mother"
    );

    private enum Category {
        CHAR("Chars", Material.SKELETON_SKULL, NamedTextColor.YELLOW),
        POTION("Potions", Material.POTION, NamedTextColor.LIGHT_PURPLE),
        RECIPE("Recipes", Material.BOOK, NamedTextColor.AQUA),
        INGREDIENTS_ALL("All Ingredients", Material.CHEST, NamedTextColor.GREEN),
        INGREDIENTS_MAIN("Main Ingredients", Material.BARREL, NamedTextColor.GREEN),
        INGREDIENTS_SUPPLEMENTARY("Supplementary Ingredients", Material.SHULKER_BOX, NamedTextColor.DARK_GREEN);

        final String label;
        final Material icon;
        final NamedTextColor color;

        Category(String label, Material icon, NamedTextColor color) {
            this.label = label;
            this.icon  = icon;
            this.color = color;
        }

        String coiIdFor(String pathway, int sequence) {
            return switch (this) {
                case CHAR                    -> "char-"                    + pathway + "-" + sequence;
                case POTION                  -> "potion-"                  + pathway + "-" + sequence;
                case RECIPE                  -> "recipe-"                  + pathway + "-" + sequence;
                case INGREDIENTS_ALL         -> "ingredients-"              + pathway + "-" + sequence;
                case INGREDIENTS_MAIN        -> "main-ingredients-"         + pathway + "-" + sequence;
                case INGREDIENTS_SUPPLEMENTARY -> "supplementary-ingredients-" + pathway + "-" + sequence;
            };
        }
    }

    private final Consumer<String> onSelect;
    private final Runnable onBack;

    public CoiCatalogGUI(Consumer<String> onSelect, Runnable onBack) {
        this.onSelect = onSelect;
        this.onBack   = onBack;
    }

    // ── Level 1: Category ───────────────────────────────────────────────────────

    public void openMain(Player player) {
        Gui gui = Gui.gui()
            .title(Component.text("COI Item Catalog", NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .rows(6)
            .disableAllInteractions()
            .create();

        fillGlass(gui);

        // Row 2 (slots 18-26) — first three categories centred at 20, 22, 24
        gui.setItem(20, categoryButton(player, Category.CHAR));
        gui.setItem(22, categoryButton(player, Category.POTION));
        gui.setItem(24, categoryButton(player, Category.RECIPE));

        // Row 3 (slots 27-35) — ingredient categories centred at 29, 31, 33
        gui.setItem(29, categoryButton(player, Category.INGREDIENTS_ALL));
        gui.setItem(31, categoryButton(player, Category.INGREDIENTS_MAIN));
        gui.setItem(33, categoryButton(player, Category.INGREDIENTS_SUPPLEMENTARY));

        gui.setItem(49, backButton(player, null, null));

        gui.open(player);
    }

    private GuiItem categoryButton(Player player, Category category) {
        ItemStack icon = new ItemStack(category.icon);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(category.label, category.color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Select a pathway →", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
            icon.setItemMeta(meta);
        }
        return new GuiItem(icon, event -> {
            event.setCancelled(true);
            openPathwaySelect(player, category);
        });
    }

    // ── Level 2: Pathway ────────────────────────────────────────────────────────

    private void openPathwaySelect(Player player, Category category) {
        Gui gui = Gui.gui()
            .title(Component.text("Pathway — " + category.label, NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .rows(6)
            .disableAllInteractions()
            .create();

        fillGlass(gui);

        // 21 pathways in rows 0-2 (3 rows × 7 per row = 21 slots)
        for (int i = 0; i < PATHWAYS.size(); i++) {
            String pathway = PATHWAYS.get(i);
            gui.setItem(i, pathwayButton(player, category, pathway));
        }

        gui.setItem(49, backButton(player, null, null));

        gui.open(player);
    }

    private GuiItem pathwayButton(Player player, Category category, String pathway) {
        // Use the seq-9 char as the pathway icon
        ItemStack icon = CoiItemResolver.resolveItem("char-" + pathway + "-9");
        if (icon == null) {
            icon = new ItemStack(Material.PLAYER_HEAD);
        }
        ItemStack display = icon.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(capitalize(pathway), NamedTextColor.YELLOW, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Select a sequence →", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
            display.setItemMeta(meta);
        }
        return new GuiItem(display, event -> {
            event.setCancelled(true);
            openSequenceSelect(player, category, pathway);
        });
    }

    // ── Level 3: Sequence ────────────────────────────────────────────────────────

    private void openSequenceSelect(Player player, Category category, String pathway) {
        Gui gui = Gui.gui()
            .title(Component.text(capitalize(pathway) + " — " + category.label, NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
            .rows(6)
            .disableAllInteractions()
            .create();

        fillGlass(gui);

        // Sequences 9→1 in slots 0-8 (first row)
        for (int seq = 9; seq >= 1; seq--) {
            int slot = 9 - seq; // seq 9 → slot 0, seq 1 → slot 8
            String coiId = category.coiIdFor(pathway, seq);
            gui.setItem(slot, sequenceButton(player, category, pathway, seq, coiId));
        }

        gui.setItem(49, backButton(player, category, pathway));

        gui.open(player);
    }

    private GuiItem sequenceButton(Player player, Category category, String pathway, int sequence, String coiId) {
        ItemStack icon;
        List<Component> lore = new ArrayList<>();

        if (CoiItemResolver.isMultiItem(coiId)) {
            List<ItemStack> bundle = CoiItemResolver.resolveItems(coiId);
            icon = bundle.isEmpty() ? new ItemStack(Material.CHEST) : bundle.get(0).clone();
            lore.add(Component.text("Bundle: " + bundle.size() + " item(s)", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        } else {
            ItemStack resolved = CoiItemResolver.resolveItem(coiId);
            icon = (resolved != null) ? resolved.clone() : new ItemStack(Material.BARRIER);
        }

        lore.add(Component.text("Sequence: " + sequence, NamedTextColor.AQUA)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("ID: " + coiId, NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("Click to add to shop →", NamedTextColor.GREEN)
            .decoration(TextDecoration.ITALIC, false));

        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            List<Component> existing = meta.hasLore() ? meta.lore() : List.of();
            List<Component> combined = new ArrayList<>(existing != null ? existing : List.of());
            combined.add(Component.empty());
            combined.addAll(lore);
            meta.lore(combined);
            icon.setItemMeta(meta);
        }

        return new GuiItem(icon, event -> {
            event.setCancelled(true);
            onSelect.accept(coiId);
            player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text("Added COI item ", NamedTextColor.GREEN))
                .append(Component.text(coiId, NamedTextColor.AQUA))
                .append(Component.text(" to next empty slot. Set prices and save.", NamedTextColor.GREEN)));
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * Back button: from sequence view → pathway view; from pathway view → main;
     * from main (category == null) → runs onBack (return to admin GUI).
     */
    private GuiItem backButton(Player player, Category category, String pathway) {
        ItemStack icon = new ItemStack(Material.ARROW);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("← Back", NamedTextColor.GRAY, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            icon.setItemMeta(meta);
        }
        return new GuiItem(icon, event -> {
            event.setCancelled(true);
            if (pathway != null) {
                // sequence view → pathway view
                openPathwaySelect(player, category);
            } else if (category != null) {
                // pathway view → main
                openMain(player);
            } else {
                // main → admin GUI
                onBack.run();
            }
        });
    }

    private void fillGlass(Gui gui) {
        GuiItem glass = glass();
        for (int i = 0; i < 54; i++) gui.setItem(i, glass);
    }

    private GuiItem glass() {
        ItemStack g = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = g.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            g.setItemMeta(meta);
        }
        return new GuiItem(g, event -> event.setCancelled(true));
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
