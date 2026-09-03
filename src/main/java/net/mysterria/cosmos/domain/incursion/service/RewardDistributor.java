package net.mysterria.cosmos.domain.incursion.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.beacon.model.BeaconCapture;
import net.mysterria.cosmos.domain.beacon.service.BeaconManager;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.incursion.model.IncursionEvent;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import net.mysterria.cosmos.domain.incursion.model.EventResult;
import net.mysterria.cosmos.domain.incursion.model.PlayerContribution;
import net.mysterria.cosmos.domain.incursion.model.TownScore;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Replaces the old winner-take-all payout ({@code EventManager} used to hand the entire reward
 * to whichever single town {@code BeaconManager.getWinningTown()} named). Ranks every town that
 * contested a beacon, pays proportionally to contribution among those above a score floor, keeps
 * the Acting Speed buff scarce and rank-tiered, amplifies (never grants) the resource share of
 * towns whose Nation allies also pulled their weight, and pays MVPs independent of town placement.
 */
public class RewardDistributor {

    private final CosmosIncursion plugin;

    public RewardDistributor(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /**
     * Ranks towns by contribution, grants podium buffs and proportional resources, pays MVPs,
     * records history, and announces the result. Returns the full standings (including
     * non-qualifying towns) for callers that want them; empty if nobody contested a beacon.
     */
    public List<TownScore> distribute(IncursionEvent event) {
        CosmosConfig config = config();
        BeaconManager beaconManager = plugin.getBeaconManager();

        Map<Integer, Double> rawScores = computeRawScores(beaconManager, config);
        if (rawScores.isEmpty()) {
            plugin.log("No town contested a beacon this event — no rewards distributed");
            return List.of();
        }

        Map<Integer, Long> contestedTotals = computeContestedTotals(beaconManager);
        Map<Integer, Integer> captureTotals = computeCaptureTotals(beaconManager);

        double topScore = Collections.max(rawScores.values());
        double floor = topScore * config.getMinScoreShare();
        double totalQualifyingScore = rawScores.values().stream()
                .filter(score -> score >= floor)
                .mapToDouble(Double::doubleValue)
                .sum();

        // Final tie-break on town id (ascending) makes an exact tie on every other metric
        // deterministic and reproducible, instead of falling back to HashMap iteration order.
        List<Integer> ranked = rawScores.keySet().stream()
                .sorted(Comparator
                        .comparingDouble((Integer id) -> rawScores.get(id)).reversed()
                        .thenComparing(Comparator.comparingLong((Integer id) -> contestedTotals.getOrDefault(id, 0L)).reversed())
                        .thenComparing(Comparator.comparingInt((Integer id) -> captureTotals.getOrDefault(id, 0)).reversed())
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        List<TownScore> standings = new ArrayList<>();
        int rank = 0;
        for (int townId : ranked) {
            rank++;
            double score = rawScores.get(townId);
            boolean qualified = score >= floor;
            Optional<TownData> townOpt = TownsToolkit.getTownById(townId);
            String townName = townOpt.map(TownData::name).orElse("Unknown Town #" + townId);
            int nationId = townOpt.map(TownData::nationId).orElse(TownData.NO_NATION);
            String nationName = townOpt.map(TownData::nationName).orElse(null);
            double share = (qualified && totalQualifyingScore > 0) ? score / totalQualifyingScore : 0.0;

            standings.add(new TownScore(townId, townName, score, share, rank, qualified, nationId, nationName));
        }

        int previousHolderId = plugin.getEventHistoryStore().getHolderTownId();
        String previousHolderName = plugin.getEventHistoryStore().getHolderTownName();
        int previousHolderStreak = plugin.getEventHistoryStore().getHolderStreak();
        TownScore winner = standings.stream()
                .filter(town -> town.rank() == 1 && town.qualified())
                .findFirst().orElse(null);

        int dethronerTownId = applyHolderStreakAndReturnDethroner(standings);
        applyPodiumBuffs(standings, config);

        Map<ResourceType, Double> pool = computeResourcePool(event, config);
        payoutResources(event, standings, pool, config, dethronerTownId);

        List<PlayerContribution> mvps = payoutMvps(event, config);

        boolean historySaved = recordHistory(standings, mvps);
        if (winner != null) {
            AuditOutcome historyOutcome = historySaved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED;
            AuditRisk historyRisk = historySaved ? AuditRisk.NORMAL : AuditRisk.HIGH;
            String historyFailure = historySaved ? null : "history_persistence_failed";
            Map<String, Object> winnerMetadata = new java.util.LinkedHashMap<>();
            winnerMetadata.put("town_id", winner.townId());
            winnerMetadata.put("town_name", winner.townName() == null ? "" : winner.townName());
            winnerMetadata.put("score", winner.score());
            winnerMetadata.put("share", winner.share());
            winnerMetadata.put("rank", winner.rank());
            winnerMetadata.put("qualified", winner.qualified());
            MysterriaAuditEmitter.emit(plugin, "incursion.winner", historyOutcome,
                    historyRisk, event.getEventId(), event.getEventId() + ".winner", null, null, null,
                    historyFailure, winnerMetadata);

            int holderTownId = plugin.getEventHistoryStore().getHolderTownId();
            int holderStreak = plugin.getEventHistoryStore().getHolderStreak();
            if (previousHolderId != holderTownId || previousHolderStreak != holderStreak) {
                Map<String, Object> holderMetadata = new java.util.LinkedHashMap<>();
                holderMetadata.put("previous_town_id", previousHolderId);
                holderMetadata.put("previous_town_name", previousHolderName == null ? "" : previousHolderName);
                holderMetadata.put("previous_streak", previousHolderStreak);
                holderMetadata.put("town_id", holderTownId);
                holderMetadata.put("town_name", plugin.getEventHistoryStore().getHolderTownName() == null
                        ? "" : plugin.getEventHistoryStore().getHolderTownName());
                holderMetadata.put("streak", holderStreak);
                String holderChange = previousHolderId == 0 ? "new_holder"
                        : (previousHolderId == holderTownId ? "holder_defended" : "holder_dethroned");
                holderMetadata.put("holder_change", holderChange);
                MysterriaAuditEmitter.emit(plugin, "incursion.holder_changed", historyOutcome,
                        historyRisk, event.getEventId(), event.getEventId() + ".holder", null, null, null,
                        historySaved ? holderChange : historyFailure, holderMetadata);
            }
        }
        broadcastStandings(standings, mvps);
        plugin.getDiscordToolkit().sendEventResults(standings, mvps);

        return standings;
    }

    // ── Scoring ──────────────────────────────────────────────────────────────────

    private Map<Integer, Double> computeRawScores(BeaconManager beaconManager, CosmosConfig config) {
        Map<Integer, Double> scores = new HashMap<>();
        for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
            double tierWeight = tierWeight(config, capture);
            for (int townId : capture.getInvolvedTownIds()) {
                double townScore = capture.getOwnershipSeconds(townId) * config.getContributionHoldWeight()
                        + capture.getContestedSeconds(townId) * config.getContributionContestedHoldWeight()
                        + capture.getCaptures(townId) * config.getContributionCaptureWeight();
                scores.merge(townId, townScore * tierWeight, Double::sum);
            }
        }
        return scores;
    }

    private Map<Integer, Long> computeContestedTotals(BeaconManager beaconManager) {
        Map<Integer, Long> totals = new HashMap<>();
        for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
            for (int townId : capture.getInvolvedTownIds()) {
                totals.merge(townId, capture.getContestedSeconds(townId), Long::sum);
            }
        }
        return totals;
    }

