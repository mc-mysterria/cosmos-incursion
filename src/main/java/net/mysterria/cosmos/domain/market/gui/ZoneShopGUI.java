package net.mysterria.cosmos.domain.market.gui;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
import net.mysterria.cosmos.domain.market.service.ShopTransactionLogger;
import net.mysterria.cosmos.domain.market.service.ZoneShopManager;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Player-facing shop GUI. Town members spend their town's accumulated
 * resources (Gold / Iron / Gems) to purchase items from the shop.
 */
public class ZoneShopGUI {

    private static final int PAGE_SIZE = 45;

    private final CosmosIncursion plugin;
    private final ZoneShopManager shopManager;
    private final PermanentZoneManager zoneManager;
    private final ShopTransactionLogger txLogger;

    private static final long DENIAL_AUDIT_INTERVAL_MS = 5_000L;
    private static final int DENIAL_AUDIT_MAX_TRACKED = 1024;
    /** Last pre-confirmation denial row per player; touched only on the main thread. */
    private final Map<UUID, Long> lastDenialAudit = new HashMap<>();

    public ZoneShopGUI(CosmosIncursion plugin, ZoneShopManager shopManager,
                       PermanentZoneManager zoneManager, ShopTransactionLogger txLogger) {
        this.plugin = plugin;
        this.shopManager = shopManager;
        this.zoneManager = zoneManager;
        this.txLogger = txLogger;
    }

    // ── Open ────────────────────────────────────────────────────────────────────

