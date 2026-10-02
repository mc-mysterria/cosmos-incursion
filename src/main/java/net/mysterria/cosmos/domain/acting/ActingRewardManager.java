package net.mysterria.cosmos.domain.acting;

import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.config.CosmosConfig;
import net.mysterria.cosmos.domain.exclusion.model.source.ExclusionZoneTier;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import dev.ua.ikeepcalm.coi.api.model.ActingSourceCategory;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditOutcome;
import dev.ua.ikeepcalm.mysterria.audit.client.api.AuditRisk;
import net.mysterria.cosmos.toolkit.CoiToolkit;
import net.mysterria.cosmos.toolkit.MysterriaAuditEmitter;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grants CircleOfImagination acting effort for completing Cosmos Incursion objectives:
 * resource extraction, beacon capture, and qualifying PvP kills.
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
        grant(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort, "extraction", tier.name(), null, 1.0);
    }

    /** Grants acting to each player present when their town fully captures a Spirit Beacon. */
    public void grantBeaconCaptureActing(Player player) {
        double effort = config().getBeaconCaptureActingEffort();
        if (effort <= 0) return;
        grant(player, CoiToolkit.SOURCE_WORLD_CONTENT, effort, "beacon_capture", null, null, 1.0);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a timed incursion zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantIncursionPvpActing(Player killer, Player victim, ZoneTier tier) {
        double effort = config().getTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort, "incursion_pvp", tier.name());
    }

    /**
     * Grants acting for a qualifying PvP kill inside a permanent extraction zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantExclusionPvpActing(Player killer, Player victim, ExclusionZoneTier tier) {
        double effort = config().getExclusionTierConfigs().get(tier).pvpActingEffort();
        grantPvpActing(killer, victim, effort, "exclusion_pvp", tier.name());
    }

    private void grantPvpActing(Player killer, Player victim, double effort, String source, String tier) {
        if (effort <= 0 || killer == null || victim == null || killer.equals(victim)) return;

        if ("incursion_pvp".equals(source) && isReciprocalIncursionKill(killer.getUniqueId(), victim.getUniqueId())) {
            UUID operationId = UUID.randomUUID();
            emitActingGranted(operationId, killer, CoiToolkit.SOURCE_PLAYER_INTERACTION, effort, 0,
                    source, tier, victim, 0.0, AuditOutcome.DENIED, "reciprocal_kill_trade");
            return;
        }

        double multiplier = nextRepeatMultiplier(killer.getUniqueId(), victim.getUniqueId());
        double grantedEffort = effort * multiplier;
        if (grantedEffort <= 0) return;

        grant(killer, CoiToolkit.SOURCE_PLAYER_INTERACTION, grantedEffort, source, tier, victim, multiplier);
    }

    /**
     * Records an incursion kill pair and rejects the pair after the first reciprocal kill.
     * The existing repeat-kill decay handles one-sided farming. This state handles players
     * alternating kills, which otherwise receives a fresh first-kill reward in each direction.
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

        if (previous.reciprocal() || !previous.lastKiller().equals(killerId)) {
            incursionKillPairs.put(pair, new PairKillState(killerId, now, true));
            return true;
        }

        incursionKillPairs.put(pair, new PairKillState(killerId, now, false));
        return false;
    }

    /**
     * Grants acting through COI and records one {@code incursion.acting_granted} row. COI grants
     * 0 points to non-Beyonders and capped sources; that is recorded as DENIED, since nothing
     * changed. The operation ID passed to COI is the row's correlation ID.
     */
    private void grant(Player player, ActingSourceCategory category, double effort, String source,
                       String tier, Player victim, double multiplier) {
        UUID operationId = UUID.randomUUID();
        int granted;
        try {
            granted = CoiToolkit.grantActingEffort(player, category, effort, operationId);
        } catch (RuntimeException failure) {
            emitActingGranted(operationId, player, category, effort, 0, source, tier, victim, multiplier,
                    AuditOutcome.FAILED, failure.getClass().getName());
            throw failure;
        }
        emitActingGranted(operationId, player, category, effort, granted, source, tier, victim, multiplier,
                granted > 0 ? AuditOutcome.COMMITTED : AuditOutcome.DENIED,
                granted > 0 ? source : "no_acting_granted");
    }

    private void emitActingGranted(UUID operationId, Player player, ActingSourceCategory category,
                                   double effort, int granted, String source, String tier, Player victim,
                                   double multiplier, AuditOutcome outcome, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", source);
        metadata.put("source_category", category.name());
        if (tier != null) metadata.put("tier", tier);
        metadata.put("acting_effort", effort);
        metadata.put("acting_granted", granted);
        metadata.put("repeat_multiplier", multiplier);
        if (victim != null) metadata.put("victim_name", victim.getName());
        MysterriaAuditEmitter.putPlayerLocation(metadata, player);
        MysterriaAuditEmitter.emit(plugin, "incursion.acting_granted", outcome,
                outcome == AuditOutcome.FAILED ? AuditRisk.NORMAL : AuditRisk.LOW,
                operationId, "acting." + operationId, player.getUniqueId(), player.getUniqueId(),
                victim == null ? null : victim.getUniqueId(), reason, metadata);
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
