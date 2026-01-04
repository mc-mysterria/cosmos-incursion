package net.mysterria.cosmos;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.domain.beacon.BeaconManager;
import net.mysterria.cosmos.domain.beacon.ui.BeaconUIManager;
import net.mysterria.cosmos.domain.combat.CombatLogHandler;
import net.mysterria.cosmos.command.CosmosCommand;
import net.mysterria.cosmos.config.ConfigManager;
import net.mysterria.cosmos.domain.effect.EffectManager;
import net.mysterria.cosmos.task.SpiritWeightTask;
import net.mysterria.cosmos.domain.event.EventManager;
import net.mysterria.cosmos.gui.ConsentGUI;
import net.mysterria.cosmos.integration.BlueMapIntegration;
import net.mysterria.cosmos.integration.CitizensIntegration;
import net.mysterria.cosmos.listener.*;
import net.mysterria.cosmos.domain.player.KillTracker;
import net.mysterria.cosmos.domain.player.PlayerStateManager;
import net.mysterria.cosmos.domain.reward.BuffManager;
import net.mysterria.cosmos.task.EventCheckTask;
import net.mysterria.cosmos.domain.zone.ZoneManager;
import net.william278.husktowns.api.HuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CosmosIncursion extends JavaPlugin {

    @Getter
    private static CosmosIncursion instance;

    @Getter
    private CircleOfImaginationAPI coiAPI;

    @Getter
    private HuskTownsAPI huskTownsAPI;

    @Getter
    private ConfigManager configManager;

    @Getter
    private ZoneManager zoneManager;

    @Getter
    private BeaconManager beaconManager;

    @Getter
    private EventManager eventManager;

    @Getter
    private PlayerStateManager playerStateManager;

    @Getter
    private KillTracker killTracker;

    @Getter
    private net.mysterria.cosmos.domain.combat.DeathHandler deathHandler;

    @Getter
    private EffectManager effectManager;

    @Getter
    private BlueMapIntegration blueMapIntegration;

    @Getter
    private CitizensIntegration citizensIntegration;

    @Getter
    private CombatLogHandler combatLogHandler;

    @Getter
    private BuffManager buffManager;

    @Getter
    private net.mysterria.cosmos.gui.ConsentGUI consentGUI;

    @Getter
    private net.mysterria.cosmos.domain.beacon.ui.BeaconUIManager beaconUIManager;

    private LiteCommands<CommandSender> liteCommands;

    // Task IDs for tasks that need to be restarted on reload
    private int spiritWeightTaskId = -1;

    @Override
    public void onEnable() {
        instance = this;

        log("Enabling Cosmos Incursion...");

        // Load configuration
        log("Loading configuration...");
        configManager = new ConfigManager(this);
        configManager.load();

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
        effectManager = new EffectManager(this);

        // Initialize beacon UI manager
        log("Initializing beacon UI manager...");
        beaconUIManager = new BeaconUIManager(this, beaconManager);

        // Initialize BlueMap integration
        log("Initializing BlueMap integration...");
        blueMapIntegration = new BlueMapIntegration(this);
        blueMapIntegration.initialize();

        // Initialize kill tracker
        log("Initializing kill tracker...");
        killTracker = new KillTracker(this, blueMapIntegration);

        // Initialize death handler
        log("Initializing death handler...");
        deathHandler = new net.mysterria.cosmos.domain.combat.DeathHandler(this, playerStateManager, killTracker);

        // Initialize Citizens integration (retry mechanism for API initialization)
        log("Initializing Citizens integration...");
        citizensIntegration = new CitizensIntegration(this);
        initializeCitizensWithRetry(0);

        // Initialize combat log handler
        log("Initializing combat log handler...");
        combatLogHandler = new CombatLogHandler(this, playerStateManager, citizensIntegration, killTracker);

        // Initialize buff manager
        log("Initializing buff manager...");
        buffManager = new BuffManager(this);
        buffManager.loadBuffData();

        // Initialize consent GUI
        log("Initializing consent GUI...");
        consentGUI = new ConsentGUI();

        // Initialize event manager
        log("Initializing event manager...");
        eventManager = new EventManager(this, zoneManager, beaconManager, buffManager, blueMapIntegration, beaconUIManager);

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
        if (buffManager != null) {
            buffManager.saveBuffData();
        }

        // Unregister commands
        if (liteCommands != null) {
            liteCommands.unregister();
        }

        log("Cosmos Incursion disabled!");
    }

    private void registerCommands() {
        this.liteCommands = LiteBukkitFactory.builder().commands(new CosmosCommand(this)).build();
    }

    private void registerListeners() {
        // PlayerMoveListener removed - using tick-based ZoneCheckTask instead for better reliability
        getServer().getPluginManager().registerEvents(new SafeModeListener(this, playerStateManager), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(playerStateManager, killTracker, deathHandler), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(combatLogHandler, buffManager, beaconUIManager), this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(combatLogHandler, buffManager), this);
        getServer().getPluginManager().registerEvents(combatLogHandler, this);
        getServer().getPluginManager().registerEvents(new BeaconProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperAngelListener(this), this);
    }

    private void startTasks() {
        // Event check task - runs every second
        new EventCheckTask(eventManager).runTaskTimer(this, 0L, 20L);

        // Zone check task - runs every 5 ticks (4 times per second) for reliable zone detection
        new net.mysterria.cosmos.task.ZoneCheckTask(this, zoneManager, playerStateManager, effectManager, eventManager, consentGUI)
                .runTaskTimer(this, 0L, 5L);

        // Spirit Weight task - runs based on config (default: every 5 seconds / 100 ticks)
        startSpiritWeightTask();

        // Hollow Body cleanup task - runs every 30 seconds
        if (citizensIntegration.isAvailable()) {
            getServer().getScheduler().runTaskTimer(this, () -> {
                citizensIntegration.cleanupExpired();
            }, 600L, 600L);  // 30 seconds = 600 ticks
        }

        // Corrupted Monster cleanup task - runs every 60 seconds
        getServer().getScheduler().runTaskTimer(this, () -> {
            killTracker.cleanupExpired();
        }, 1200L, 1200L);  // 60 seconds = 1200 ticks

        // Buff cleanup task - runs every 5 minutes
        getServer().getScheduler().runTaskTimer(this, () -> {
            buffManager.cleanupExpired();
        }, 6000L, 6000L);  // 5 minutes = 6000 ticks
    }

    /**
     * Initialize Citizens integration with retry mechanism
     * @param attempt Current attempt number (0-based)
     */
    private void initializeCitizensWithRetry(int attempt) {
        final int maxAttempts = 10;
        final long delayTicks = 20L; // 1 second between attempts

        getServer().getScheduler().runTaskLater(this, () -> {
            boolean success = citizensIntegration.initialize();

            if (!success && attempt < maxAttempts - 1) {
                log("Citizens API not ready yet, retrying in 1 second... (attempt " + (attempt + 2) + "/" + maxAttempts + ")");
                initializeCitizensWithRetry(attempt + 1);
            } else if (!success) {
                log("Failed to initialize Citizens after " + maxAttempts + " attempts - Hollow Body NPCs will be disabled");
            }
        }, delayTicks);
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

    /**
     * Start or restart the Spirit Weight task with current config values
     */
    private void startSpiritWeightTask() {
        // Cancel existing task if running
        if (spiritWeightTaskId != -1) {
            getServer().getScheduler().cancelTask(spiritWeightTaskId);
        }

        // Start new task with current config interval
        long dotInterval = configManager.getConfig().getDotIntervalTicks();
        SpiritWeightTask task = new SpiritWeightTask(this, playerStateManager, effectManager, eventManager, zoneManager);
        spiritWeightTaskId = task.runTaskTimer(this, dotInterval, dotInterval).getTaskId();
    }

    /**
     * Reload plugin configuration and restart config-dependent tasks
     * Called by /cosmos admin reload command
     */
    public void reloadPlugin() {
        log("Reloading Cosmos Incursion configuration...");

        // Reload configuration
        configManager.reload();

        // Restart tasks that depend on config values
        startSpiritWeightTask();

        log("Configuration reloaded successfully!");
    }

    public void log(String message) {
        Component debugMessage = Component.text("[CI] ").color(NamedTextColor.GOLD).append(Component.text(message).color(NamedTextColor.WHITE));
        Bukkit.getConsoleSender().sendMessage(debugMessage);
    }

    /**
     * Create a NamespacedKey for this plugin
     */
    public NamespacedKey getKey(String key) {
        return new NamespacedKey(this, key);
    }

}
