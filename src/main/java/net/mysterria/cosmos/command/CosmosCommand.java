package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.command.CommandSender;

@Command(name = "cosmos")
public class CosmosCommand {

    private final CosmosIncursion plugin;

    public CosmosCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Execute
    public void help(@Context CommandSender sender) {
        sender.sendMessage(Component.text("=== Cosmos Incursion ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/cosmos status").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Show current event status").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin reload").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Reload configuration").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin start").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Force start event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin stop").color(NamedTextColor.YELLOW)
                .append(Component.text(" - Force stop event").color(NamedTextColor.WHITE)));
    }

    @Execute(name = "status")
    public void status(@Context CommandSender sender) {
        // TODO: Will be implemented with EventManager in Session 3
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("No event active").color(NamedTextColor.WHITE)));
    }

    @Execute(name = "admin reload")
    @Permission("cosmos.admin")
    public void reload(@Context CommandSender sender) {
        try {
            plugin.getConfigManager().reload();
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                    .append(Component.text("Configuration reloaded successfully!").color(NamedTextColor.GREEN)));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                    .append(Component.text("Failed to reload configuration: " + e.getMessage()).color(NamedTextColor.RED)));
            e.printStackTrace();
        }
    }

    @Execute(name = "admin start")
    @Permission("cosmos.admin")
    public void start(@Context CommandSender sender) {
        // TODO: Will be implemented with EventManager in Session 3
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("Command not yet implemented").color(NamedTextColor.RED)));
    }

    @Execute(name = "admin stop")
    @Permission("cosmos.admin")
    public void stop(@Context CommandSender sender) {
        // TODO: Will be implemented with EventManager in Session 3
        sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("Command not yet implemented").color(NamedTextColor.RED)));
    }

}
