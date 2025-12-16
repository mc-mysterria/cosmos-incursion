package net.mysterria.cosmos.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CosmosConfig {

    // Event settings
    private int minPlayers = 30;
    private int cooldownMinutes = 120;
    private int durationMinutes = 30;
    private int countdownSeconds = 60;

    // Zone settings
    private int zoneBaseCount = 2;
    private int playersPerZone = 20;
    private int zoneMaxCount = 5;
    private double zoneRadius = 150.0;
    private double townBuffer = 50.0;
    private double minZoneSeparation = 500.0;

    // Spirit Weight (high-tier penalties)
    private int spiritWeightMinSequence = 4;
    private int spiritWeightMaxSequence = 5;
    private double dotDamage = 1.0;
    private int dotIntervalTicks = 100;

    // Anti-griefing
    private int griefKillThreshold = 3;
    private int griefTimeWindowSeconds = 600;
    private int griefSequenceDifference = 3;
    private int corruptedDurationMinutes = 15;

    // Death system
    private int regressionSequence = 4;
    private String crateCommand = "crate give cosmos %player% 1";

    // Combat logging
    private int npcDurationMinutes = 5;
    private String npcNameFormat = "Hollow Body of %player%";

    // Beacons
    private double beaconCaptureRadius = 20.0;
    private double beaconCapturePoints = 100.0;
    private double pointsPerPlayer = 1.0;
    private double decayRate = 0.5;

    // Beacon UI/UX
    private boolean beaconUIEnabled = true;
    private boolean beaconSoundsEnabled = true;
    private boolean beaconParticlesEnabled = true;
    private boolean beaconPhysicalEnabled = true;
    private double beaconUIRadius = 30.0;
    private int beaconScoreboardUpdateTicks = 40;  // 2 seconds
    private int beaconParticleUpdateTicks = 10;  // 0.5 seconds

    // Zone boundary particles
    private boolean zoneBoundaryParticlesEnabled = true;
    private int zoneBoundaryParticleUpdateTicks = 40;  // 2 seconds
    private double zoneBoundaryParticleViewDistance = 50.0;  // Distance from boundary to show particles

    // Rewards
    private double actingSpeedBonus = 1.10;
    private int buffDurationHours = 24;

    // Messages
    private String msgEventStarting = "<red>[Cosmos Incursion]</red> <white>An incursion begins in %countdown% seconds!</white>";
    private String msgEventStarted = "<red>[Cosmos Incursion]</red> <white>The incursion has begun! %zones% zones active.</white>";
    private String msgEventTimeRemaining = "<red>[Cosmos Incursion]</red> <yellow>%minutes% minutes remaining!</yellow> <gray>Contest the beacons while you can!</gray>";
    private String msgEventEnding = "<red>[Cosmos Incursion]</red> <white>The incursion is ending...</white>";
    private String msgEventEnded = "<red>[Cosmos Incursion]</red> <white>The incursion has concluded.</white>";
    private String msgZoneEntry = "<red>The barrier of reality dissolves...</red>";
    private String msgZoneExit = "<green>You have left the incursion zone.</green>";
    private String msgCorruptedMonster = "<dark_red>%player% has become a Corrupted Monster!</dark_red>";
    private String msgDeathRegression = "<red>Your sequence has regressed due to death in the incursion.</red>";
    private String msgPaperAngelSaved = "<green>Your Paper Angel has protected you from regression!</green>";

}
