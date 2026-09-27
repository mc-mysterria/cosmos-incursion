package net.mysterria.cosmos.domain.market.service;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import net.mysterria.cosmos.toolkit.item.CoiItemIdentity;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;

public class ZoneShopManager {

    private final CosmosIncursion plugin;
    private final File shopFile;
    private final Gson gson;
    private final List<ShopItem> items = new ArrayList<>();

    public ZoneShopManager(CosmosIncursion plugin) {
        this.plugin   = plugin;
        this.shopFile = new File(plugin.getDataFolder(), "zone_shop.json");
        this.gson     = new GsonBuilder().setPrettyPrinting().create();
    }

    public List<ShopItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public void setItems(List<ShopItem> newItems) {
        items.clear();
        items.addAll(newItems);
    }

    public void addItem(ShopItem item) {
        items.add(item);
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    /** Writes the catalogue to disk; returns whether the write succeeded. */
    public boolean save() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ShopItem si : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", si.getId().toString());

            if (si.isCoi()) {
                entry.put("coiItemId", si.getCoiItemId());
            } else {
                entry.put("item", Base64.getEncoder().encodeToString(si.getItem().serializeAsBytes()));
            }

            Map<String, Double> priceMap = new LinkedHashMap<>();
            for (Map.Entry<ResourceType, Double> p : si.getPrices().entrySet()) {
                priceMap.put(p.getKey().name(), p.getValue());
            }
            entry.put("prices", priceMap);
            list.add(entry);
        }
        try (FileWriter fw = new FileWriter(shopFile)) {
            gson.toJson(list, fw);
            return true;
        } catch (IOException | JsonIOException e) {
            plugin.log("Failed to save zone shop: " + e.getMessage());
            return false;
        }
    }

    /**
     * Replaces the catalogue on behalf of an admin, saves it, and records one
     * {@code admin.zone_shop_edited} row with the before/after catalogue.
     */
    public boolean replaceItems(List<ShopItem> newItems, CommandSender actor, String operation) {
        List<ShopItem> before = List.copyOf(items);
        setItems(newItems);
        return saveAndAudit(before, actor, operation);
    }

    /** Appends one item on behalf of an admin, saves, and records {@code admin.zone_shop_edited}. */
    public boolean addItem(ShopItem item, CommandSender actor, String operation) {
        List<ShopItem> before = List.copyOf(items);
        addItem(item);
        return saveAndAudit(before, actor, operation);
    }

    private boolean saveAndAudit(List<ShopItem> beforeItems, CommandSender actor, String operation) {
        boolean saved = save();
        List<ShopItem> afterItems = List.copyOf(items);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("operation", operation);
        putCatalogueDiff(metadata, beforeItems, afterItems);
        MysterriaAuditEmitter.putActor(metadata, actor);
        UUID correlationId = UUID.randomUUID();
        MysterriaAuditEmitter.emit(plugin, "admin.zone_shop_edited",
                saved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED, AuditRisk.HIGH,
                correlationId, "zone-shop.edit." + correlationId, MysterriaAuditEmitter.actorId(actor),
                null, null, saved ? operation : "shop_persistence_failed", metadata);
        return saved;
    }

    /**
     * Adds the before/after catalogue, the added/removed entries, the shop entry IDs, and the CoI
     * physical identities ({@code item_uuid}/{@code parent_item_uuid}) of listed stacks. The GUI
     * regenerates entry IDs on every save, so the added/removed diff is keyed by logical identity
     * (including physical item UUIDs) and price, not by entry ID. When an added entry carries a
     * tracked item, the first one is also promoted to top-level {@code item_uuid}/
     * {@code parent_item_uuid}.
     */
    private static void putCatalogueDiff(Map<String, Object> metadata, List<ShopItem> beforeItems,
                                         List<ShopItem> afterItems) {
        List<String> before = describe(beforeItems);
        List<String> after = describe(afterItems);
        List<String> added = new ArrayList<>(after);
        before.forEach(added::remove);
        List<String> removed = new ArrayList<>(before);
        after.forEach(removed::remove);
        metadata.put("item_count_before", before.size());
        metadata.put("item_count_after", after.size());
        metadata.put("items_before", String.join("; ", before));
        metadata.put("items_after", String.join("; ", after));
        metadata.put("items_added", String.join("; ", added));
        metadata.put("items_removed", String.join("; ", removed));
        metadata.put("shop_item_ids_before", joinIds(beforeItems));
        metadata.put("shop_item_ids_after", joinIds(afterItems));

        Set<String> uuidsBefore = trackedUuids(beforeItems);
        Set<String> uuidsAfter = trackedUuids(afterItems);
        Set<String> uuidsAdded = new LinkedHashSet<>(uuidsAfter);
        uuidsAdded.removeAll(uuidsBefore);
        Set<String> uuidsRemoved = new LinkedHashSet<>(uuidsBefore);
        uuidsRemoved.removeAll(uuidsAfter);
        metadata.put("item_uuids_added", String.join(",", uuidsAdded));
        metadata.put("item_uuids_removed", String.join(",", uuidsRemoved));
        promoteFirstAddedIdentity(metadata, afterItems, uuidsAdded);
    }

    private static void promoteFirstAddedIdentity(Map<String, Object> metadata, List<ShopItem> afterItems,
                                                  Set<String> uuidsAdded) {
        for (ShopItem entry : afterItems) {
            if (entry.isCoi()) continue;
            Map<String, Object> evidence = CoiItemIdentity.evidence(entry.getItem());
            Object itemUuid = evidence.get("item_uuid");
            if (itemUuid == null || !uuidsAdded.contains(itemUuid.toString())) continue;
            metadata.put("item_uuid", itemUuid);
            if (evidence.containsKey("parent_item_uuid")) {
                metadata.put("parent_item_uuid", evidence.get("parent_item_uuid"));
            }
            metadata.put("item_shop_item_id", entry.getId().toString());
            return;
        }
    }

    private static String joinIds(List<ShopItem> entries) {
        StringJoiner ids = new StringJoiner(",");
        entries.forEach(entry -> ids.add(entry.getId().toString()));
        return ids.toString();
    }

    private static Set<String> trackedUuids(List<ShopItem> entries) {
        Set<String> result = new LinkedHashSet<>();
        for (ShopItem entry : entries) {
            if (entry.isCoi()) continue;
            Object itemUuid = CoiItemIdentity.evidence(entry.getItem()).get("item_uuid");
            if (itemUuid != null) result.add(itemUuid.toString());
        }
        return result;
    }

    /**
     * Description of each entry without its (regenerated) entry ID: logical item, CoI physical
     * identity when tagged, and price.
     */
    private static List<String> describe(List<ShopItem> entries) {
        List<String> result = new ArrayList<>();
        for (ShopItem entry : entries) {
            String identity;
            if (entry.isCoi()) {
                identity = "coi:" + entry.getCoiItemId();
            } else {
                identity = describeStack(entry.getItem());
            }
            StringJoiner price = new StringJoiner(",");
            for (ResourceType type : ResourceType.values()) {
                double value = entry.getPrices().getOrDefault(type, 0.0);
                if (value > 0) price.add(type.configKey() + "=" + (long) value);
            }
            result.add(identity + "[" + price + "]");
        }
        return result;
    }

    private static String describeStack(ItemStack stack) {
        Map<String, Object> evidence = CoiItemIdentity.evidence(stack);
        StringBuilder identity = new StringBuilder()
                .append(stack.getType().name().toLowerCase(Locale.ROOT)).append('x').append(stack.getAmount());
        if (evidence.containsKey("item_uuid")) identity.append("{item_uuid=").append(evidence.get("item_uuid"));
        if (evidence.containsKey("parent_item_uuid")) {
            identity.append(evidence.containsKey("item_uuid") ? "," : "{")
                    .append("parent_item_uuid=").append(evidence.get("parent_item_uuid"));
        }
        if (evidence.containsKey("item_uuid") || evidence.containsKey("parent_item_uuid")) identity.append('}');
        return identity.toString();
    }

    @SuppressWarnings("unchecked")
    public void load() {
        items.clear();
        if (!shopFile.exists()) return;
        try (FileReader fr = new FileReader(shopFile)) {
            Type listType = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> list = gson.fromJson(fr, listType);
            if (list == null) return;
            for (Map<String, Object> entry : list) {
                try {
                    UUID id = UUID.fromString((String) entry.get("id"));

                    Map<ResourceType, Double> prices = new EnumMap<>(ResourceType.class);
                    Map<String, Double> rawPrices = (Map<String, Double>) entry.get("prices");
                    if (rawPrices != null) {
                        for (Map.Entry<String, Double> p : rawPrices.entrySet()) {
                            try { prices.put(ResourceType.valueOf(p.getKey()), p.getValue()); }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }

                    if (entry.containsKey("coiItemId")) {
                        String coiItemId = (String) entry.get("coiItemId");
                        items.add(new ShopItem(id, coiItemId, prices));
                    } else {
                        byte[] bytes = Base64.getDecoder().decode((String) entry.get("item"));
                        ItemStack item = ItemStack.deserializeBytes(bytes);
                        items.add(new ShopItem(id, item, prices));
                    }
                } catch (Exception e) {
                    plugin.log("Skipping corrupt shop entry: " + e.getMessage());
                }
            }
            plugin.log("Loaded " + items.size() + " shop item(s)");
        } catch (IOException e) {
            plugin.log("Failed to load zone shop: " + e.getMessage());
        }
    }
}
