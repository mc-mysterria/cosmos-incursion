package net.mysterria.cosmos.command;

import dev.rollczi.litecommands.annotations.command.Command;
import dev.rollczi.litecommands.annotations.context.Context;
import dev.rollczi.litecommands.annotations.execute.Execute;
import dev.rollczi.litecommands.annotations.permission.Permission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.incursion.model.EventResult;
import net.mysterria.cosmos.domain.incursion.model.TownScore;
import net.mysterria.cosmos.domain.incursion.model.source.EventState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Command(name = "cosmos")
public class GeneralCommand {

    private final CosmosIncursion plugin;

    public GeneralCommand(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @Execute(name = "help")
    @Permission("cosmos.admin")
    public void help(@Context CommandSender sender) {
        sender.sendMessage(Component.text("=== Cosmos Incursion ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/cosmos guide").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Open the in-game guide").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos balance").color(NamedTextColor.YELLOW)
            .append(Component.text(" - View your town's extracted resources").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos status").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Show current event status").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos leaderboard").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Show incursion standings and win history").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin reload").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Reload configuration").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin start / stop").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Force start/stop event").color(NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("/cosmos admin give paperangel <player>").color(NamedTextColor.YELLOW)
            .append(Component.text(" - Give Paper Angel item").color(NamedTextColor.WHITE)));
    }

    @Execute(aliases = {"guide"})
    public void guide(@Context CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Only players can open the guide.", NamedTextColor.RED)));
            return;
        }
        plugin.getGuideGUI().openMainMenu(player);
    }

    @Execute(name = "balance")
    public void balance(@Context CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Only players can view balances.", NamedTextColor.RED)));
            return;
        }
        plugin.getGuideGUI().openTownResources(player);
    }

    @Execute(name = "shop")
    public void shop(@Context CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("[Cosmos] ", NamedTextColor.GOLD)
                .append(Component.text("Only players can open the shop.", NamedTextColor.RED)));
            return;
        }
        plugin.getZoneShopGUI().open(player);
    }

    @Execute(name = "status")
    @Permission("cosmos.admin")
    public void status(@Context CommandSender sender) {
        var eventManager = plugin.getEventManager();
        if (eventManager == null) {
            sender.sendMessage(Component.text("[Cosmos Incursion] ").color(NamedTextColor.GOLD)
                .append(Component.text("EventManager not initialized").color(NamedTextColor.RED)));
            return;
        }

        var state = eventManager.getState();
        var activeEvent = eventManager.getActiveEvent();

        sender.sendMessage(Component.text("=== Cosmos Incursion Status ===").color(NamedTextColor.GOLD));
        sender.sendMessage(Component.text("State: ").color(NamedTextColor.YELLOW)
            .append(Component.text(state.name()).color(getStateColor(state))));

        if (state == EventState.IDLE) {
            long cooldownSeconds = eventManager.getRemainingCooldownSeconds();
            if (cooldownSeconds > 0) {
                long minutes = cooldownSeconds / 60;
                sender.sendMessage(Component.text("Cooldown: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(minutes + " minutes remaining").color(NamedTextColor.WHITE)));
            } else {
                sender.sendMessage(Component.text("Status: ").color(NamedTextColor.YELLOW)
                    .append(Component.text("Ready to start").color(NamedTextColor.GREEN)));
            }
        } else if (activeEvent != null) {
            if (state == EventState.STARTING) {
                sender.sendMessage(Component.text("Countdown: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(activeEvent.getCountdownRemaining() + " seconds").color(NamedTextColor.WHITE)));
            } else if (state == EventState.ACTIVE) {
                long remainingSeconds = activeEvent.getRemainingTime() / 1000;
                long minutes = remainingSeconds / 60;
                sender.sendMessage(Component.text("Time Remaining: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(minutes + " minutes").color(NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Active Zones: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(String.valueOf(activeEvent.getIncursionZones().size())).color(NamedTextColor.WHITE)));
            }
        }
    }

    @Execute(name = "leaderboard")
    public void leaderboard(@Context CommandSender sender) {
        var history = plugin.getEventHistoryStore();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

        sender.sendMessage(Component.text("=== Incursion Leaderboard ===").color(NamedTextColor.GOLD));

        if (history.getHolderTownId() != 0) {
            sender.sendMessage(Component.text("Current Holder: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(history.getHolderTownName()).color(NamedTextColor.GOLD))
                    .append(Component.text(" (streak: " + history.getHolderStreak() + ")").color(NamedTextColor.GRAY)));
        }

        Map<String, Long> winCounts = history.getWinCounts();
        if (!winCounts.isEmpty()) {
            sender.sendMessage(Component.text("All-time wins:").color(NamedTextColor.YELLOW));
            int shown = 0;
            for (Map.Entry<String, Long> entry : winCounts.entrySet()) {
                if (shown >= 5) break;
                sender.sendMessage(Component.text("  " + entry.getKey() + ": ").color(NamedTextColor.WHITE)
                        .append(Component.text(entry.getValue() + " win(s)").color(NamedTextColor.GREEN)));
                shown++;
            }
        }

        List<EventResult> recent = history.getRecent(5);
        if (recent.isEmpty()) {
            sender.sendMessage(Component.text("No recorded events yet.").color(NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.text("Recent events:").color(NamedTextColor.YELLOW));
        for (EventResult result : recent) {
            String when = formatter.format(Instant.ofEpochMilli(result.timestampMillis()));
            TownScore winner = result.standings().stream()
                    .filter(t -> t.rank() == 1 && t.qualified())
                    .findFirst().orElse(null);
            String summary = winner != null ? winner.townName() : "no winner";
            sender.sendMessage(Component.text("  " + when + " — ").color(NamedTextColor.GRAY)
                    .append(Component.text(summary).color(NamedTextColor.WHITE)));
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