    private Map<Integer, Integer> computeCaptureTotals(BeaconManager beaconManager) {
        Map<Integer, Integer> totals = new HashMap<>();
        for (BeaconCapture capture : beaconManager.getAllCaptureStates()) {
            for (int townId : capture.getInvolvedTownIds()) {
                totals.merge(townId, capture.getCaptures(townId), Integer::sum);
            }
        }
        return totals;
    }

    private double tierWeight(CosmosConfig config, BeaconCapture capture) {
        return config.getContributionTierWeights().getOrDefault(capture.getBeacon().tier(), 1.0);
    }

    // ── Podium buffs + holder streak ─────────────────────────────────────────────

    /**
     * Advances the holder streak based on this event's rank-1 town and returns the town ID that
     * dethroned a defending holder (streak >= 2) this event, or 0 if there was no dethrone.
     * Leaves the holder untouched if nobody qualified this event.
     */
    private int applyHolderStreakAndReturnDethroner(List<TownScore> standings) {
        TownScore rank1 = standings.stream().filter(t -> t.rank() == 1 && t.qualified()).findFirst().orElse(null);
        if (rank1 == null) {
            return 0;
        }

        EventHistoryStore history = plugin.getEventHistoryStore();
        int previousHolderId = history.getHolderTownId();
        int previousStreak = history.getHolderStreak();

        if (rank1.townId() == previousHolderId) {
            history.setHolder(rank1.townId(), rank1.townName(), previousStreak + 1);
            return 0;
        }

        boolean dethroned = previousHolderId != TownData.NO_OWNER && previousStreak >= 2;
        history.setHolder(rank1.townId(), rank1.townName(), 1);
        return dethroned ? rank1.townId() : 0;
    }

