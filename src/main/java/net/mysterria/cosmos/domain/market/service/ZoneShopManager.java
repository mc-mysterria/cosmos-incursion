package net.mysterria.cosmos.domain.market.service;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
import org.bukkit.inventory.ItemStack;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public void save() {
        writeItems();
    }

    /**
     * Replaces the catalogue and saves it; returns whether the write succeeded. A failed or
     * throwing save restores the previous catalogue in memory.
     */
    public boolean replaceItemsAndSave(List<ShopItem> newItems) {
        List<ShopItem> before = List.copyOf(items);
        setItems(newItems);
        return saveOrRestore(before);
    }

    /** Appends one item and saves; a failed or throwing save removes it again. */
    public boolean addItemAndSave(ShopItem item) {
        List<ShopItem> before = List.copyOf(items);
        addItem(item);
        return saveOrRestore(before);
    }

    private boolean saveOrRestore(List<ShopItem> beforeItems) {
        boolean saved = false;
        try {
            saved = writeItems();
        } finally {
            if (!saved) setItems(beforeItems);
        }
        return saved;
    }

    /** Writes the catalogue to disk; returns whether the write succeeded. */
    private boolean writeItems() {
        // Write to a temp file and move it over the target so a failed write never truncates the catalogue.
        Path temporary = null;
        try {
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
            String json = gson.toJson(list);
            Path target = shopFile.toPath().toAbsolutePath();
            temporary = Files.createTempFile(target.getParent(), shopFile.getName(), ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers item serialization and Gson rejections (e.g. a NaN price) as well as JsonIOException.
            plugin.log("Failed to save zone shop: " + e.getMessage());
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
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
