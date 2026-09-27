package net.mysterria.cosmos.toolkit.item;

import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads CoI's physical item identity ({@code circleofimagination:item_uuid} and
 * {@code circleofimagination:item_parent}) from an item's PDC through string keys, so Cosmos has
 * no hard dependency on CoI. Used for audit evidence only.
 */
public final class CoiItemIdentity {
    private static final NamespacedKey ITEM_UUID = new NamespacedKey("circleofimagination", "item_uuid");
    private static final NamespacedKey ITEM_PARENT = new NamespacedKey("circleofimagination", "item_parent");

    private CoiItemIdentity() {
    }

    /**
     * Material, amount and, when tagged, {@code item_uuid}/{@code parent_item_uuid}. Never throws:
     * a failing read records an audit failure and returns what was captured so far.
     */
    public static Map<String, Object> evidence(ItemStack item) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        if (item == null) return evidence;
        try {
            evidence.put("material", item.getType().name().toLowerCase(Locale.ROOT));
            evidence.put("amount", item.getAmount());
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return evidence;
            String itemUuid = meta.getPersistentDataContainer().get(ITEM_UUID, PersistentDataType.STRING);
            if (itemUuid != null && !itemUuid.isBlank()) evidence.put("item_uuid", itemUuid);
            String parent = meta.getPersistentDataContainer().get(ITEM_PARENT, PersistentDataType.STRING);
            if (parent != null && !parent.isBlank()) evidence.put("parent_item_uuid", parent);
        } catch (RuntimeException | LinkageError failure) {
            MysterriaAuditEmitter.recordFailure();
        }
        return evidence;
    }
}
