package net.mysterria.cosmos.domain.market.service;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ShopTransactionLogger {

    public record Transaction(String timestamp, String playerName, String townName, String itemName, String priceSummary) {}

    private static final int MAX_PER_TOWN = 50;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CosmosIncursion plugin;
    private final File logDir;
    private final Map<Integer, Deque<Transaction>> history = new ConcurrentHashMap<>();

    public ShopTransactionLogger(CosmosIncursion plugin) {
        this.plugin  = plugin;
        this.logDir  = new File(plugin.getDataFolder(), "logs");
        //noinspection ResultOfMethodCallIgnored
        this.logDir.mkdirs();
    }

    public void log(int townId, String playerName, String townName, String itemName, Map<ResourceType, Double> prices) {
        String timestamp    = LocalDateTime.now().format(FMT);
        String priceSummary = buildPriceSummary(prices);

        Transaction tx = new Transaction(timestamp, playerName, townName, itemName, priceSummary);

        // In-memory
        history.computeIfAbsent(townId, k -> new ArrayDeque<>());
        Deque<Transaction> deque = history.get(townId);
        deque.addFirst(tx);
        if (deque.size() > MAX_PER_TOWN) deque.removeLast();

        // Console
        plugin.log(String.format("[Shop] %s | %s purchased '%s' for %s from town '%s'",
            timestamp, playerName, itemName, priceSummary, townName));

        // File — one file per town inside /logs/
        String safeName = townName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File townLog = new File(logDir, safeName + ".log");
        try (FileWriter fw = new FileWriter(townLog, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write(String.format("[%s] player=%s item=%s price=%s%n",
                timestamp, playerName, itemName, priceSummary));
        } catch (IOException e) {
            plugin.log("[Shop] Failed to write transaction log: " + e.getMessage());
        }
    }

    public List<Transaction> getHistory(int townId) {
        Deque<Transaction> deque = history.get(townId);
        if (deque == null) return Collections.emptyList();
        return List.copyOf(deque);
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
