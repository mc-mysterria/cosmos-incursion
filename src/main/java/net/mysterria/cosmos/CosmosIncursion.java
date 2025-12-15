package net.mysterria.cosmos;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.william278.husktowns.api.HuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class CosmosIncursion extends JavaPlugin {

    @Getter
    private static CosmosIncursion instance;

    @Getter
    private CircleOfImaginationAPI coiAPI;

    @Getter
    private HuskTownsAPI huskTownsAPI;

    @Override
    public void onEnable() {
        instance = this;

        log("Enabling Cosmos Incursion...");
        log("Enablind COI API...");
        enableCoiApi();
        log("Enabling HuskTowns API...");
        enableHuskTownsApi();
        log("Cosmos Incursion enabled!");
    }

    @Override
    public void onDisable() {
        log("Disabling Cosmos Incursion...");
        log("Cosmos Incursion disabled!");
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
