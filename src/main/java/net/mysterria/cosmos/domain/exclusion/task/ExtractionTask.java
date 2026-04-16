package net.mysterria.cosmos.domain.exclusion.task;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.manager.PermanentZoneManager;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionChannelState;
import net.mysterria.cosmos.domain.exclusion.model.ExtractionPoint;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.exclusion.model.PlayerResourceBuffer;
import net.mysterria.cosmos.domain.exclusion.model.source.ResourceType;
import net.mysterria.cosmos.toolkit.towns.TownData;
import net.mysterria.cosmos.toolkit.towns.TownsToolkit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs every 20 ticks (1 second). Manages channel-based extraction at extraction points.
 *
 * Players must stand at an extraction point for the full channel duration
 * (permanent-zones.extraction-channel-seconds). Moving out of range cancels the channel.
 * On completion, ALL carried resources are deposited to the player's town at once.
 */
public class ExtractionTask extends BukkitRunnable {

    private final CosmosIncursion plugin;
    private final PermanentZoneManager permanentZoneManager;

    public ExtractionTask(CosmosIncursion plugin, PermanentZoneManager permanentZoneManager) {
        this.plugin = plugin;
        this.permanentZoneManager = permanentZoneManager;
    }

    @Override
    public void run() {
        long channelMillis = plugin.getConfigLoader().getConfig().getPermanentZoneExtractionChannelSeconds() * 1000L;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasMetadata("NPC")) continue;

            ExtractionChannelState channel = permanentZoneManager.getExtractionChannel(player.getUniqueId());

            if (channel != null) {
                tickActiveChannel(player, channel, channelMillis);
            } else {
                tryStartChannel(player);
            }
        }
    }

    private void tickActiveChannel(Player player, ExtractionChannelState channel, long channelMillis) {
        ExtractionPoint ep = channel.getExtractionPoint();

        if (!ep.isActive() || !ep.isPlayerInRange(player.getLocation())) {
            permanentZoneManager.cancelExtractionChannel(player.getUniqueId());
            player.clearTitle();
            player.sendActionBar(Component.text("Extraction interrupted! Stay at the extraction point.", NamedTextColor.RED));
            return;
        }

        if (channel.isComplete(channelMillis)) {
            completeExtraction(player);
        } else {
            showExtractionProgress(player, channel, channelMillis);
        }
    }

    private void completeExtraction(Player player) {
        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
        Map<ResourceType, Double> extracted = buffer.snapshot();
        // Clear buffer first so zone-exit enforcement allows the teleport
        buffer.clear();
        permanentZoneManager.cancelExtractionChannel(player.getUniqueId());

        depositToTown(player, extracted);

        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);

        // Teleport player out to the nearest safe point outside the zone
        if (zone != null) {
            double exitBuffer = plugin.getConfigLoader().getConfig().getPermanentZoneExtractionExitBuffer();
            Location exitLoc = permanentZoneManager.findExitPoint(zone, player.getLocation(), exitBuffer);
            if (exitLoc != null) {
                player.setMetadata("cosmos_extraction_exit", new FixedMetadataValue(plugin, true));
                player.teleport(exitLoc);
                player.removeMetadata("cosmos_extraction_exit", plugin);
            }
        }

        // Hunt Showdown-style extraction effects
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 20 * 5, 0, false, false));
        player.showTitle(Title.title(
            Component.text("EXTRACTED", NamedTextColor.GOLD),
            buildExtractedSubtitle(extracted),
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))
        ));
        notifyExtracted(player, extracted);
    }

    private Component buildExtractedSubtitle(Map<ResourceType, Double> extracted) {
        Component sub = Component.empty();
        boolean first = true;
        for (Map.Entry<ResourceType, Double> entry : extracted.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (!first) sub = sub.append(Component.text("  ", NamedTextColor.DARK_GRAY));
            sub = sub.append(Component.text(
                entry.getKey().name() + " +" + format(entry.getValue()),
                resourceColor(entry.getKey())
            ));
            first = false;
        }
        if (first) return Component.text("You live to die another day.", NamedTextColor.GRAY);
        return sub;
    }

    private void tryStartChannel(Player player) {
        PermanentZone zone = permanentZoneManager.getPlayerZone(player.getUniqueId());
        if (zone == null) return;

        PlayerResourceBuffer buffer = permanentZoneManager.getBuffer(player.getUniqueId());
        if (buffer.isEmpty()) return;

        List<ExtractionPoint> eps = permanentZoneManager.getActiveExtractionPoints(zone);
        for (ExtractionPoint ep : eps) {
            if (!ep.isActive()) continue;
            if (ep.isPlayerInRange(player.getLocation())) {
                permanentZoneManager.startExtractionChannel(player.getUniqueId(), ep);
                player.sendActionBar(Component.text("Extracting... hold position! ", NamedTextColor.GREEN)
                    .append(Component.text("[0%]", NamedTextColor.YELLOW)));
                player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f);
                break;
            }
        }
    }

    private void depositToTown(Player player, Map<ResourceType, Double> amounts) {
        Optional<TownData> townOpt = TownsToolkit.getPlayerTown(player);
        if (townOpt.isEmpty()) return;
        permanentZoneManager.depositToTown(townOpt.get().id(), amounts);
    }

    private void notifyExtracted(Player player, Map<ResourceType, Double> extracted) {
        Component msg = Component.text("Extracted! Deposited: ", NamedTextColor.GREEN);
        boolean first = true;
        for (Map.Entry<ResourceType, Double> entry : extracted.entrySet()) {
            if (entry.getValue() <= 0) continue;
            if (!first) msg = msg.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
            msg = msg.append(Component.text(
                entry.getKey().name() + " +" + format(entry.getValue()),
                resourceColor(entry.getKey())
            ));
            first = false;
        }
        player.sendActionBar(msg);
    }

    private void showExtractionProgress(Player player, ExtractionChannelState channel, long channelMillis) {
        float progress = channel.getProgress(channelMillis);
        long remaining = channelMillis - channel.getElapsedMillis();
        int secondsLeft = (int) Math.ceil(Math.max(0, remaining) / 1000.0);

        // Countdown title — stay duration slightly over 1s so it persists between ticks
        player.showTitle(Title.title(
            Component.text(secondsLeft + "s", secondsLeft <= 3 ? NamedTextColor.RED : NamedTextColor.YELLOW),
            Component.text("Hold position!", NamedTextColor.GREEN),
            Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ZERO)
        ));

        int filled = (int) (progress * 20);
        String bar = "█".repeat(filled) + "░".repeat(20 - filled);
        player.sendActionBar(Component.text("Extracting: ", NamedTextColor.GREEN)
            .append(Component.text("[" + bar + "] ", NamedTextColor.YELLOW))
            .append(Component.text((int) (progress * 100) + "%", NamedTextColor.WHITE)));
    }

    private String format(double v) {
        return String.format("%.1f", v);
    }

    private NamedTextColor resourceColor(ResourceType type) {
        return switch (type) {
            case GOLD -> NamedTextColor.GOLD;
            case SILVER -> NamedTextColor.GRAY;
            case GEMS -> NamedTextColor.GREEN;
        };
    }
}