    public void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int page) {
        Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);

        List<ShopItem> allItems = shopManager.getItems();

        int totalPages = Math.max(1, (int) Math.ceil(allItems.size() / (double) PAGE_SIZE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int start = safePage * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allItems.size());
        List<ShopItem> pageItems = allItems.subList(start, end);

        String townName = townOpt.map(TownData::name).orElse("No Town");
        int townId = townOpt.map(TownData::id).orElse(-1);

        Gui gui = Gui.gui()
                .title(Component.text("✦ Zone Shop — " + townName, NamedTextColor.DARK_PURPLE, TextDecoration.BOLD))
                .rows(6)
                .disableAllInteractions()
                .create();

        for (int i = 0; i < pageItems.size(); i++) {
            ShopItem si = pageItems.get(i);
            gui.setItem(i, shopItemButton(player, gui, si, townId, townOpt, safePage));
        }

        // Bottom row
        if (safePage > 0) {
            gui.setItem(45, navArrow("← Previous", () -> open(player, safePage - 1)));
        } else {
            gui.setItem(45, glass());
        }

        if (townOpt.isPresent()) {
            Map<ResourceType, Double> balance = zoneManager.getTownBalance(townId);
            gui.setItem(47, balanceItem(townName, balance));
        } else {
            gui.setItem(47, noTownItem());
        }

        gui.setItem(49, shopSignItem(allItems.size(), safePage + 1, totalPages));
        gui.setItem(51, glass());

        if (safePage < totalPages - 1) {
            gui.setItem(53, navArrow("Next →", () -> open(player, safePage + 1)));
        } else {
            gui.setItem(53, glass());
        }

        // Show purchase history button only to mayors/admins
        if (TownsToolkit.canManageTownShop(player)) {
            townOpt.ifPresent(townData -> gui.setItem(52, clickItem(
                    Material.BOOK,
                    Component.text("Purchase History", NamedTextColor.AQUA, TextDecoration.BOLD),
                    List.of(line("View recent shop purchases.", NamedTextColor.GRAY)),
                    () -> openTransactionHistory(player, townData, 0)
            )));
        }

        for (int s : new int[]{46, 48, 50}) gui.setItem(s, glass());

        gui.open(player);
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

    private GuiItem clickItem(Material mat, Component name, List<Component> lore, Runnable onClick) {
        return new GuiItem(buildStack(mat, name, lore), event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    private Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    public void openTransactionHistory(Player player, TownData town, int page) {
        var logger = plugin.getShopTransactionLogger();
        var allTx = logger.getHistory(town.id());

        int pageSize = 45;
        int totalPages = Math.max(1, (int) Math.ceil(allTx.size() / (double) pageSize));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int start = safePage * pageSize;
        int end = Math.min(start + pageSize, allTx.size());
        var pageTx = allTx.subList(start, end);

        Gui gui = Gui.gui()
                .title(Component.text("Purchase History — " + town.name(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD))
                .rows(6)
                .disableAllInteractions()
                .create();

        for (int i = 0; i < pageTx.size(); i++) {
            var tx = pageTx.get(i);
            ItemStack book = new ItemStack(Material.BOOK);
            ItemMeta meta = book.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(tx.playerName(), NamedTextColor.YELLOW, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(" → " + tx.itemName(), NamedTextColor.WHITE)
                                .decoration(TextDecoration.ITALIC, false)));
                meta.lore(List.of(
                        Component.text("Cost: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                                .append(Component.text(tx.priceSummary(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)),
                        Component.text(tx.timestamp(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                ));
                book.setItemMeta(meta);
            }
            gui.setItem(i, new GuiItem(book, event -> event.setCancelled(true)));
        }

        // Bottom nav row
        if (allTx.isEmpty()) {
            gui.setItem(49, infoItem(Material.BARRIER,
                    Component.text("No purchases yet", NamedTextColor.GRAY, TextDecoration.BOLD),
                    List.of()));
        }
        if (safePage > 0) {
            gui.setItem(45, clickItem(Material.ARROW,
                    Component.text("← Previous", NamedTextColor.YELLOW, TextDecoration.BOLD), List.of(),
                    () -> openTransactionHistory(player, town, safePage - 1)));
        }
        if (safePage < totalPages - 1) {
            gui.setItem(53, clickItem(Material.ARROW,
                    Component.text("Next →", NamedTextColor.YELLOW, TextDecoration.BOLD), List.of(),
                    () -> openTransactionHistory(player, town, safePage + 1)));
        }
        gui.setItem(49, backItem(() -> open(player)));

        gui.open(player);
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

    // ── Confirmation GUI ─────────────────────────────────────────────────────────

    private void openConfirmation(Player player, ShopItem si, TownData town, int returnPage) {
        Gui confirm = Gui.gui()
                .title(Component.text("Confirm Purchase?", NamedTextColor.DARK_RED, TextDecoration.BOLD))
                .rows(3)
                .disableAllInteractions()
                .create();

        // Fill with glass
        GuiItem pane = glass();
        for (int i = 0; i < 27; i++) confirm.setItem(i, pane);

        // Display item in center
        confirm.setItem(13, new GuiItem(buildShopDisplay(si, town.id()), event -> event.setCancelled(true)));

        // Confirm button (slots 10, 11, 12)
        GuiItem confirmAction = new GuiItem(buildConfirmButton().getItemStack(), event -> {
            event.setCancelled(true);
            executePurchase(player, si, town, returnPage);
        });
        confirm.setItem(10, confirmAction);
        confirm.setItem(11, confirmAction);
        confirm.setItem(12, confirmAction);

        // Cancel button (slots 14, 15, 16)
        GuiItem cancelBtn = buildCancelButton(() -> open(player, returnPage));
        confirm.setItem(14, cancelBtn);
        confirm.setItem(15, cancelBtn);
        confirm.setItem(16, cancelBtn);

        confirm.open(player);
    }

    private void executePurchase(Player player, ShopItem si, TownData town, int returnPage) {
        UUID correlationId = UUID.randomUUID();
        String businessId = "zone-shop.purchase." + correlationId;
        Map<ResourceType, Double> prices = si.getPrices();

        // Re-validate balance at purchase time
        Map<ResourceType, Double> balance = zoneManager.getTownBalance(town.id());
        Map<ResourceType, Double> balanceBefore = new EnumMap<>(ResourceType.class);
        balanceBefore.putAll(balance);
        for (Map.Entry<ResourceType, Double> entry : prices.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (balance.getOrDefault(entry.getKey(), 0.0) < entry.getValue()) {
                emitPurchaseResult(correlationId, businessId, player, town, si, null, prices, balanceBefore, balanceBefore,
                        AuditOutcome.DENIED, "insufficient_balance", Map.of());
                player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                        .append(Component.text("Your town no longer has enough " + entry.getKey().displayName() + ".", NamedTextColor.RED)));
                open(player, returnPage);
                return;
            }
        }

        List<ItemStack> toGive = si.getItems();
        if (toGive.isEmpty()) {
            emitPurchaseResult(correlationId, businessId, player, town, si, null, prices, balanceBefore, balanceBefore,
                    AuditOutcome.FAILED, "item_resolution_failed", Map.of());
            player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                    .append(Component.text("This item could not be resolved.", NamedTextColor.RED)));
            open(player, returnPage);
            return;
        }
        // Each stack is checked against the current inventory on its own. Anything that does
        // not fit at delivery is dropped and counted in the committed row (granted/dropped
        // amounts) rather than denying the purchase.
        for (ItemStack stack : toGive) {
            if (!hasInventorySpace(player, stack)) {
                emitPurchaseResult(correlationId, businessId, player, town, si, itemEvidence(stack), prices,
                        balanceBefore, balanceBefore, AuditOutcome.DENIED, "inventory_full",
                        Map.of("items", toGive.stream().map(this::itemEvidence).toList()));
                player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                        .append(Component.text("Your inventory is full.", NamedTextColor.RED)));
                open(player, returnPage);
                return;
            }
        }

        PermanentZoneManager.DeductResult deduction;
        try {
            deduction = zoneManager.tryDeductFromTown(town.id(), prices);
        } catch (RuntimeException failure) {
            emitPurchaseResult(correlationId, businessId, player, town, si, itemEvidence(toGive.get(0)), prices,
                    balanceBefore, zoneManager.getTownBalance(town.id()), AuditOutcome.FAILED,
                    "balance_persist_threw", Map.of("error", failure.getClass().getName()));
            throw failure;
        }
        if (deduction != PermanentZoneManager.DeductResult.DEDUCTED) {
            Map<ResourceType, Double> balanceAfter = new EnumMap<>(ResourceType.class);
            balanceAfter.putAll(zoneManager.getTownBalance(town.id()));
            emitPurchaseResult(correlationId, businessId, player, town, si, itemEvidence(toGive.get(0)), prices,
                    balanceBefore, balanceAfter, AuditOutcome.FAILED,
                    deductionFailureReason(deduction), Map.of());
            player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                    .append(Component.text("Purchase failed — insufficient town balance.", NamedTextColor.RED)));
            open(player, returnPage);
            return;
        }

        List<Map<String, Object>> grantedItems = new ArrayList<>();
        List<Map<String, Object>> droppedItems = new ArrayList<>();
        List<Map<String, Object>> undeliveredItems = new ArrayList<>();
        int requestedItemAmount = 0;
        int grantedItemAmount = 0;
        int droppedItemAmount = 0;
        int undeliveredItemAmount = 0;
        // The town was charged, so whatever no longer fits is dropped at the player
        for (ItemStack stack : toGive) {
            int requestedAmount = stack.getAmount();
            requestedItemAmount += requestedAmount;
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack.clone());
            int leftoverAmount = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int inventoryAmount = requestedAmount - leftoverAmount;
            if (inventoryAmount > 0) {
                grantedItems.add(stackEvidence(stack.getType(), inventoryAmount));
                grantedItemAmount += inventoryAmount;
            }
            for (ItemStack leftover : leftovers.values()) {
                Item dropped = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                // ItemSpawnEvent may be cancelled: only a live entity counts as delivered.
                if (dropped == null || !dropped.isValid() || dropped.isDead()) {
                    undeliveredItemAmount += leftover.getAmount();
                    undeliveredItems.add(itemEvidence(leftover));
                    continue;
                }
                droppedItemAmount += leftover.getAmount();
                Map<String, Object> evidence = itemEvidence(leftover);
                evidence.put("entity_uuid", dropped.getUniqueId().toString());
                droppedItems.add(evidence);
            }
        }

        ItemStack primary = si.getItem();
        Component itemName = primary.getItemMeta() != null && primary.getItemMeta().hasDisplayName()
                ? Objects.requireNonNull(primary.getItemMeta().displayName())
                : Component.text(primary.getType().name().replace('_', ' '), NamedTextColor.WHITE);

        String plainItemName = itemNamePlain(primary);
        String priceSummary = priceSummary(prices);

        // The deduction is committed. The purchase row is COMMITTED only when every requested
        // item reached the inventory or a live dropped entity; otherwise it is FAILED with the
        // undelivered amounts. Bounded in-memory history for the shop GUI is kept either way.
        Map<String, Object> primaryGrantedItem = grantedItems.isEmpty() ? itemEvidence(toGive.get(0)) : grantedItems.get(0);
        Map<String, Object> delivery = new LinkedHashMap<>();
        delivery.put("items", toGive.stream().map(this::itemEvidence).toList());
        delivery.put("requested_item_count", toGive.size());
        delivery.put("granted_item_count", grantedItems.size());
        delivery.put("dropped_item_count", droppedItems.size());
        delivery.put("undelivered_item_count", undeliveredItems.size());
        delivery.put("requested_total_amount", requestedItemAmount);
        delivery.put("granted_total_amount", grantedItemAmount);
        delivery.put("dropped_total_amount", droppedItemAmount);
        delivery.put("undelivered_total_amount", undeliveredItemAmount);
        delivery.put("price_summary", priceSummary);
        boolean fullyDelivered = undeliveredItems.isEmpty();
        emitPurchaseResult(correlationId, businessId, player, town, si, primaryGrantedItem, prices, balanceBefore,
                zoneManager.getTownBalance(town.id()),
                fullyDelivered ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                fullyDelivered ? null : "delivery_incomplete", delivery);
        emitGrantedPhysicalItems(correlationId, businessId, player, town, si, grantedItems);
        emitDroppedPhysicalItems(correlationId, businessId, player, town, si, droppedItems,
                AuditOutcome.COMMITTED, "inventory_fallback");
        emitDroppedPhysicalItems(correlationId, businessId, player, town, si, undeliveredItems,
                AuditOutcome.FAILED, "drop_spawn_cancelled");

        txLogger.log(town.id(), player.getName(), town.name(), plainItemName, prices);

        Component msg = Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text("Purchased ", NamedTextColor.GREEN))
                .append(itemName);
        if (toGive.size() > 1) {
            msg = msg.append(Component.text(" +" + (toGive.size() - 1) + " more", NamedTextColor.GRAY));
        }
        player.sendMessage(msg.append(Component.text("!", NamedTextColor.GREEN)));

        // Notify all online town members
        Component broadcast = Component.text("[Shop] ", NamedTextColor.GOLD)
                .append(Component.text(player.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" purchased ", NamedTextColor.GRAY))
                .append(itemName)
                .append(Component.text(" for ", NamedTextColor.GRAY))
                .append(Component.text(priceSummary, NamedTextColor.WHITE));
        for (Player member : TownsToolkit.getMembers(town)) {
            if (!member.equals(player)) member.sendMessage(broadcast);
        }

        open(player, returnPage);
    }

    // ── Shop item button ─────────────────────────────────────────────────────────

    private GuiItem shopItemButton(Player player, Gui gui, ShopItem si,
                                   int townId, Optional<TownData> townOpt, int page) {

        ItemStack display = buildShopDisplay(si, townId);

        return new GuiItem(display, event -> {
            event.setCancelled(true);

            if (townOpt.isEmpty()) {
                emitPurchaseDenied(player, si, null, "not_town_member", Map.of());
                player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                        .append(Component.text("You must be in a town to purchase items.", NamedTextColor.RED)));
                return;
            }

            TownData town = townOpt.get();

            if (!TownsToolkit.canManageTownShop(player)) {
                emitPurchaseDenied(player, si, town, "missing_permission", Map.of());
                player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                        .append(Component.text("Only the town mayor or a trusted member can purchase items.", NamedTextColor.RED)));
                return;
            }

            Map<ResourceType, Double> prices = si.getPrices();

            if (prices.isEmpty() || prices.values().stream().allMatch(v -> v <= 0)) {
                emitPurchaseDenied(player, si, town, "price_unset", Map.of());
                player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                        .append(Component.text("This item has no price set.", NamedTextColor.RED)));
                return;
            }

            // Quick pre-check balance before opening confirmation
            Map<ResourceType, Double> balance = zoneManager.getTownBalance(town.id());
            for (Map.Entry<ResourceType, Double> entry : prices.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (balance.getOrDefault(entry.getKey(), 0.0) < entry.getValue()) {
                    emitPurchaseDenied(player, si, town, "insufficient_balance",
                            Map.of("resource", entry.getKey().configKey(),
                                    "balance", resourceAmounts(balance)));
                    player.sendMessage(Component.text("[Shop] ", NamedTextColor.GOLD)
                            .append(Component.text("Your town doesn't have enough " + entry.getKey().displayName() + ".", NamedTextColor.RED)));
                    return;
                }
            }

            openConfirmation(player, si, town, page);
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String deductionFailureReason(PermanentZoneManager.DeductResult result) {
        return switch (result) {
            case INVALID_AMOUNT -> "invalid_price";
            case PERSIST_FAILED -> "balance_persist_failed";
            default -> "balance_changed_before_commit";
        };
    }

    /**
     * Records a purchase refused before the confirmation screen. Rate-limited per player so
     * repeated clicks on a refused item do not produce one row per click. Main thread only.
     */
    private void emitPurchaseDenied(Player player, ShopItem shopItem, TownData town, String reason,
                                    Map<String, ?> extra) {
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        Long last = lastDenialAudit.get(playerId);
        if (last != null && now - last < DENIAL_AUDIT_INTERVAL_MS) return;
        if (lastDenialAudit.size() >= DENIAL_AUDIT_MAX_TRACKED) {
            lastDenialAudit.values().removeIf(time -> now - time >= DENIAL_AUDIT_INTERVAL_MS);
        }
        lastDenialAudit.put(playerId, now);

        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (town != null) {
                metadata.put("town_id", town.id());
                metadata.put("town_name", town.name());
            }
            metadata.put("shop_item_id", shopItem.getId().toString());
            metadata.put("coi_item_id", shopItem.getCoiItemId() == null ? "" : shopItem.getCoiItemId());
            metadata.put("logical_type", "zone_shop_item");
            metadata.put("logical_id", shopItem.getId().toString());
            metadata.put("price", resourceAmounts(shopItem.getPrices()));
            metadata.put("stage", "pre_confirmation");
            if (extra != null) metadata.putAll(extra);
            MysterriaAuditEmitter.putPlayerLocation(metadata, player);
            UUID correlationId = UUID.randomUUID();
            MysterriaAuditEmitter.emit(plugin, "shop.purchase", AuditOutcome.DENIED, AuditRisk.NORMAL,
                    correlationId, "zone-shop.purchase." + correlationId, playerId, playerId, null,
                    reason, metadata);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
    }

    private String itemNamePlain(ItemStack item) {
        if (item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            Component name = item.getItemMeta().displayName();
            if (name != null)
                return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
        }
        return item.getType().name().replace('_', ' ');
    }

    private void emitPurchaseResult(UUID correlationId, String businessId, Player player, TownData town,
                                    ShopItem shopItem, Map<String, Object> primaryItem,
                                    Map<ResourceType, Double> prices,
                                    Map<ResourceType, Double> balanceBefore,
                                    Map<ResourceType, Double> balanceAfter, AuditOutcome outcome,
                                    String reason, Map<String, ?> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("town_id", town.id());
        metadata.put("town_name", town.name());
        metadata.put("shop_item_id", shopItem.getId().toString());
        metadata.put("coi_item_id", shopItem.getCoiItemId() == null ? "" : shopItem.getCoiItemId());
        metadata.put("logical_type", "zone_shop_item");
        metadata.put("logical_id", shopItem.getId().toString());
        metadata.put("price", resourceAmounts(prices));
        metadata.put("balance_before", resourceAmounts(balanceBefore));
        metadata.put("balance_after", resourceAmounts(balanceAfter));
        if (primaryItem != null) metadata.put("item", primaryItem);
        if (extra != null) metadata.putAll(extra);
        MysterriaAuditEmitter.putPlayerLocation(metadata, player);
        MysterriaAuditEmitter.emit(plugin, "shop.purchase", outcome,
                outcome == AuditOutcome.COMMITTED ? AuditRisk.NORMAL : AuditRisk.HIGH,
                correlationId, businessId, player.getUniqueId(), player.getUniqueId(), null,
                reason, metadata);
    }

    private void emitGrantedPhysicalItems(UUID correlationId, String businessId, Player player,
                                          TownData town, ShopItem shopItem,
                                          List<Map<String, Object>> items) {
        Map<Object, Map<String, Object>> aggregated = new LinkedHashMap<>();
        for (Map<String, Object> evidence : items) {
            Object key = evidence.getOrDefault("material", "unknown");
            Map<String, Object> existing = aggregated.get(key);
            if (existing == null) {
                aggregated.put(key, new LinkedHashMap<>(evidence));
            } else {
                int amount = ((Number) existing.getOrDefault("amount", 0)).intValue()
                        + ((Number) evidence.getOrDefault("amount", 0)).intValue();
                existing.put("amount", amount);
            }
        }

        for (Map<String, Object> evidence : aggregated.values()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("town_id", town.id());
            metadata.put("town_name", town.name());
            metadata.put("shop_item_id", shopItem.getId().toString());
            metadata.put("logical_type", "zone_shop_item");
            metadata.put("logical_id", shopItem.getId().toString());
            metadata.put("material", evidence.getOrDefault("material", "unknown"));
            metadata.put("amount", evidence.getOrDefault("amount", 0));
            MysterriaAuditEmitter.putPlayerLocation(metadata, player);
            MysterriaAuditEmitter.emit(plugin, "shop.item_granted", AuditOutcome.COMMITTED,
                    AuditRisk.NORMAL, correlationId, businessId, player.getUniqueId(),
                    player.getUniqueId(), null, null, metadata);
        }
    }

    /**
     * One row per overflow stack, with the player's location. Delivered drops also carry the
     * dropped entity's UUID.
     */
    private void emitDroppedPhysicalItems(UUID correlationId, String businessId, Player player,
                                          TownData town, ShopItem shopItem,
                                          List<Map<String, Object>> items, AuditOutcome outcome,
                                          String reason) {
        for (Map<String, Object> evidence : items) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("town_id", town.id());
            metadata.put("town_name", town.name());
            metadata.put("shop_item_id", shopItem.getId().toString());
            metadata.put("logical_type", "zone_shop_item");
            metadata.put("logical_id", shopItem.getId().toString());
            metadata.put("material", evidence.getOrDefault("material", "unknown"));
            metadata.put("amount", evidence.getOrDefault("amount", 0));
            metadata.put("entity_uuid", evidence.getOrDefault("entity_uuid", ""));
            MysterriaAuditEmitter.putPlayerLocation(metadata, player);
            MysterriaAuditEmitter.emit(plugin, "shop.item_dropped", outcome,
                    outcome == AuditOutcome.COMMITTED ? AuditRisk.NORMAL : AuditRisk.HIGH,
                    correlationId, businessId, player.getUniqueId(),
                    player.getUniqueId(), null, reason, metadata);
        }
    }

    private Map<String, Double> resourceAmounts(Map<ResourceType, Double> values) {
        Map<String, Double> result = new LinkedHashMap<>();
        values.forEach((type, amount) -> result.put(type.configKey(), amount));
        return result;
    }

    private String priceSummary(Map<ResourceType, Double> prices) {
        StringJoiner summary = new StringJoiner(", ");
        for (ResourceType type : ResourceType.values()) {
            double amount = prices.getOrDefault(type, 0.0);
            if (amount > 0) summary.add(String.format("%.0f %s", amount, type.displayName()));
        }
        return summary.length() == 0 ? "free" : summary.toString();
    }

    private Map<String, Object> itemEvidence(ItemStack item) {
        return stackEvidence(item.getType(), item.getAmount());
    }

    /** Material and amount only: item meta and CoI tags are not read for audit rows. */
    private static Map<String, Object> stackEvidence(Material type, int amount) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("material", type.name().toLowerCase(Locale.ROOT));
        evidence.put("amount", amount);
        return evidence;
    }

    // ── Inventory space check ────────────────────────────────────────────────────

    private boolean hasInventorySpace(Player player, ItemStack item) {
        int needed = item.getAmount();
        int available = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) {
            if (slot == null || slot.getType() == Material.AIR) {
                available += item.getMaxStackSize();
            } else if (slot.isSimilar(item)) {
                available += item.getMaxStackSize() - slot.getAmount();
            }
            if (available >= needed) return true;
        }
        return false;
    }

    // ── Item builders ─────────────────────────────────────────────────────────────

    private ItemStack buildShopDisplay(ShopItem si, int townId) {
        ItemStack base = si.getItem();
        ItemStack copy = base.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) return copy;

        List<Component> lore = new ArrayList<>();
        if (meta.hasLore() && meta.lore() != null) lore.addAll(Objects.requireNonNull(meta.lore()));

        // For multi-item COI bundles, indicate bundle size
        List<ItemStack> allItems = si.getItems();
        if (allItems.size() > 1) {
            lore.add(Component.text("Bundle: " + allItems.size() + " items granted", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false));
        }

        lore.add(Component.empty());
        lore.add(Component.text("── Price ──", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));

        Map<ResourceType, Double> balance = townId >= 0 ? zoneManager.getTownBalance(townId) : Collections.emptyMap();
        Map<ResourceType, Double> prices = si.getPrices();

        if (prices.isEmpty() || prices.values().stream().allMatch(v -> v <= 0)) {
            lore.add(Component.text("No price set", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        } else {
            for (ResourceType rt : ResourceType.values()) {
                double price = prices.getOrDefault(rt, 0.0);
                if (price <= 0) continue;
                double have = balance.getOrDefault(rt, 0.0);
                NamedTextColor col = have >= price ? NamedTextColor.GREEN : NamedTextColor.RED;
                lore.add(Component.text(
                                String.format("  %s: %.0f (have %.0f)", rt.displayName(), price, have), col)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("Left-click to purchase", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        copy.setItemMeta(meta);
        return copy;
    }

    private GuiItem balanceItem(String townName, Map<ResourceType, Double> balance) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("⚖ " + townName + " Balance", NamedTextColor.GOLD, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (ResourceType rt : ResourceType.values()) {
                double val = balance.getOrDefault(rt, 0.0);
                NamedTextColor col = switch (rt) {
                    case GOLD -> NamedTextColor.GOLD;
                    case SILVER -> NamedTextColor.WHITE;
                    case GEMS -> NamedTextColor.GREEN;
                };
                lore.add(Component.text(String.format("  %s: %.1f", rt.displayName(), val), col)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> event.setCancelled(true));
    }

    private GuiItem noTownItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("No Town Found", NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Join a town to use the shop.", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> event.setCancelled(true));
    }

    private GuiItem shopSignItem(int total, int page, int totalPages) {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Zone Shop", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text(total + " item(s) available", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Page " + page + " / " + totalPages, NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
            ));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> event.setCancelled(true));
    }

    private GuiItem navArrow(String label, Runnable action) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(label, NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            action.run();
        });
    }

    private GuiItem buildConfirmButton() {
        ItemStack item = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✔ Confirm Purchase", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> event.setCancelled(true));
    }

    private GuiItem buildCancelButton(Runnable onCancel) {
        ItemStack item = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("✗ Cancel", NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onCancel.run();
        });
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
}
