package net.mysterria.cosmos.domain.acting;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants CircleOfImagination acting effort for completing Cosmos Incursion objectives:
 * resource extraction, beacon capture, and qualifying PvP kills.
 * <p>
 * Callers are responsible for anti-grief gating (griefing kills, Corrupted Monster) before
 * calling the PvP grant methods — this class only enforces the repeat-kill exponential
 * backoff, since the CircleOfImagination source cap (acting-sources.yml) already bounds
 * total farmable amount.
 */
public class ActingRewardManager {

    private final CosmosIncursion plugin;

    // killer UUID -> victim UUID -> repeat-kill state. Applies exponential backoff to
    // repeated kills of the same victim so farming a single target loses value fast.
    private final Map<UUID, Map<UUID, RepeatKillState>> pvpRepeatKills = new ConcurrentHashMap<>();

    private record RepeatKillState(int streak, long lastGrantMillis) {}

    public ActingRewardManager(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /** Grants acting for a successful resource extraction from a permanent zone of the given tier. */
    public void grantExtractionActing(Player player, ExclusionZoneTier tier) {
        double effort = config().getExclusionTierConfigs().get(tier).extractionActingEffort();
        if (effort <= 0) return;
        CoiToolkit.grantActingEffort(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort);
    }

    /** Grants acting to each player present when their town fully captures a Spirit Beacon. */
    public void grantBeaconCaptureActing(Player player) {
        double effort = config().getBeaconCaptureActingEffort();
        if (effort <= 0) return;
        CoiToolkit.grantActingEffort(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a timed incursion zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantIncursionPvpActing(Player killer, Player victim, ZoneTier tier) {
        double effort = config().getTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a permanent extraction zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantExclusionPvpActing(Player killer, Player victim, ExclusionZoneTier tier) {
        double effort = config().getExclusionTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort);
    }

    private void grantPvpActing(Player killer, Player victim, double effort) {
        if (effort <= 0 || killer == null || victim == null || killer.equals(victim)) return;

        double multiplier = nextRepeatMultiplier(killer.getUniqueId(), victim.getUniqueId());
        double grantedEffort = effort * multiplier;
        if (grantedEffort <= 0) return;

        CoiToolkit.grantActingEffort(killer, CoiToolkit.SOURCE_PLAYER_INTERACTION, grantedEffort);
    }

    /**
     * Advances and returns the repeat-kill multiplier for this killer/victim pair.
     * The streak resets to zero (full reward) once the reset window has elapsed since the
     * last grant; otherwise each successive kill multiplies the reward by the decay factor,
     * floored at the configured minimum.
     */
    private double nextRepeatMultiplier(UUID killerId, UUID victimId) {
        long now = System.currentTimeMillis();
        long resetMillis = config().getPvpRepeatKillResetSeconds() * 1000L;

        Map<UUID, RepeatKillState> victims = pvpRepeatKills.computeIfAbsent(killerId, k -> new ConcurrentHashMap<>());
        RepeatKillState previous = victims.get(victimId);

        int streak = (previous == null || (now - previous.lastGrantMillis()) >= resetMillis)
                ? 0
                : previous.streak() + 1;

        victims.put(victimId, new RepeatKillState(streak, now));

        double multiplier = Math.pow(config().getPvpRepeatKillDecayFactor(), streak);
        return Math.max(multiplier, config().getPvpRepeatKillMinMultiplier());
    }

    private CosmosConfig config() {
        return plugin.getConfigLoader().getConfig();
    }

}
