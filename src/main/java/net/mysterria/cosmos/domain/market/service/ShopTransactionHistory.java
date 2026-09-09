package net.mysterria.cosmos.domain.market.service;

import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Bounded GUI history; canonical persistence belongs to the neutral audit client. */
public class ShopTransactionHistory {

    public record Transaction(String timestamp, String playerName, String townName, String itemName, String priceSummary) {}

    private static final int MAX_PER_TOWN = 50;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<Integer, Deque<Transaction>> history = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Deque<Transaction>> eldest) {
            return size() > 4096;
        }
    };

    public synchronized void record(int townId, String playerName, String townName, String itemName, Map<ResourceType, Double> prices) {
        String timestamp    = LocalDateTime.now().format(FMT);
        String priceSummary = buildPriceSummary(prices);

        Transaction tx = new Transaction(timestamp, playerName, townName, itemName, priceSummary);

        // In-memory
        history.computeIfAbsent(townId, k -> new ArrayDeque<>());
        Deque<Transaction> deque = history.get(townId);
        deque.addFirst(tx);
        if (deque.size() > MAX_PER_TOWN) deque.removeLast();

    }

    public synchronized List<Transaction> getHistory(int townId) {
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
