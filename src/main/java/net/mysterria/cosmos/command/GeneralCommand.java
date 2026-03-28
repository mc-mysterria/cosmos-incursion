package net.mysterria.cosmos.command;


import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.incursion.model.source.EventState;
import org.bukkit.command.CommandSender;

@Command(name = "cosmos")
public class GeneralCommand {

    private final CosmosIncursion plugin;

    public GeneralCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Execute
    public void help(@Context CommandSender sender) {
        sender.sendMessage(Component.text("=== Cosmos Incursion ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/cosmos status").color(NamedTextColor.YELLOW).append(Component.text(" - Show current event status").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin reload").color(NamedTextColor.YELLOW).append(Component.text(" - Reload configuration").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin start").color(NamedTextColor.YELLOW).append(Component.text(" - Force start event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin stop").color(NamedTextColor.YELLOW).append(Component.text(" - Force stop event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin give paperangel <player> [amount]").color(NamedTextColor.YELLOW).append(Component.text(" - Give Paper Angel items").color(NamedTextColor.WHITE)));
    }

    @Execute(name = "status")
    public void status(@Context CommandSender sender) {
        var eventManager = plugin.getEventManager();
        if (eventManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD).append(Component.text("EventManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        var state = eventManager.getState();
        var activeEvent = eventManager.getActiveEvent();

        sender.sendMessage(Component.text("=== Cosmos Incursion Status ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("State: ").color(NamedTextColor.YELLOW).append(Component.text(state.name()).color(getStateColor(state))));

        if (state == EventState.IDLE) {
            long cooldownSeconds = eventManager.getRemainingCooldownSeconds();
            if (cooldownSeconds > 0) {
                long minutes = cooldownSeconds / 60;
                sender.sendMessage(Component.text("Cooldown: ").color(NamedTextColor.YELLOW).append(Component.text(minutes + " minutes remaining").color(NamedTextColor.WHITE)));
            } else {
                sender.sendMessage(Component.text("Status: ").color(NamedTextColor.YELLOW).append(Component.text("Ready to start").color(NamedTextColor.GREEN)));
            }
        } else if (activeEvent != null) {
            if (state == EventState.STARTING) {
                sender.sendMessage(Component.text("Countdown: ").color(NamedTextColor.YELLOW).append(Component.text(activeEvent.getCountdownRemaining() + " seconds").color(NamedTextColor.WHITE)));
            } else if (state == EventState.ACTIVE) {
                long remainingSeconds = activeEvent.getRemainingTime() / 1000;
                long minutes = remainingSeconds / 60;
                sender.sendMessage(Component.text("Time Remaining: ").color(NamedTextColor.YELLOW).append(Component.text(minutes + " minutes").color(NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Active Zones: ").color(NamedTextColor.YELLOW).append(Component.text(String.valueOf(activeEvent.getIncursionZones().size())).color(NamedTextColor.WHITE)));
            }
        }
    }

    private NamedTextColor getStateColor(EventState state) {
        return switch (state) {
            case IDLE -> NamedTextColor.GRAY;
            case STARTING -> NamedTextColor.YELLOW;
            case ACTIVE -> NamedTextColor.GREEN;
            case ENDING -> NamedTextColor.RED;
        };
    }

}
