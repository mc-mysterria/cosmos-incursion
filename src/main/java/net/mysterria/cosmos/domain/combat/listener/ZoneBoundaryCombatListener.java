package net.mysterria.cosmos.domain.combat.listener;

import dev.ua.ikeepcalm.coi.api.event.MagicDamageEvent;
import net.mysterria.cosmos.CosmosIncursion;
import net.mysterria.cosmos.domain.exclusion.model.PermanentZone;
import net.mysterria.cosmos.domain.incursion.model.IncursionZone;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PotionSplashEvent;

import java.util.Objects;

/**
 * Player-vs-player harm only lands when both sides stand in the same zone (or both outside every zone).
 * Otherwise a player outside a zone could kill someone inside it with no risk to themselves while the
 * victim still pays the zone's death penalty.
 */
public class ZoneBoundaryCombatListener implements Listener {

    private final CosmosIncursion plugin;

    public ZoneBoundaryCombatListener(CosmosIncursion plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (crossesBoundary(owningPlayer(event.getDamageSource().getCausingEntity()), victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMagicDamage(MagicDamageEvent event) {
        if (!(event.getDamaged() instanceof Player victim)) return;
        if (crossesBoundary(event.getDamager(), victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        Player thrower = event.getPotion().getShooter() instanceof Player p ? p : null;
        for (var affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && crossesBoundary(thrower, victim)) {
                event.setIntensity(affected, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCloudApply(AreaEffectCloudApplyEvent event) {
        Player owner = event.getEntity().getSource() instanceof Player p ? p : null;
        event.getAffectedEntities().removeIf(e -> e instanceof Player victim && crossesBoundary(owner, victim));
    }

    private Player owningPlayer(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    private boolean crossesBoundary(Player attacker, Player victim) {
        if (attacker == null || attacker.equals(victim)) return false;
        // Location-based so Hollow Body NPCs (no tracked zone state) are judged the same way.
        IncursionZone attackerIncursion = plugin.getZoneManager().getZoneAt(attacker.getLocation());
        IncursionZone victimIncursion = plugin.getZoneManager().getZoneAt(victim.getLocation());
        PermanentZone attackerPermanent = plugin.getPermanentZoneManager().getZoneAt(attacker.getLocation());
        PermanentZone victimPermanent = plugin.getPermanentZoneManager().getZoneAt(victim.getLocation());
        return !Objects.equals(attackerIncursion, victimIncursion) || !Objects.equals(attackerPermanent, victimPermanent);
    }
}
