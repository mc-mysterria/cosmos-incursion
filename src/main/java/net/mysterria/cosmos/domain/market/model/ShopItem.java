package net.mysterria.cosmos.domain.market.model;

import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

public class ShopItem {

    private final UUID id;
    private ItemStack item;
    private final Map<ResourceType, Double> prices;

    public ShopItem(UUID id, ItemStack item, Map<ResourceType, Double> prices) {
        this.id = id;
        this.item = item.clone();
        this.prices = new EnumMap<>(ResourceType.class);
        this.prices.putAll(prices);
    }

    public UUID getId() {
        return id;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public void setItem(ItemStack item) {
        this.item = item.clone();
    }

    public Map<ResourceType, Double> getPrices() {
        return prices;
    }

    public double getPrice(ResourceType type) {
        return prices.getOrDefault(type, 0.0);
    }

    public void setPrice(ResourceType type, double amount) {
        if (amount <= 0) prices.remove(type);
        else prices.put(type, amount);
    }

    public boolean hasAnyPrice() {
        return prices.values().stream().anyMatch(v -> v > 0);
    }
}