    private void applyPodiumBuffs(List<TownScore> standings, CosmosConfig config) {
        int streak = plugin.getEventHistoryStore().getHolderStreak();

        for (TownScore town : standings) {
            if (!town.qualified()) continue;
            CosmosConfig.PodiumRank podium = config.getPodiumRanks().get(town.rank());
            if (podium == null) continue;

            double bonus = podium.actingSpeedBonus();
            if (town.rank() == 1) {
                double streakBonus = Math.min(
                        config.getHolderStreakBonusPerWin() * Math.max(0, streak - 1),
                        config.getHolderMaxStreakBonus());
                bonus += streakBonus;
            }

            plugin.getBuffToolkit().awardBuff(town.townId(), bonus, podium.durationHours());
        }
    }

    // ── Resource payout ──────────────────────────────────────────────────────────

    private Map<ResourceType, Double> computeResourcePool(IncursionEvent event, CosmosConfig config) {
        Map<ZoneTier, Map<ResourceType, Double>> rewardsByTier = config.getEventWinnerResourcesByTier();
        Map<ResourceType, Double> total = new EnumMap<>(ResourceType.class);
        for (IncursionZone zone : event.getIncursionZones()) {
            Map<ResourceType, Double> tierReward = rewardsByTier.get(zone.getTier());
            if (tierReward != null) {
                tierReward.forEach((type, amount) -> total.merge(type, amount, Double::sum));
            }
        }
        return total;
    }

    private void payoutResources(IncursionEvent event, List<TownScore> standings, Map<ResourceType, Double> pool,
                                 CosmosConfig config, int dethronerTownId) {
        if (pool.isEmpty()) return;

        List<Map<String, Object>> committedPayouts = new ArrayList<>();
        Map<String, Double> resourcePool = resourceAmounts(pool);
        for (TownScore town : standings) {
            if (!town.qualified() || town.share() <= 0) continue;

            double multiplier = nationMultiplier(town, standings, config);
            if (town.townId() == dethronerTownId) {
                multiplier *= config.getHolderDethroneResourceMultiplier();
            }

            double effectiveShare = town.share() * multiplier;
            Map<ResourceType, Double> payout = new EnumMap<>(ResourceType.class);
            pool.forEach((type, amount) -> payout.put(type, amount * effectiveShare));

            plugin.getPermanentZoneManager().depositToTown(town.townId(), payout);
            plugin.log(String.format(
                    "Deposited event resources to %s (rank %d, share %.1f%%, multiplier %.2fx): %s",
                    town.townName(), town.rank(), town.share() * 100, multiplier, payout));

            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("town_id", town.townId());
            metadata.put("town_name", town.townName());
            metadata.put("rank", town.rank());
            metadata.put("share", town.share());
            metadata.put("multiplier", multiplier);
            metadata.put("resource_pool", resourcePool);
            metadata.put("payout", resourceAmounts(payout));
            committedPayouts.add(metadata);
        }

        boolean balancesSaved = plugin.getPermanentZoneManager().saveBalances();
        for (Map<String, Object> metadata : committedPayouts) {
            int townId = ((Number) metadata.get("town_id")).intValue();
            MysterriaAuditEmitter.emit(plugin, "incursion.reward_granted",
                    balancesSaved ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                    balancesSaved ? AuditRisk.NORMAL : AuditRisk.HIGH,
                    event.getEventId(), event.getEventId() + ".reward." + townId,
                    null, null, null, balancesSaved ? null : "balance_persistence_failed", metadata);
        }
    }

    private Map<String, Double> resourceAmounts(Map<ResourceType, Double> values) {
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        values.forEach((type, amount) -> result.put(type.configKey(), amount));
        return result;
    }

    private double nationMultiplier(TownScore town, List<TownScore> standings, CosmosConfig config) {
        if (!config.isNationBonusEnabled() || town.nationId() == TownData.NO_NATION) {
            return 1.0;
        }
        long participatingAllies = standings.stream()
                .filter(TownScore::qualified)
                .filter(other -> other.nationId() == town.nationId())
                .count();
        double multiplier = 1.0 + config.getNationBonusPerExtraTown() * Math.max(0, participatingAllies - 1);
        return Math.min(multiplier, config.getNationMaxMultiplier());
    }

