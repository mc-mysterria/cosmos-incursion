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
 * Grants CircleOfImagination acting points for completing Cosmos Incursion objectives:
 * resource extraction, beacon capture, and qualifying PvP kills.
 * <p>
 * Callers are responsible for anti-grief gating (griefing kills, Corrupted Monster) before
 * calling the PvP grant methods — this class only enforces the repeat-kill cooldown, since
 * the CircleOfImagination source cap (acting-sources.yml) already bounds total farmable amount.
 */
public class ActingRewardManager {

    private final CosmosIncursion plugin;

    // killer UUID -> victim UUID -> last grant timestamp (millis). Prevents repeat-kill farming
    // of the same victim from generating unlimited acting grants.
    private final Map<UUID, Map<UUID, Long>> pvpGrantCooldowns = new ConcurrentHashMap<>();

    public ActingRewardManager(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    /** Grants acting for a successful resource extraction from a permanent zone of the given tier. */
    public void grantExtractionActing(Player player, ExclusionZoneTier tier) {
        int amount = config().getExclusionTierConfigs().get(tier).extractionActingReward();
        if (amount <= 0) return;
        CoiToolkit.grantActing(player, CoiToolkit.SOURCE_WORLD_CONTENT, amount);
    }

    /** Grants acting to each player present when their town fully captures a Spirit Beacon. */
    public void grantBeaconCaptureActing(Player player) {
        int amount = config().getBeaconCaptureActingReward();
        if (amount <= 0) return;
        CoiToolkit.grantActing(player, CoiToolkit.SOURCE_WORLD_CONTENT, amount);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a timed incursion zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantIncursionPvpActing(Player killer, Player victim, ZoneTier tier) {
        int amount = config().getTierConfigs().get(tier).pvpActingReward();
        grantPvpActing(killer, victim, amount);
    }

    /**
     * Grants acting for a qualifying PvP kill inside a permanent extraction zone.
     * Caller must have already verified this isn't a griefing/Corrupted Monster kill.
     */
    public void grantExclusionPvpActing(Player killer, Player victim, ExclusionZoneTier tier) {
        int amount = config().getExclusionTierConfigs().get(tier).pvpActingReward();
        grantPvpActing(killer, victim, amount);
    }

    private void grantPvpActing(Player killer, Player victim, int amount) {
        if (amount <= 0 || killer == null || victim == null || killer.equals(victim)) return;
        if (isOnCooldown(killer.getUniqueId(), victim.getUniqueId())) return;

        CoiToolkit.grantActing(killer, CoiToolkit.SOURCE_PLAYER_INTERACTION, amount);
        markGranted(killer.getUniqueId(), victim.getUniqueId());
    }

    private boolean isOnCooldown(UUID killerId, UUID victimId) {
        Long last = pvpGrantCooldowns.getOrDefault(killerId, Map.of()).get(victimId);
        if (last == null) return false;
        long cooldownMillis = config().getPvpActingCooldownSeconds() * 1000L;
        return (System.currentTimeMillis() - last) < cooldownMillis;
    }

    private void markGranted(UUID killerId, UUID victimId) {
        pvpGrantCooldowns.computeIfAbsent(killerId, k -> new ConcurrentHashMap<>())
                .put(victimId, System.currentTimeMillis());
    }

    private CosmosConfig config() {
        return plugin.getConfigLoader().getConfig();
    }

}
