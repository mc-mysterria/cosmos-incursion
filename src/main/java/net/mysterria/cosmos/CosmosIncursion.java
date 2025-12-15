package net.mysterria.cosmos;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.beacon.BeaconManager;
import net.mysterria.cosmos.combat.CombatLogHandler;
import net.mysterria.cosmos.command.CosmosCommand;
import net.mysterria.cosmos.config.ConfigManager;
import net.mysterria.cosmos.effect.EffectManager;
import net.mysterria.cosmos.effect.SpiritWeightTask;
import net.mysterria.cosmos.event.EventManager;
import net.mysterria.cosmos.integration.BlueMapIntegration;
import net.mysterria.cosmos.integration.CitizensIntegration;
import net.mysterria.cosmos.listener.*;
import net.mysterria.cosmos.player.KillTracker;
import net.mysterria.cosmos.player.PlayerStateManager;
import net.mysterria.cosmos.reward.BuffManager;
import net.mysterria.cosmos.task.EventCheckTask;
import net.mysterria.cosmos.zone.ZoneManager;
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
    private EffectManager effectManager;

    @Getter
    private BlueMapIntegration blueMapIntegration;

    @Getter
    private CitizensIntegration citizensIntegration;

    @Getter
    private CombatLogHandler combatLogHandler;

    @Getter
    private BuffManager buffManager;

    private LiteCommands<CommandSender> liteCommands;

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
        beaconManager.loadBeacons();

        // Initialize player state manager
        log("Initializing player state manager...");
        playerStateManager = new PlayerStateManager(this);

        // Initialize effect manager
        log("Initializing effect manager...");
        effectManager = new EffectManager(this);

        // Initialize BlueMap integration
        log("Initializing BlueMap integration...");
        blueMapIntegration = new BlueMapIntegration(this);
        blueMapIntegration.initialize();

        // Initialize kill tracker
        log("Initializing kill tracker...");
        killTracker = new KillTracker(this, blueMapIntegration);

        // Initialize Citizens integration
        log("Initializing Citizens integration...");
        citizensIntegration = new CitizensIntegration(this);
        citizensIntegration.initialize();

        // Initialize combat log handler
        log("Initializing combat log handler...");
        combatLogHandler = new CombatLogHandler(this, playerStateManager, citizensIntegration, killTracker);

        // Initialize buff manager
        log("Initializing buff manager...");
        buffManager = new BuffManager(this);
        buffManager.loadBuffData();

        // Initialize event manager
        log("Initializing event manager...");
        eventManager = new EventManager(this, zoneManager, beaconManager, buffManager, blueMapIntegration);

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
        this.liteCommands = LiteBukkitFactory.builder()
                .commands(new CosmosCommand(this))
                .build();
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(
                new PlayerMoveListener(this, zoneManager, playerStateManager, effectManager), this);
        getServer().getPluginManager().registerEvents(
                new SafeModeListener(this, playerStateManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerDeathListener(this, playerStateManager, killTracker), this);
        getServer().getPluginManager().registerEvents(
                new PlayerQuitListener(combatLogHandler, buffManager), this);
        getServer().getPluginManager().registerEvents(
                new PlayerJoinListener(combatLogHandler, buffManager), this);
        getServer().getPluginManager().registerEvents(
                combatLogHandler, this);
    }

    private void startTasks() {
        // Event check task - runs every second
        new EventCheckTask(eventManager).runTaskTimer(this, 0L, 20L);

        // Spirit Weight task - runs based on config (default: every 5 seconds / 100 ticks)
        long dotInterval = configManager.getConfig().getDotIntervalTicks();
        new SpiritWeightTask(this, playerStateManager, effectManager, eventManager)
                .runTaskTimer(this, dotInterval, dotInterval);

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
