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
 * resource extraction, beacon hold time, and qualifying PvP kills.
 * <p>
 * Callers are responsible for anti-grief gating (griefing kills, Corrupted Monster) before
 * calling the PvP grant methods. This class applies repeat-kill decay and blocks reciprocal
 * incursion kills, since the CircleOfImagination source cap (acting-sources.yml) does not
 * distinguish a real fight from two players trading kills.
 */
public class ActingRewardManager {

    private final CosmosIncursion plugin;

    // killer UUID -> victim UUID -> repeat-kill state. Applies exponential backoff to
    // repeated kills of the same victim so farming a single target loses value fast.
    private final Map<UUID, Map<UUID, RepeatKillState>> pvpRepeatKills = new ConcurrentHashMap<>();
    // One shared state per pair catches A killing B followed by B killing A. Tracking each
    // direction independently would let two players trade first kills for full rewards.
    private final Map<PvpPair, PairKillState> incursionKillPairs = new ConcurrentHashMap<>();

    // player UUID -> beacon hold seconds accumulated toward the next acting grant
    private final Map<UUID, Integer> holdSeconds = new ConcurrentHashMap<>();

    private record RepeatKillState(int streak, long lastGrantMillis) {}
    private record PvpPair(UUID first, UUID second) {
        private static PvpPair of(UUID left, UUID right) {
            return left.compareTo(right) <= 0 ? new PvpPair(left, right) : new PvpPair(right, left);
        }
    }
    private record PairKillState(UUID lastKiller, long lastKillMillis, boolean reciprocal) {}

    public ActingRewardManager(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /** Grants acting for a successful resource extraction from a permanent zone of the given tier. */
    public void grantExtractionActing(Player player, ExclusionZoneTier tier) {
        double effort = config().getExclusionTierConfigs().get(tier).extractionActingEffort();
        if (effort <= 0) return;
        CoiToolkit.grantActingEffort(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort);
    }

    /**
     * Records one second of beacon hold time for a player. Once a full interval of hold time has
     * accumulated, grants the configured effort. Batched because CoI converts effort to whole
     * acting points, so a tiny per-second grant could round down to nothing.
     */
    public void recordBeaconHoldSecond(Player player) {
        double effort = config().getBeaconHoldActingEffort();
        int interval = Math.max(1, config().getBeaconHoldActingIntervalSeconds());
        if (effort <= 0) return;

        int held = holdSeconds.merge(player.getUniqueId(), 1, Integer::sum);
        if (held >= interval) {
            holdSeconds.put(player.getUniqueId(), held - interval);
            CoiToolkit.grantActingEffort(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort);
        }
    }

    /**
     * Grants acting for a qualifying PvP kill inside a timed incursion zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantIncursionPvpActing(Player killer, Player victim, ZoneTier tier) {
        double effort = config().getTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort, true);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a permanent extraction zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantExclusionPvpActing(Player killer, Player victim, ExclusionZoneTier tier) {
        double effort = config().getExclusionTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort, false);
    }

    private void grantPvpActing(Player killer, Player victim, double effort, boolean incursionKill) {
        if (effort <= 0 || killer == null || victim == null || killer.equals(victim)) return;

        if (incursionKill && isReciprocalIncursionKill(killer.getUniqueId(), victim.getUniqueId())) return;

        double multiplier = nextRepeatMultiplier(killer.getUniqueId(), victim.getUniqueId());
        double grantedEffort = effort * multiplier;
        if (grantedEffort <= 0) return;

        CoiToolkit.grantActingEffort(killer, CoiToolkit.SOURCE_PLAYER_INTERACTION, grantedEffort);
    }

    /**
     * True for the first kill back inside the repeat-kill window, and for every kill of the pair
     * after it until that window ends (denied kills do not extend it).
     */
    private boolean isReciprocalIncursionKill(UUID killerId, UUID victimId) {
        long now = System.currentTimeMillis();
        long resetMillis = config().getPvpRepeatKillResetSeconds() * 1000L;
        PvpPair pair = PvpPair.of(killerId, victimId);
        PairKillState previous = incursionKillPairs.get(pair);

        if (previous == null || now - previous.lastKillMillis() >= resetMillis) {
            incursionKillPairs.put(pair, new PairKillState(killerId, now, false));
            return false;
        }
        if (previous.reciprocal()) return true;

        boolean reciprocal = !previous.lastKiller().equals(killerId);
        incursionKillPairs.put(pair, new PairKillState(killerId, now, reciprocal));
        return reciprocal;
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
