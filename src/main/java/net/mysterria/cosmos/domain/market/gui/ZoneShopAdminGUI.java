package net.mysterria.cosmos.domain.market.gui;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
import net.mysterria.cosmos.domain.market.service.ZoneShopManager;
import net.mysterria.cosmos.toolkit.CoiItemResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Admin GUI for managing the zone shop.
 *
 * <p>Slots 0–44 are shop slots. All interactions are explicitly handled:
 * <ul>
 *   <li>Click an empty slot while holding an item → places that item into the shop slot.</li>
 *   <li>Left-click or right-click an occupied vanilla slot → opens the price editor.</li>
 *   <li>Shift-click an occupied vanilla slot → removes the item and returns it to your inventory.</li>
 *   <li>Shift-click an occupied COI slot → removes the COI entry (item not returned).</li>
 *   <li>Click an occupied slot while holding a different item → swaps them (vanilla only).</li>
 * </ul>
 * Row 6 (slots 45–53) holds control buttons: Save, Clear All, Help, COI Catalog, Close.
 */
public class ZoneShopAdminGUI {

    private final ZoneShopManager shopManager;

    public ZoneShopAdminGUI(ZoneShopManager shopManager) {
        this.shopManager = shopManager;
    }

    // ── Open ────────────────────────────────────────────────────────────────────

    public void open(Player player) {
        Map<Integer, ItemStack> slotItems      = new HashMap<>();
        Map<Integer, String>    slotCoiIds     = new HashMap<>();
        Map<Integer, Map<ResourceType, Double>> sessionPrices = new HashMap<>();

        Gui gui = Gui.gui()
            .title(Component.text("✦ Zone Shop — Admin Editor", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
            .rows(6)
            .create();

        // Populate existing shop items
        List<ShopItem> existing = shopManager.getItems();
        for (int i = 0; i < 45; i++) {
            if (i < existing.size()) {
                ShopItem si = existing.get(i);
                ItemStack display = si.getItem();
                slotItems.put(i, display);
                sessionPrices.put(i, new EnumMap<>(si.getPrices()));
                if (si.isCoi()) slotCoiIds.put(i, si.getCoiItemId());
                setOccupied(gui, player, i, display, slotItems, sessionPrices, slotCoiIds);
            } else {
                setEmpty(gui, player, i, slotItems, sessionPrices, slotCoiIds);
            }
        }

        // Control row
        gui.setItem(45, saveButton(player, gui, slotItems, sessionPrices, slotCoiIds));
        gui.setItem(47, clearAllButton(player, gui, slotItems, sessionPrices, slotCoiIds));
        gui.setItem(49, helpButton());
        gui.setItem(51, coiCatalogButton(player, gui, slotItems, sessionPrices, slotCoiIds));
        gui.setItem(53, closeButton(player));
        GuiItem glass = glassPane();
        for (int s : new int[]{46, 48, 50, 52}) gui.setItem(s, glass);

        gui.open(player);
    }

    // ── Slot state helpers ───────────────────────────────────────────────────────

    private void setOccupied(Gui gui, Player player, int slot, ItemStack item,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        Map<ResourceType, Double> prices = sessionPrices.computeIfAbsent(slot, k -> new EnumMap<>(ResourceType.class));
        String coiId = slotCoiIds.get(slot);
        ItemStack display = priceAnnotated(item, prices, coiId);

        GuiItem gi = new GuiItem(display, event -> {
            event.setCancelled(true);
            ItemStack cursor   = event.getCursor();
            boolean hasCursor  = cursor != null && cursor.getType() != Material.AIR;

            if (event.isShiftClick()) {
                // Vanilla items: return to player. COI items: just remove from shop.
                if (!slotCoiIds.containsKey(slot)) {
                    player.getInventory().addItem(item.clone());
                }
                slotCoiIds.remove(slot);
                slotItems.remove(slot);
                sessionPrices.remove(slot);
                setEmpty(gui, player, slot, slotItems, sessionPrices, slotCoiIds);
                syncSlot(gui, slot);

            } else if (hasCursor) {
                // Swap: place cursor item into slot, push current vanilla item back to cursor.
                ItemStack placed = cursor.clone();
                if (!slotCoiIds.containsKey(slot)) {
                    event.getWhoClicked().setItemOnCursor(item.clone());
                } else {
                    event.getWhoClicked().setItemOnCursor(null);
                    slotCoiIds.remove(slot);
                }
                slotItems.put(slot, placed);
                sessionPrices.put(slot, new EnumMap<>(ResourceType.class));
                setOccupied(gui, player, slot, placed, slotItems, sessionPrices, slotCoiIds);
                syncSlot(gui, slot);

            } else {
                // Left/right click with empty hand → open price editor
                openPriceEditor(player, gui, slot, item, slotItems, sessionPrices, slotCoiIds);
            }
        });

        gui.updateItem(slot, gi);
    }

    private void setEmpty(Gui gui, Player player, int slot,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        ItemStack ph = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = ph.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Empty — hold item and click to add", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
            ph.setItemMeta(meta);
        }

        GuiItem gi = new GuiItem(ph, event -> {
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType() == Material.AIR) return;
            if (!event.isLeftClick()) return;

            ItemStack placed = cursor.clone();
            event.getWhoClicked().setItemOnCursor(null);
            slotCoiIds.remove(slot);
            slotItems.put(slot, placed);
            sessionPrices.put(slot, new EnumMap<>(ResourceType.class));
            setOccupied(gui, player, slot, placed, slotItems, sessionPrices, slotCoiIds);
            syncSlot(gui, slot);
        });

        gui.updateItem(slot, gi);
    }

