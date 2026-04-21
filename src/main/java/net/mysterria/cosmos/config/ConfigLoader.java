package net.mysterria.cosmos.config;

import lombok.Getter;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumMap;
import java.util.Map;

public class ConfigLoader {

    private final CosmosIncursion plugin;

    @Getter
    private final CosmosConfig config;

    public ConfigLoader(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = new CosmosConfig();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration fileConfig = plugin.getConfig();

        // Event settings
        config.setEventAutoStart(fileConfig.getBoolean("event.auto-start", true));
        config.setMinPlayers(fileConfig.getInt("event.min-players", 30));
        config.setCooldownMinutes(fileConfig.getInt("event.cooldown-minutes", 120));
        config.setDurationMinutes(fileConfig.getInt("event.duration-minutes", 30));
        config.setCountdownSeconds(fileConfig.getInt("event.countdown-seconds", 60));

        // Zone tier distribution and per-tier settings
        Map<ZoneTier, Integer> distribution = new EnumMap<>(ZoneTier.class);
        Map<ZoneTier, CosmosConfig.ZoneTierConfig> tierConfigs = new EnumMap<>(ZoneTier.class);

        int[] defaultDropChancePercent = {0, 33, 100, 100};   // GREEN, YELLOW, RED, DEATH
        int[][] defaultColors = {
                {0, 220, 0},      // GREEN
                {255, 220, 0},    // YELLOW
                {255, 60, 60},    // RED
                {80, 0, 0}        // DEATH (dark crimson)
        };

        ZoneTier[] tiers = ZoneTier.values();
        for (int i = 0; i < tiers.length; i++) {
            ZoneTier tier = tiers[i];
            String key = tier.configKey();

            distribution.put(tier, fileConfig.getInt("zones.tier-distribution." + key, 1));

            double dropChance = fileConfig.getDouble("zones.tiers." + key + ".drop-chance",
                    defaultDropChancePercent[i] / 100.0);
            String rewardCommand = fileConfig.getString("zones.tiers." + key + ".reward-command", "");
            String colorHex = fileConfig.getString("zones.tiers." + key + ".particle-color", "");
            int r, g, b;
            if (colorHex.length() == 6) {
                r = Integer.parseInt(colorHex.substring(0, 2), 16);
                g = Integer.parseInt(colorHex.substring(2, 4), 16);
                b = Integer.parseInt(colorHex.substring(4, 6), 16);
            } else {
                r = defaultColors[i][0];
                g = defaultColors[i][1];
                b = defaultColors[i][2];
            }

            tierConfigs.put(tier, new CosmosConfig.ZoneTierConfig(dropChance, rewardCommand, r, g, b));
        }

        config.setTierDistribution(distribution);
        config.setTierConfigs(tierConfigs);

        // Zone settings
        config.setZoneBaseCount(fileConfig.getInt("zones.base-count", 2));
        config.setPlayersPerZone(fileConfig.getInt("zones.players-per-zone", 20));
        config.setZoneMaxCount(fileConfig.getInt("zones.max-count", 5));
        config.setZoneRadius(fileConfig.getDouble("zones.radius", 150.0));
        config.setTownBuffer(fileConfig.getDouble("zones.town-buffer", 50.0));
        config.setMinZoneSeparation(fileConfig.getDouble("zones.min-separation", 500.0));

        // Spirit Weight (high-tier penalties)
        config.setSpiritWeightMinSequence(fileConfig.getInt("balancing.spirit-weight.min-sequence", 4));
        config.setSpiritWeightMaxSequence(fileConfig.getInt("balancing.spirit-weight.max-sequence", 5));
        config.setDotDamage(fileConfig.getDouble("balancing.spirit-weight.dot-damage", 1.0));
        config.setDotIntervalTicks(fileConfig.getInt("balancing.spirit-weight.dot-interval-ticks", 100));

        // Anti-griefing
        config.setGriefKillThreshold(fileConfig.getInt("balancing.anti-grief.kill-threshold", 3));
        config.setGriefTimeWindowSeconds(fileConfig.getInt("balancing.anti-grief.time-window-seconds", 600));
        config.setGriefSequenceDifference(fileConfig.getInt("balancing.anti-grief.sequence-difference", 3));
        config.setCorruptedDurationMinutes(fileConfig.getInt("balancing.anti-grief.corrupted-duration-minutes", 15));

        // Death system
        config.setRegressionSequence(fileConfig.getInt("death.regression-sequence", 4));
        config.setRegressionActingRestored(fileConfig.getDouble("death.regression-acting-restored", 0.8));
        config.setRegressionActingPenalty(fileConfig.getDouble("death.regression-acting-penalty", 0.5));
        config.setDeathPenaltyCooldownSeconds(fileConfig.getInt("death.death-penalty-cooldown-seconds", 20));
        config.setCrateCommand(fileConfig.getString("death.crate-command", "crate give cosmos %player% 1"));

        // Combat logging
        config.setNpcDurationMinutes(fileConfig.getInt("combat-log.npc-duration-minutes", 5));
        config.setNpcNameFormat(fileConfig.getString("combat-log.npc-name-format", "Hollow Body of %player%"));

        // Beacons
        config.setBeaconCaptureRadius(fileConfig.getDouble("beacons.capture-radius", 20.0));
        config.setBeaconCapturePoints(fileConfig.getDouble("beacons.capture-points", 100.0));
        config.setPointsPerPlayer(fileConfig.getDouble("beacons.points-per-player", 1.0));
        config.setDecayRate(fileConfig.getDouble("beacons.decay-rate", 0.5));

        // Zone boundary particles
        config.setZoneBoundaryParticlesEnabled(fileConfig.getBoolean("zones.boundary-particles-enabled", true));
        config.setZoneBoundaryParticleUpdateTicks(fileConfig.getInt("zones.boundary-particle-update-ticks", 40));
        config.setZoneBoundaryParticleViewDistance(fileConfig.getDouble("zones.boundary-particle-view-distance", 50.0));

        // Rewards
        config.setActingSpeedBonus(fileConfig.getDouble("rewards.acting-speed-bonus", 1.10));
        config.setBuffDurationHours(fileConfig.getInt("rewards.buff-duration-hours", 24));

        // Permanent zones
        config.setPermanentZonePoiCount(fileConfig.getInt("permanent-zones.poi-count", 3));
        config.setPermanentZonePoiDurationSeconds(fileConfig.getInt("permanent-zones.poi-duration-seconds", 300));
        config.setPermanentZoneExtractionPointCount(fileConfig.getInt("permanent-zones.extraction-point-count", 2));
        config.setPermanentZoneExtractionPointDurationSeconds(fileConfig.getInt("permanent-zones.extraction-point-duration-seconds", 180));
        config.setPermanentZonePoiCaptureRadius(fileConfig.getDouble("permanent-zones.poi-capture-radius", 8.0));
        config.setPermanentZoneExtractionRadius(fileConfig.getDouble("permanent-zones.extraction-radius", 6.0));
        config.setPermanentZoneExtractionRatePerSecond(fileConfig.getDouble("permanent-zones.extraction-rate-per-second", 5.0));
        config.setPermanentZoneExtractionChannelSeconds(fileConfig.getInt("permanent-zones.extraction-channel-seconds", 10));
        config.setPermanentZoneExtractionExitBuffer(fileConfig.getDouble("permanent-zones.extraction-exit-buffer", 15.0));
        config.setPermanentZoneParticlesEnabled(fileConfig.getBoolean("permanent-zones.particles-enabled", true));
        config.setPermanentZoneParticleViewDistance(fileConfig.getDouble("permanent-zones.particle-view-distance", 64.0));
        config.setPermanentZonePoiRespawnMinSeconds(fileConfig.getInt("permanent-zones.poi-respawn-min-seconds", 120));
        config.setPermanentZonePoiRespawnMaxSeconds(fileConfig.getInt("permanent-zones.poi-respawn-max-seconds", 300));

        // Permanent zone tier configuration
        Map<ExclusionZoneTier, CosmosConfig.ExclusionZoneTierConfig> exclusionTierConfigs = new EnumMap<>(ExclusionZoneTier.class);
        double[] defaultExclusionDropChances = {0.0, 0.33, 1.0}; // SAFE, MEDIUM, HARD
        // daily-budget defaults: total units per resource produced by the zone per 24 hours
        double[][] defaultDailyBudget = {
                {30.0,  60.0, 15.0},   // SAFE:   gold, silver, gems
                {60.0, 150.0, 30.0},   // MEDIUM: gold, silver, gems
                {120.0, 300.0, 60.0}   // HARD:   gold, silver, gems
        };
        // poi-cap defaults: max units a single PoI can hold for each resource type
        double[][] defaultPoiCap = {
                {3.0, 6.0, 1.5},   // SAFE
                {6.0, 15.0, 3.0},  // MEDIUM
                {12.0, 30.0, 6.0}  // HARD
        };
        ExclusionZoneTier[] exclusionTiers = ExclusionZoneTier.values();
        for (int i = 0; i < exclusionTiers.length; i++) {
            ExclusionZoneTier tier = exclusionTiers[i];
            String key = tier.configKey();
            double dropChance = fileConfig.getDouble("permanent-zones.tiers." + key + ".drop-chance", defaultExclusionDropChances[i]);

            Map<ResourceType, Double> dailyBudget = new EnumMap<>(ResourceType.class);
            dailyBudget.put(ResourceType.GOLD,   fileConfig.getDouble("permanent-zones.tiers." + key + ".daily-budget.gold",   defaultDailyBudget[i][0]));
            dailyBudget.put(ResourceType.SILVER, fileConfig.getDouble("permanent-zones.tiers." + key + ".daily-budget.silver", defaultDailyBudget[i][1]));
            dailyBudget.put(ResourceType.GEMS,   fileConfig.getDouble("permanent-zones.tiers." + key + ".daily-budget.gems",   defaultDailyBudget[i][2]));

            Map<ResourceType, Double> poiCap = new EnumMap<>(ResourceType.class);
            poiCap.put(ResourceType.GOLD,   fileConfig.getDouble("permanent-zones.tiers." + key + ".poi-cap.gold",   defaultPoiCap[i][0]));
            poiCap.put(ResourceType.SILVER, fileConfig.getDouble("permanent-zones.tiers." + key + ".poi-cap.silver", defaultPoiCap[i][1]));
            poiCap.put(ResourceType.GEMS,   fileConfig.getDouble("permanent-zones.tiers." + key + ".poi-cap.gems",   defaultPoiCap[i][2]));

            exclusionTierConfigs.put(tier, new CosmosConfig.ExclusionZoneTierConfig(dropChance, dailyBudget, poiCap));
        }
        config.setExclusionTierConfigs(exclusionTierConfigs);

        // Resources awarded to the winning town per zone tier conquered in an incursion event.
        // Total reward = sum of these values for each zone that was active in the event.
        double[][] defaultWinnerResources = {
                {10.0, 20.0,  5.0},   // GREEN:  gold, silver, gems
                {25.0, 50.0, 12.0},   // YELLOW: gold, silver, gems
                {50.0, 100.0, 25.0},  // RED:    gold, silver, gems
                {100.0, 200.0, 50.0}  // DEATH:  gold, silver, gems
        };
        Map<ZoneTier, Map<ResourceType, Double>> winnerResourcesByTier = new EnumMap<>(ZoneTier.class);
        ZoneTier[] incursionTiers = ZoneTier.values();
        for (int i = 0; i < incursionTiers.length; i++) {
            ZoneTier tier = incursionTiers[i];
            String key = tier.configKey();
            Map<ResourceType, Double> reward = new EnumMap<>(ResourceType.class);
            reward.put(ResourceType.GOLD,   fileConfig.getDouble("rewards.winner-resources." + key + ".gold",   defaultWinnerResources[i][0]));
            reward.put(ResourceType.SILVER, fileConfig.getDouble("rewards.winner-resources." + key + ".silver", defaultWinnerResources[i][1]));
            reward.put(ResourceType.GEMS,   fileConfig.getDouble("rewards.winner-resources." + key + ".gems",   defaultWinnerResources[i][2]));
            winnerResourcesByTier.put(tier, reward);
        }
        config.setEventWinnerResourcesByTier(winnerResourcesByTier);

        // Messages
        config.setMsgEventStarting(fileConfig.getString("messages.event-starting",
                "<red>[Cosmos Incursion]</red> <white>An incursion begins in %countdown% seconds!</white>"));
        config.setMsgEventStarted(fileConfig.getString("messages.event-started",
                "<red>[Cosmos Incursion]</red> <white>The incursion has begun! %zones% zones active.</white>"));
        config.setMsgEventTimeRemaining(fileConfig.getString("messages.event-time-remaining",
                "<red>[Cosmos Incursion]</red> <yellow>%minutes% minutes remaining!</yellow> <gray>Contest the beacons while you can!</gray>"));
        config.setMsgEventEnding(fileConfig.getString("messages.event-ending",
                "<red>[Cosmos Incursion]</red> <white>The incursion is ending...</white>"));
        config.setMsgEventEnded(fileConfig.getString("messages.event-ended",
                "<red>[Cosmos Incursion]</red> <white>The incursion has concluded.</white>"));
        config.setMsgZoneEntry(fileConfig.getString("messages.zone-entry",
                "<red>The barrier of reality dissolves...</red>"));
        config.setMsgZoneExit(fileConfig.getString("messages.zone-exit",
                "<green>You have left the incursion zone.</green>"));
        config.setMsgCorruptedMonster(fileConfig.getString("messages.corrupted-monster",
                "<dark_red>%player% has become a Corrupted Monster!</dark_red>"));
        config.setMsgDeathRegression(fileConfig.getString("messages.death-regression",
                "<red>Your sequence has regressed due to death in the incursion.</red>"));
        config.setMsgPaperAngelSaved(fileConfig.getString("messages.paper-angel-saved",
                "<green>Your Paper Angel has protected you from regression!</green>"));

        plugin.log("Configuration loaded successfully");
    }

    public void reload() {
        load();
    }

}
