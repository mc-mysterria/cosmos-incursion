package net.mysterria.cosmos;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.command.GeneralCommand;
import net.mysterria.cosmos.command.ExclusionCommand;
import net.mysterria.cosmos.config.ConfigLoader;
import net.mysterria.cosmos.domain.beacon.listener.BeaconProtectionListener;
import net.mysterria.cosmos.domain.beacon.service.BeaconManager;
import net.mysterria.cosmos.domain.beacon.service.BeaconUIManager;
import net.mysterria.cosmos.domain.combat.listener.PaperAngelListener;
import net.mysterria.cosmos.domain.combat.listener.PlayerDeathListener;
import net.mysterria.cosmos.domain.combat.listener.PlayerJoinListener;
import net.mysterria.cosmos.domain.combat.listener.PlayerQuitListener;
import net.mysterria.cosmos.domain.combat.service.CombatLogHandler;
import net.mysterria.cosmos.domain.combat.service.DeathHandler;
import net.mysterria.cosmos.domain.exclusion.listener.ExclusionZoneListener;
import net.mysterria.cosmos.domain.guide.CosmosGuideGUI;
import net.mysterria.cosmos.domain.exclusion.task.ExtractionTask;
import net.mysterria.cosmos.domain.exclusion.task.PermanentZoneBoundaryParticleTask;
import net.mysterria.cosmos.domain.exclusion.task.PermanentZonePlayerTask;
import net.mysterria.cosmos.domain.exclusion.task.PoIRotationTask;
import net.mysterria.cosmos.domain.exclusion.task.PoIVisualizationTask;
import net.mysterria.cosmos.domain.exclusion.task.ResourceAccumulationTask;
import net.mysterria.cosmos.domain.incursion.task.EventCheckTask;
import net.mysterria.cosmos.domain.incursion.task.ZoneCheckTask;
import net.mysterria.cosmos.toolkit.EffectsToolkit;
import net.mysterria.cosmos.domain.incursion.service.EventManager;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.combat.service.KillTracker;
import net.mysterria.cosmos.domain.incursion.service.PlayerStateManager;
import net.mysterria.cosmos.toolkit.BuffToolkit;
import net.mysterria.cosmos.domain.incursion.service.ZoneManager;
import net.mysterria.cosmos.domain.incursion.gui.ConsentGUI;
import net.mysterria.cosmos.toolkit.CitizensToolkit;
import net.mysterria.cosmos.toolkit.map.impl.BlueMapIntegration;
import net.mysterria.cosmos.toolkit.map.MapIntegration;
import net.mysterria.cosmos.toolkit.map.impl.NoOpMapIntegration;
import net.mysterria.cosmos.toolkit.map.impl.SquareMapIntegration;
import net.william278.husktowns.api.HuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public final class CosmosIncursion extends JavaPlugin {

    @Getter
    private static CosmosIncursion instance;

    // APIs
    private CircleOfImaginationAPI coiAPI;
    private HuskTownsAPI huskTownsAPI;

    // Configuration
    private ConfigLoader configLoader;

    // Core managers
    private ZoneManager zoneManager;
    private BeaconManager beaconManager;
    private PermanentZoneManager permanentZoneManager;
    private EventManager eventManager;
    private PlayerStateManager playerStateManager;
    private KillTracker killTracker;
    private DeathHandler deathHandler;
    private EffectsToolkit effectsToolkit;
    private BuffToolkit buffToolkit;

    // Integrations & handlers
    private MapIntegration mapIntegration;
    private CitizensToolkit citizensToolkit;
    private CombatLogHandler combatLogHandler;

    // UI
    private ConsentGUI consentGUI;
    private BeaconUIManager beaconUIManager;
    private CosmosGuideGUI guideGUI;

    private LiteCommands<CommandSender> liteCommands;

    @Override
    public void onEnable() {
        instance = this;

        log("Enabling Cosmos Incursion...");

        // Load configuration
        log("Loading configuration...");
        configLoader = new ConfigLoader(this);
        configLoader.load();

        // Enable API integrations
        log("Enabling COI API...");
        enableCoiApi();

        log("Enabling HuskTowns API...");
        enableHuskTownsApi();

        // Initialize zone manager
        log("Initializing zone manager...");
        zoneManager = new ZoneManager(this);

        // Initialize beacon manager
        log("Initializing beacon manager...");
        beaconManager = new BeaconManager(this);

        // Initialize player state manager
        log("Initializing player state manager...");
        playerStateManager = new PlayerStateManager(this);

        // Initialize effect manager
        log("Initializing effect manager...");
        effectsToolkit = new EffectsToolkit(this);

        // Initialize beacon UI manager
        log("Initializing beacon UI manager...");
        beaconUIManager = new BeaconUIManager(this, beaconManager);

        // Initialize map integration — prefer BlueMap, fall back to squaremap, then no-op
        log("Initializing map integration...");
        mapIntegration = resolveMapIntegration();
        mapIntegration.initialize();

        // Initialize kill tracker
        log("Initializing kill tracker...");
        killTracker = new KillTracker(this, mapIntegration);

        // Initialize death handler
        log("Initializing death handler...");
        deathHandler = new DeathHandler(this, playerStateManager, killTracker);

        // Initialize Citizens integration (retry mechanism for API initialization)
        log("Initializing Citizens integration...");
        citizensToolkit = new CitizensToolkit(this);
        initializeCitizensWithRetry(0);

        // Initialize combat log handler
        log("Initializing combat log handler...");
        combatLogHandler = new CombatLogHandler(this, playerStateManager, citizensToolkit, killTracker);

        // Initialize buff manager
        log("Initializing buff manager...");
        buffToolkit = new BuffToolkit(this);
        buffToolkit.loadBuffData();

        // Initialize consent GUI
        log("Initializing consent GUI...");
        consentGUI = new ConsentGUI();

        // Initialize guide GUI
        log("Initializing guide GUI...");
        guideGUI = new CosmosGuideGUI(this);

        // Initialize permanent zone manager
        log("Initializing permanent zone manager...");
        permanentZoneManager = new PermanentZoneManager(this);
        permanentZoneManager.loadZones();
        permanentZoneManager.loadBalances();
        permanentZoneManager.cleanupOrphanedDisplayEntities();

        for (PermanentZone zone : permanentZoneManager.getAllZones()) {
            mapIntegration.createPermanentZoneMarker(zone);
            permanentZoneManager.spawnPoIsForZone(zone);
            permanentZoneManager.spawnExtractionPoints(zone);
        }

        // Initialize event manager
        log("Initializing event manager...");
        eventManager = new EventManager(this, zoneManager, beaconManager, buffToolkit, mapIntegration, beaconUIManager);

        // Register commands
        log("Registering commands...");
        registerCommands();

        // Register listeners
        log("Registering listeners...");
        registerListeners();

        // Start tasks
        log("Starting background tasks...");
        startTasks();

        log("Cosmos Incursion enabled!");
    }

    @Override
    public void onDisable() {
        log("Disabling Cosmos Incursion...");

        // Save buff data
        if (buffToolkit != null) {
            buffToolkit.saveBuffData();
        }

        // Save permanent zone data and clean up display entities
        if (permanentZoneManager != null) {
            permanentZoneManager.cleanup();
            permanentZoneManager.saveZones();
            permanentZoneManager.saveBalances();
        }

        // Unregister commands
        if (liteCommands != null) {
            liteCommands.unregister();
        }

        log("Cosmos Incursion disabled!");
    }

    private void registerCommands() {
        this.liteCommands = LiteBukkitFactory.builder(this.getName(), this)
                .commands(new ExclusionCommand(this), new GeneralCommand(this), new ExclusionCommand(this))
                .build();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(this, playerStateManager, killTracker, deathHandler), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(this, combatLogHandler, buffToolkit, beaconUIManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(combatLogHandler, buffToolkit), this);
        getServer().getPluginManager().registerEvents(combatLogHandler, this);
        getServer().getPluginManager().registerEvents(new BeaconProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperAngelListener(this), this);
        getServer().getPluginManager().registerEvents(new ExclusionZoneListener(permanentZoneManager), this);
    }

    private void startTasks() {
        // Event check task - runs every second
        new EventCheckTask(eventManager).runTaskTimer(this, 0L, 20L);

        // Zone check task - runs every 5 ticks (4 times per second) for reliable zone detection
        new ZoneCheckTask(this, zoneManager, playerStateManager, effectsToolkit, eventManager, consentGUI).runTaskTimer(this, 0L, 5L);

        // Hollow Body cleanup task - runs every 30 seconds
        if (citizensToolkit.isAvailable()) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                citizensToolkit.cleanupExpired();
            }, 600L, 600L);  // 30 seconds = 600 ticks
        }

        // Corrupted Monster cleanup task - runs every 60 seconds
        getServer().getScheduler().runTaskTimer(this, () -> {
            killTracker.cleanupExpired();
        }, 1200L, 1200L);  // 60 seconds = 1200 ticks

        // Buff cleanup task - runs every 5 minutes
        getServer().getScheduler().runTaskTimer(this, () -> {
            buffToolkit.cleanupExpired();
        }, 6000L, 6000L);  // 5 minutes = 6000 ticks

        // Permanent zone tasks
        new PermanentZonePlayerTask(permanentZoneManager).runTaskTimer(this, 0L, 5L);
        new ResourceAccumulationTask(this, permanentZoneManager).runTaskTimer(this, 0L, 20L);
        new ExtractionTask(this, permanentZoneManager).runTaskTimer(this, 0L, 20L);
        new PoIRotationTask(permanentZoneManager).runTaskTimer(this, 0L, 20L);
        new PermanentZoneBoundaryParticleTask(this, permanentZoneManager).runTaskTimer(this, 0L, 40L);
        new PoIVisualizationTask(this, permanentZoneManager).runTaskTimer(this, 0L, 5L);
    }

    private void initializeCitizensWithRetry(int attempt) {
        final int maxAttempts = 10;
        final long delayTicks = 20L; // 1 second between attempts

        getServer().getScheduler().runTaskLater(this, () -> {
            boolean success = citizensToolkit.initialize();

            if (!success && attempt < maxAttempts - 1) {
                log("Citizens API not ready yet, retrying in 1 second... (attempt " + (attempt + 2) + "/" + maxAttempts + ")");
                initializeCitizensWithRetry(attempt + 1);
            } else if (!success) {
                log("Failed to initialize Citizens after " + maxAttempts + " attempts - Hollow Body NPCs will be disabled");
            }
        }, delayTicks);
    }

    private MapIntegration resolveMapIntegration() {
        if (getServer().getPluginManager().getPlugin("BlueMap") != null) {
            log("Map: BlueMap detected, using BlueMap integration");
            return new BlueMapIntegration(this);
        }
        if (getServer().getPluginManager().getPlugin("squaremap") != null) {
            log("Map: squaremap detected, using squaremap integration");
            return new SquareMapIntegration(this);
        }
        log("Map: no map plugin found, map markers disabled");
        return new NoOpMapIntegration();
    }

    private void enableHuskTownsApi() {
        Plugin huskTownsPlugin = getServer().getPluginManager().getPlugin("HuskTowns");
        if (huskTownsPlugin == null || !huskTownsPlugin.isEnabled()) {
            log("HuskTowns plugin not found or not enabled, disabling Cosmos Incursion");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {

            HuskTownsAPI api = HuskTownsAPI.getInstance();

            if (api == null) {
                log("HuskTowns API not registered, disabling Cosmos Incursion");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            this.huskTownsAPI = api;
            log("HuskTowns API hooked successfully");
        } catch (Throwable t) {
            log("Failed to hook HuskTowns API: " + t.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enableCoiApi() {
        Plugin coiPlugin = getServer().getPluginManager().getPlugin("CircleOfImagination");
        if (coiPlugin == null || !coiPlugin.isEnabled()) {
            log("CircleOfImagination plugin not found or not enabled, disabling Cosmos Incursion");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            CircleOfImaginationAPI api = Bukkit.getServer().getServicesManager().load(CircleOfImaginationAPI.class);
            if (api == null) {
                log("CircleOfImagination API not registered, disabling Cosmos Incursion");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            this.coiAPI = api;
            log("CircleOfImagination API hooked successfully");
        } catch (Throwable t) {
            log("Failed to hook CircleOfImagination API: " + t.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    public void reloadPlugin() {
        log("Reloading Cosmos Incursion configuration...");

        // Reload configuration
        configLoader.reload();

        log("Configuration reloaded successfully!");
    }

    public void log(String message) {
        Component debugMessage = Component.text("[CI] ").color(NamedTextColor.GOLD).append(Component.text(message).color(NamedTextColor.WHITE));
        Bukkit.getConsoleSender().sendMessage(debugMessage);
    }

    public NamespacedKey getKey(String key) {
        return new NamespacedKey(this, key);
    }

}
