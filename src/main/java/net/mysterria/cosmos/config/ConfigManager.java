package net.mysterria.cosmos.config;

import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigManager {

    private final CosmosIncursion plugin;
    private CosmosConfig config;

    public ConfigManager(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.config = new CosmosConfig();
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration fileConfig = plugin.getConfig();

        // Event settings
        config.setMinPlayers(fileConfig.getInt("event.min-players", 30));
        config.setCooldownMinutes(fileConfig.getInt("event.cooldown-minutes", 120));
        config.setDurationMinutes(fileConfig.getInt("event.duration-minutes", 30));
        config.setCountdownSeconds(fileConfig.getInt("event.countdown-seconds", 60));

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
        config.setCrateCommand(fileConfig.getString("death.crate-command", "crate give cosmos %player% 1"));

        // Combat logging
        config.setNpcDurationMinutes(fileConfig.getInt("combat-log.npc-duration-minutes", 5));
        config.setNpcNameFormat(fileConfig.getString("combat-log.npc-name-format", "Hollow Body of %player%"));

        // Beacons
        config.setBeaconCaptureRadius(fileConfig.getDouble("beacons.capture-radius", 20.0));
        config.setBeaconCapturePoints(fileConfig.getDouble("beacons.capture-points", 100.0));
        config.setPointsPerPlayer(fileConfig.getDouble("beacons.points-per-player", 1.0));
        config.setDecayRate(fileConfig.getDouble("beacons.decay-rate", 0.5));

        // Rewards
        config.setActingSpeedBonus(fileConfig.getDouble("rewards.acting-speed-bonus", 1.10));
        config.setBuffDurationHours(fileConfig.getInt("rewards.buff-duration-hours", 24));

        // Messages
        config.setMsgEventStarting(fileConfig.getString("messages.event-starting",
                "<red>[Cosmos Incursion]</red> <white>An incursion begins in %countdown% seconds!</white>"));
        config.setMsgEventStarted(fileConfig.getString("messages.event-started",
                "<red>[Cosmos Incursion]</red> <white>The incursion has begun! %zones% zones active.</white>"));
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

    public CosmosConfig getConfig() {
        return config;
    }

}
