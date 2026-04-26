package net.mysterria.cosmos.domain.market.model;

import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.CoiItemResolver;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShopItem {

    private final UUID id;
    @Nullable private ItemStack item;          // null for COI items
    @Nullable private final String coiItemId;  // null for vanilla items
    private final Map<ResourceType, Double> prices;

    /** Vanilla (non-COI) item. */
    public ShopItem(UUID id, ItemStack item, Map<ResourceType, Double> prices) {
        this.id         = id;
        this.item       = item.clone();
        this.coiItemId  = null;
        this.prices     = new EnumMap<>(ResourceType.class);
        this.prices.putAll(prices);
    }

    /** COI item identified by its raw ID string (e.g. {@code "char-fool-9"}). */
    public ShopItem(UUID id, String coiItemId, Map<ResourceType, Double> prices) {
        this.id         = id;
        this.item       = null;
        this.coiItemId  = coiItemId;
        this.prices     = new EnumMap<>(ResourceType.class);
        this.prices.putAll(prices);
    }

    public UUID getId() { return id; }

    public boolean isCoi() { return coiItemId != null; }

    @Nullable
    public String getCoiItemId() { return coiItemId; }

    /**
     * Returns the display / primary item.
     * For multi-item COI bundles (ingredients) this is the first ingredient.
     */
    public ItemStack getItem() {
        if (coiItemId != null) {
            if (CoiItemResolver.isMultiItem(coiItemId)) {
                List<ItemStack> all = CoiItemResolver.resolveItems(coiItemId);
                return all.isEmpty() ? new ItemStack(Material.CHEST) : all.get(0);
            }
            ItemStack resolved = CoiItemResolver.resolveItem(coiItemId);
            return resolved != null ? resolved : new ItemStack(Material.BARRIER);
        }
        return item != null ? item.clone() : new ItemStack(Material.AIR);
    }

    /**
     * Returns all items granted on purchase.
     * Vanilla items always return a singleton list.
     */
    public List<ItemStack> getItems() {
        if (coiItemId != null) {
            return CoiItemResolver.resolveItems(coiItemId);
        }
        return item != null ? List.of(item.clone()) : List.of();
    }

    public void setItem(ItemStack item) {
        if (coiItemId != null) return;
        this.item = item.clone();
    }

    public Map<ResourceType, Double> getPrices() { return prices; }

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
