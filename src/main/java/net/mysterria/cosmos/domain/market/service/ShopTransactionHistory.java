package net.mysterria.cosmos.domain.market.service;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Bounded per-town shop history shown in the shop GUI. Canonical transaction evidence belongs to
 * the neutral audit client; this copy is persisted to {@code zone-shop-history.yml} only so the
 * GUI view survives restarts. Saves are debounced and written off the main thread.
 */
public class ShopTransactionHistory {

    public record Transaction(String timestamp, String playerName, String townName, String itemName, String priceSummary) {}

    private static final int MAX_PER_TOWN = 50;
    private static final int MAX_TOWNS = 4096;
    private static final long SAVE_DEBOUNCE_TICKS = 100L;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CosmosIncursion plugin;
    private final File historyFile;
    private final Object writeLock = new Object();
    private BukkitTask pendingSave;
    private long snapshotGeneration;
    private long writtenGeneration;

    private final Map<Integer, Deque<Transaction>> history = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Deque<Transaction>> eldest) {
            return size() > MAX_TOWNS;
        }
    };

    public ShopTransactionHistory(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.historyFile = new File(plugin.getDataFolder(), "zone-shop-history.yml");
    }

    public synchronized void record(int townId, String playerName, String townName, String itemName, Map<ResourceType, Double> prices) {
        String timestamp    = LocalDateTime.now().format(FMT);
        String priceSummary = buildPriceSummary(prices);

        Transaction tx = new Transaction(timestamp, playerName, townName, itemName, priceSummary);

        Deque<Transaction> deque = history.computeIfAbsent(townId, k -> new ArrayDeque<>());
        deque.addFirst(tx);
        if (deque.size() > MAX_PER_TOWN) deque.removeLast();
        scheduleSave();
    }

    public synchronized List<Transaction> getHistory(int townId) {
        Deque<Transaction> deque = history.get(townId);
        if (deque == null) return Collections.emptyList();
        return List.copyOf(deque);
    }

    // ── Persistence ─────────────────────────────────────────────────────────────

    /** Loads persisted history; a missing or unreadable file leaves history empty. */
    public synchronized void load() {
        if (!historyFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(historyFile);
        ConfigurationSection towns = yaml.getConfigurationSection("towns");
        if (towns == null) return;
        int loaded = 0;
        for (String key : towns.getKeys(false)) {
            int townId;
            try {
                townId = Integer.parseInt(key);
            } catch (NumberFormatException ignored) {
                continue;
            }
            Deque<Transaction> deque = new ArrayDeque<>();
            for (Map<?, ?> entry : towns.getMapList(key)) {
                if (deque.size() >= MAX_PER_TOWN) break;
                deque.addLast(new Transaction(text(entry, "timestamp"), text(entry, "player"),
                        text(entry, "town"), text(entry, "item"), text(entry, "price")));
            }
            if (!deque.isEmpty()) {
                history.put(townId, deque);
                loaded += deque.size();
            }
        }
        plugin.log("Loaded " + loaded + " zone shop history entries");
    }

    /** Cancels any pending debounced save and writes the current history synchronously. */
    public void flush() {
        String snapshot;
        long generation;
        synchronized (this) {
            if (pendingSave != null) {
                pendingSave.cancel();
                pendingSave = null;
            }
            snapshot = serialize();
            generation = ++snapshotGeneration;
        }
        write(snapshot, generation);
    }

    private void scheduleSave() {
        if (pendingSave != null || !plugin.isEnabled()) return;
        pendingSave = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            String snapshot;
            long generation;
            synchronized (this) {
                pendingSave = null;
                snapshot = serialize();
                generation = ++snapshotGeneration;
            }
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(snapshot, generation));
        }, SAVE_DEBOUNCE_TICKS);
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<Integer, Deque<Transaction>> entry : history.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Transaction tx : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("timestamp", tx.timestamp());
                row.put("player", tx.playerName());
                row.put("town", tx.townName());
                row.put("item", tx.itemName());
                row.put("price", tx.priceSummary());
                rows.add(row);
            }
            yaml.set("towns." + entry.getKey(), rows);
        }
        return yaml.saveToString();
    }

    /** Writes a snapshot unless a newer one has already reached disk. */
    private void write(String contents, long generation) {
        synchronized (writeLock) {
            if (generation <= writtenGeneration) return;
            Path temporary = null;
            try {
                Path target = historyFile.toPath().toAbsolutePath();
                Files.createDirectories(target.getParent());
                temporary = Files.createTempFile(target.getParent(), historyFile.getName(), ".tmp");
                Files.writeString(temporary, contents);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
                writtenGeneration = generation;
            } catch (IOException e) {
                plugin.getLogger().warning("Failed to save zone shop history: " + e.getMessage());
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static String text(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return value == null ? "" : value.toString();
    }

    private String buildPriceSummary(Map<ResourceType, Double> prices) {
        StringJoiner sj = new StringJoiner(", ");
        for (ResourceType rt : ResourceType.values()) {
            double v = prices.getOrDefault(rt, 0.0);
            if (v > 0) sj.add(String.format("%.0f %s", v, rt.displayName()));
        }
        return sj.length() == 0 ? "free" : sj.toString();
    }
}
