package net.mysterria.cosmos;

import dev.rollczi.litecommands.LiteCommands;
import dev.rollczi.litecommands.bukkit.LiteBukkitFactory;
import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.command.CosmosCommand;
import net.mysterria.cosmos.config.ConfigManager;
import net.william278.husktowns.api.HuskTownsAPI;
import org.bukkit.Bukkit;
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

        // Register commands
        log("Registering commands...");
        registerCommands();

        log("Cosmos Incursion enabled!");
    }

    @Override
    public void onDisable() {
        log("Disabling Cosmos Incursion...");

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

}
