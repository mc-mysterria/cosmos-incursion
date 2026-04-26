package net.mysterria.cosmos.domain.market.service;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.market.model.ShopItem;
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

    public void save() {
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
        } catch (IOException e) {
            plugin.log("Failed to save zone shop: " + e.getMessage());
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