    // ── MVP payouts ──────────────────────────────────────────────────────────────

    /**
     * Pays MVP rewards to online top contributors immediately. A contributor who's offline at
     * distribution time is still named as MVP everywhere (broadcast/Discord/history), but their
     * acting-effort reward is queued in {@link EventHistoryStore} and paid via the normal online
     * grant path — see {@link #grantPendingMvpReward} — the next time they join, rather than
     * through COI's cruder, unscaled offline API.
     */
    private List<PlayerContribution> payoutMvps(IncursionEvent event, CosmosConfig config) {
        List<PlayerContribution> mvps = plugin.getContributionTracker().top(config.getMvpCount());

        for (int index = 0; index < mvps.size(); index++) {
            PlayerContribution mvp = mvps.get(index);
            Player player = Bukkit.getPlayer(mvp.playerId());
            boolean online = player != null && player.isOnline();
            Map<String, Object> resultMetadata = new java.util.LinkedHashMap<>();
            resultMetadata.put("player_name", mvp.playerName());
            resultMetadata.put("score", mvp.score());
            resultMetadata.put("rank", index + 1);
            resultMetadata.put("online", online);
            resultMetadata.put("acting_effort", config.getMvpActingEffort());
            MysterriaAuditEmitter.emit(plugin, "incursion.mvp.result", AuditOutcome.OBSERVED,
                    AuditRisk.NORMAL, event.getEventId(), event.getEventId() + ".mvp." + mvp.playerId(),
                    mvp.playerId(), mvp.playerId(), null, null, resultMetadata);

            if (!online) {
                if (config.getMvpActingEffort() > 0) {
                    boolean queued = plugin.getEventHistoryStore().queuePendingMvpReward(
                            mvp.playerId(), event.getEventId(), config.getMvpActingEffort());
                    MysterriaAuditEmitter.emit(plugin, "incursion.mvp.reward_pending",
                            queued ? AuditOutcome.COMMITTED : AuditOutcome.FAILED,
                            queued ? AuditRisk.NORMAL : AuditRisk.HIGH,
                            event.getEventId(), event.getEventId() + ".mvp-reward." + mvp.playerId(),
                            null, mvp.playerId(), null,
                            queued ? "player_offline" : "pending_reward_persistence_failed",
                            Map.of("acting_effort", config.getMvpActingEffort()));
                }
                continue;
            }

            int grantedActingPoints = 0;
            if (config.getMvpActingEffort() > 0) {
                grantedActingPoints = CoiToolkit.grantActingEffort(
                        player, CoiToolkit.SOURCE_WORLD_CONTENT, config.getMvpActingEffort());
            }
            boolean commandApplied = true;
            if (config.getMvpCommand() != null && !config.getMvpCommand().isBlank()) {
                String command = config.getMvpCommand().replace("%player%", player.getName());
                commandApplied = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }

            player.sendMessage(Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                    .append(Component.text("You were an MVP of the incursion!", NamedTextColor.GREEN)));

            boolean effortApplied = config.getMvpActingEffort() <= 0 || grantedActingPoints > 0;
            AuditOutcome rewardOutcome = effortApplied && commandApplied
                    ? AuditOutcome.COMMITTED : AuditOutcome.FAILED;
            String failureReason = !effortApplied ? "acting_effort_not_granted"
                    : (!commandApplied ? "reward_command_failed" : null);
            MysterriaAuditEmitter.emit(plugin, "incursion.mvp.reward_granted", rewardOutcome,
                    rewardOutcome == AuditOutcome.COMMITTED ? AuditRisk.NORMAL : AuditRisk.HIGH,
                    event.getEventId(), event.getEventId() + ".mvp-reward." + mvp.playerId(),
                    player.getUniqueId(), player.getUniqueId(), null, failureReason,
                    Map.of("acting_effort", config.getMvpActingEffort(),
                            "acting_points_granted", grantedActingPoints,
                            "command_applied", commandApplied, "online", true));
        }

        return mvps;
    }

