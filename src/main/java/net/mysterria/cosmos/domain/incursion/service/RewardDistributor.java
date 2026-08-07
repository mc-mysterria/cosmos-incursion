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

        List<Integer> ranked = rawScores.keySet().stream()
                .sorted(Comparator
                        .comparingDouble((Integer id) -> rawScores.get(id)).reversed()
                        .thenComparing(Comparator.comparingLong((Integer id) -> contestedTotals.getOrDefault(id, 0L)).reversed())
                        .thenComparing(Comparator.comparingInt((Integer id) -> captureTotals.getOrDefault(id, 0)).reversed()))
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

        int dethronerTownId = applyHolderStreakAndReturnDethroner(standings);
        applyPodiumBuffs(standings, config);

        Map<ResourceType, Double> pool = computeResourcePool(event, config);
        payoutResources(standings, pool, config, dethronerTownId);

        List<PlayerContribution> mvps = payoutMvps(config);

        recordHistory(standings, mvps);
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

    private void payoutResources(List<TownScore> standings, Map<ResourceType, Double> pool,
                                 CosmosConfig config, int dethronerTownId) {
        if (pool.isEmpty()) return;

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
        }
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

    private List<PlayerContribution> payoutMvps(CosmosConfig config) {
        List<PlayerContribution> mvps = plugin.getContributionTracker().top(config.getMvpCount());

        for (PlayerContribution mvp : mvps) {
            Player player = Bukkit.getPlayer(mvp.playerId());
            if (player == null || !player.isOnline()) continue;

            if (config.getMvpActingEffort() > 0) {
                CoiToolkit.grantActingEffort(player, CoiToolkit.SOURCE_WORLD_CONTENT, config.getMvpActingEffort());
            }
            if (config.getMvpCommand() != null && !config.getMvpCommand().isBlank()) {
                String command = config.getMvpCommand().replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }

            player.sendMessage(Component.text("[Cosmos Incursion] ", NamedTextColor.GOLD)
                    .append(Component.text("You were an MVP of the incursion!", NamedTextColor.GREEN)));
        }

        return mvps;
    }

    // ── History + announcement ───────────────────────────────────────────────────

    private void recordHistory(List<TownScore> standings, List<PlayerContribution> mvps) {
        PlayerContribution topMvp = mvps.isEmpty() ? null : mvps.get(0);
        EventResult result = new EventResult(
                System.currentTimeMillis(),
                standings,
                topMvp != null ? topMvp.playerId() : null,
                topMvp != null ? topMvp.playerName() : null);
        plugin.getEventHistoryStore().recordResult(result);
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