    private void syncSlot(Gui gui, int slot) {
        GuiItem gi = gui.getGuiItem(slot);
        gui.getInventory().setItem(slot, gi != null ? gi.getItemStack() : null);
    }

    // ── Price editor ─────────────────────────────────────────────────────────────

    private void openPriceEditor(Player player, Gui parentGui, int slot, ItemStack editItem,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        Map<ResourceType, Double> prices = sessionPrices.computeIfAbsent(slot, k -> new EnumMap<>(ResourceType.class));

        Gui editor = Gui.gui()
            .title(Component.text("Set Prices — Slot " + slot, NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
            .rows(5)
            .create();

        Runnable onDone = () -> {
            setOccupied(parentGui, player, slot, editItem, slotItems, sessionPrices, slotCoiIds);
            syncSlot(parentGui, slot);
            parentGui.open(player);
        };

        editor.setItem(0, backItem(onDone));
        editor.setItem(4, displayItem(editItem));
        for (int s : new int[]{1, 2, 3, 5, 6, 7, 8}) editor.setItem(s, glassPane());

        buildPriceRow(editor, player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, ResourceType.GOLD,   9);
        buildPriceRow(editor, player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, ResourceType.SILVER, 18);
        buildPriceRow(editor, player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, ResourceType.GEMS,   27);

        for (int s : new int[]{36, 37, 38, 39, 41, 42, 43, 44}) editor.setItem(s, glassPane());
        editor.setItem(40, confirmItem(onDone));

        editor.open(player);
    }

    private void buildPriceRow(Gui editor, Player player, Gui parentGui, int slot,
            ItemStack editItem,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds,
            ResourceType type, int rowStart) {

        Map<ResourceType, Double> prices = sessionPrices.computeIfAbsent(slot, k -> new EnumMap<>(ResourceType.class));
        double current = prices.getOrDefault(type, 0.0);
        NamedTextColor color = priceColor(type);

        editor.setItem(rowStart,     adjustBtn("-10", NamedTextColor.RED,   () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, -10)));
        editor.setItem(rowStart + 1, adjustBtn("-5",  NamedTextColor.RED,   () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, -5)));
        editor.setItem(rowStart + 2, adjustBtn("-1",  NamedTextColor.RED,   () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, -1)));