    /**
     * Grants any MVP acting-effort reward queued for this player while they were offline.
     * Called from {@code PlayerJoinListener}, mirroring how {@code BuffToolkit.handlePlayerJoin}
     * reapplies a buff to a town member who was offline when their town earned it.
     */
    public void grantPendingMvpReward(Player player) {
        List<EventHistoryStore.PendingMvpReward> rewards =
                plugin.getEventHistoryStore().getPendingMvpRewards(player.getUniqueId());
        if (rewards.isEmpty()) return;

        int acknowledged = 0;
        for (EventHistoryStore.PendingMvpReward reward : rewards) {
            UUID correlationId = reward.eventId();
            String businessId = correlationId == null
                    ? "mvp-pending:" + player.getUniqueId()
                    : correlationId + ".mvp-reward." + player.getUniqueId();
            int grantedActingPoints = 0;
            boolean commandApplied = false;
            boolean acknowledgedReward = false;
            String reason;
            try {
                if (reward.effort() > 0) {
                    grantedActingPoints = CoiToolkit.grantActingEffort(
                            player, CoiToolkit.SOURCE_WORLD_CONTENT, reward.effort());
                }
                boolean effortApplied = reward.effort() <= 0 || grantedActingPoints > 0;
                String command = config().getMvpCommand();
                commandApplied = command == null || command.isBlank()
                        || Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
                if (!effortApplied) {
                    reason = "acting_effort_not_granted";
                } else if (!commandApplied) {
                    reason = "reward_command_failed";
                } else {
                    acknowledgedReward = plugin.getEventHistoryStore()
                            .acknowledgePendingMvpReward(player.getUniqueId(), reward);
                    reason = acknowledgedReward ? "pending_reward_join" : "pending_ack_persistence_failed";
                }
            } catch (RuntimeException failure) {
                plugin.log("Failed to process queued MVP reward for " + player.getName()
                        + ": " + failure.getClass().getSimpleName());
                reason = "pending_reward_processing_failed";
            }
            if (acknowledgedReward) acknowledged++;
            AuditOutcome outcome = acknowledgedReward ? AuditOutcome.COMMITTED : AuditOutcome.FAILED;
            MysterriaAuditEmitter.emit(plugin, "incursion.mvp.reward_granted", outcome,
                    acknowledgedReward ? AuditRisk.NORMAL : AuditRisk.HIGH,
                    correlationId, businessId, player.getUniqueId(), player.getUniqueId(), null, reason,
                    Map.of("acting_effort", reward.effort(),
                            "acting_points_granted", grantedActingPoints,
                            "command_applied", commandApplied, "online", true));
        }

        if (acknowledged > 0) {
            player.sendMessage(Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                    .append(Component.text("You were an MVP of a recent incursion — reward processed!", NamedTextColor.GREEN)));
        }
        plugin.log("Processed " + acknowledged + " of " + rewards.size()
                + " queued MVP reward(s) for " + player.getName() + " on join");
    }

    // ── History + announcement ───────────────────────────────────────────────────

    private boolean recordHistory(List<TownScore> standings, List<PlayerContribution> mvps) {
        PlayerContribution topMvp = mvps.isEmpty() ? null : mvps.get(0);
        EventResult result = new EventResult(
                System.currentTimeMillis(),
                standings,
                topMvp != null ? topMvp.playerId() : null,
                topMvp != null ? topMvp.playerName() : null);
        return plugin.getEventHistoryStore().recordResult(result);
    }

    private void broadcastStandings(List<TownScore> standings, List<PlayerContribution> mvps) {
        Bukkit.getServer().sendMessage(Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                .append(Component.text("Incursion results:", NamedTextColor.WHITE)));

        int shown = 0;
        for (TownScore town : standings) {
            if (shown >= 5) break;
            NamedTextColor rankColor = switch (town.rank()) {
                case 1 -> NamedTextColor.GOLD;
                case 2 -> NamedTextColor.GRAY;
                case 3 -> NamedTextColor.YELLOW;
                default -> NamedTextColor.WHITE;
            };
            String status = town.qualified()
                    ? String.format("%.0f%% share", town.share() * 100)
                    : "no reward (below contribution floor)";
            Bukkit.getServer().sendMessage(Component.text("  #" + town.rank() + " ", rankColor)
                    .append(Component.text(town.townName() + " ", NamedTextColor.WHITE))
                    .append(Component.text("(" + status + ")", NamedTextColor.GRAY)));
            shown++;
        }

        if (!mvps.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < mvps.size(); i++) {
                if (i > 0) names.append(", ");
                names.append(mvps.get(i).playerName());
            }
            Bukkit.getServer().sendMessage(Component.text("  MVPs: ", NamedTextColor.YELLOW)
                    .append(Component.text(names.toString(), NamedTextColor.WHITE)));
        }
    }

    private CosmosConfig config() {
        return plugin.getConfigLoader().getConfig();
    }

}