        ItemStack display = new ItemStack(type.getDefaultMaterial());
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(type.displayName() + " Price", color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(String.format("Current: %.0f", current), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)));
            display.setItemMeta(meta);
        }
        editor.setItem(rowStart + 3, new GuiItem(display, e -> e.setCancelled(true)));

        editor.setItem(rowStart + 4, adjustBtn("+1",  NamedTextColor.GREEN, () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, 1)));
        editor.setItem(rowStart + 5, adjustBtn("+5",  NamedTextColor.GREEN, () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, 5)));
        editor.setItem(rowStart + 6, adjustBtn("+10", NamedTextColor.GREEN, () -> adjustAndReopen(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds, type, 10)));

        editor.setItem(rowStart + 7, glassPane());

        ItemStack label = new ItemStack(type.getDefaultMaterial());
        ItemMeta lm = label.getItemMeta();
        if (lm != null) {
            lm.displayName(Component.text(type.displayName(), color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            label.setItemMeta(lm);
        }
        editor.setItem(rowStart + 8, new GuiItem(label, e -> e.setCancelled(true)));
    }

    private void adjustAndReopen(Player player, Gui parentGui, int slot,
            ItemStack editItem,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds,
            ResourceType type, double delta) {

        Map<ResourceType, Double> prices = sessionPrices.computeIfAbsent(slot, k -> new EnumMap<>(ResourceType.class));
        double updated = Math.max(0, prices.getOrDefault(type, 0.0) + delta);
        if (updated <= 0) prices.remove(type);
        else prices.put(type, updated);

        openPriceEditor(player, parentGui, slot, editItem, slotItems, sessionPrices, slotCoiIds);
    }

    // ── Control buttons ──────────────────────────────────────────────────────────

    private GuiItem saveButton(Player player, Gui gui,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✔ Save Shop", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Saves all items and their prices.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("Left/right-click an item to set prices first.", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            List<ShopItem> newItems = new ArrayList<>();
            slotItems.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int s = e.getKey();
                    Map<ResourceType, Double> prices =
                        sessionPrices.getOrDefault(s, new EnumMap<>(ResourceType.class));
                    if (slotCoiIds.containsKey(s)) {
                        newItems.add(new ShopItem(UUID.randomUUID(), slotCoiIds.get(s), prices));
                    } else {
                        newItems.add(new ShopItem(UUID.randomUUID(), e.getValue(), prices));
                    }
                });
            shopManager.setItems(newItems);
            shopManager.save();
            player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text("Shop saved — " + newItems.size() + " item(s).", NamedTextColor.GREEN)));
            player.closeInventory();
        });
    }

    private GuiItem clearAllButton(Player player, Gui gui,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        ItemStack item = new ItemStack(Material.TNT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✖ Clear All Slots", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Returns vanilla items to your inventory.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("COI items are simply removed.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("You must still click Save to apply.", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            for (Map.Entry<Integer, ItemStack> e : slotItems.entrySet()) {
                if (!slotCoiIds.containsKey(e.getKey())) {
                    player.getInventory().addItem(e.getValue().clone());
                }
            }
            slotItems.clear();
            slotCoiIds.clear();
            sessionPrices.clear();
            for (int s = 0; s < 45; s++) {
                setEmpty(gui, player, s, slotItems, sessionPrices, slotCoiIds);
                syncSlot(gui, s);
            }
            player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text("All slots cleared. Click Save to apply.", NamedTextColor.YELLOW)));
        });
    }

    private GuiItem coiCatalogButton(Player player, Gui gui,
            Map<Integer, ItemStack> slotItems,
            Map<Integer, Map<ResourceType, Double>> sessionPrices,
            Map<Integer, String> slotCoiIds) {

        ItemStack item = new ItemStack(Material.ENCHANTING_TABLE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("⚗ COI Item Catalog", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("Browse chars, potions, recipes,", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("and ingredient bundles from COI.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);

            Consumer<String> onSelect = coiId -> {
                for (int slot = 0; slot < 45; slot++) {
                    if (!slotItems.containsKey(slot)) {
                        ItemStack display;
                        if (CoiItemResolver.isMultiItem(coiId)) {
                            List<ItemStack> bundle = CoiItemResolver.resolveItems(coiId);
                            display = bundle.isEmpty() ? new ItemStack(Material.CHEST) : bundle.get(0).clone();
                        } else {
                            ItemStack resolved = CoiItemResolver.resolveItem(coiId);
                            display = resolved != null ? resolved : new ItemStack(Material.BARRIER);
                        }
                        slotItems.put(slot, display);
                        slotCoiIds.put(slot, coiId);
                        sessionPrices.put(slot, new EnumMap<>(ResourceType.class));
                        setOccupied(gui, player, slot, display, slotItems, sessionPrices, slotCoiIds);
                        syncSlot(gui, slot);
                        break;
                    }
                }
                gui.open(player);
            };

            new CoiCatalogGUI(onSelect, () -> gui.open(player)).openMain(player);
        });
    }

    private GuiItem helpButton() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("How to use", NamedTextColor.AQUA, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                Component.text("• Hold item, left-click empty slot → add to shop.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("• Left/right-click occupied slot → set price.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("• Shift-click occupied slot → remove item.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("• ⚗ COI Catalog → add COI items by pathway/sequence.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("• Click ✔ Save Shop when done.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> event.setCancelled(true));
    }

    private GuiItem closeButton(Player player) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Close (without saving)", NamedTextColor.RED, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            player.closeInventory();
        });
    }

    // ── Item builders ─────────────────────────────────────────────────────────────

    private ItemStack priceAnnotated(ItemStack base, Map<ResourceType, Double> prices, @Nullable String coiId) {
        ItemStack copy = base.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;

        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) lore.addAll(Objects.requireNonNull(meta.lore()));
        lore.add(Component.empty());

        if (coiId != null) {
            lore.add(Component.text("COI: " + coiId, NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.text("── Price ──", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));

        boolean anyPrice = prices.values().stream().anyMatch(v -> v > 0);
        if (!anyPrice) {
            lore.add(Component.text("No price set — click to configure", NamedTextColor.RED)
                .decoration(TextDecoration.ITALIC, false));
        } else {
            for (ResourceType rt : ResourceType.values()) {
                double p = prices.getOrDefault(rt, 0.0);
                if (p > 0) lore.add(Component.text(
                    String.format("  %s: %.0f", rt.displayName(), p), priceColor(rt))
                    .decoration(TextDecoration.ITALIC, false));
            }
        }
        lore.add(Component.empty());
        lore.add(Component.text("Left/Right-click → set price  |  Shift-click → remove", NamedTextColor.YELLOW)
            .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private GuiItem adjustBtn(String label, NamedTextColor color, Runnable action) {
        Material mat = (color == NamedTextColor.RED) ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(label, color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            action.run();
        });
    }

    private GuiItem confirmItem(Runnable action) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✔ Confirm Prices", NamedTextColor.GREEN, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            action.run();
        });
    }

    private GuiItem backItem(Runnable action) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("← Back", NamedTextColor.GRAY, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            action.run();
        });
    }

    private GuiItem displayItem(ItemStack base) {
        return new GuiItem(base.clone(), event -> event.setCancelled(true));
    }

    private GuiItem glassPane() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
            glass.setItemMeta(meta);
        }
        return new GuiItem(glass, event -> event.setCancelled(true));
    }

    private NamedTextColor priceColor(ResourceType type) {
        return switch (type) {
            case GOLD   -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.WHITE;
            case GEMS   -> NamedTextColor.GREEN;
        };
    }
}
