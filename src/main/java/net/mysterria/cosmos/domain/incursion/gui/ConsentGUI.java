package net.mysterria.cosmos.domain.incursion.gui;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.domain.incursion.model.source.ZoneTier;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Consent GUI shown when players approach incursion zones
 * Players must agree to PvP rules before entering
 */
public class ConsentGUI {

    // Key format: "uuid_TIER" — one consent state per player per zone tier
    private final Map<String, ConsentState> playerConsents;

    public ConsentGUI() {
        this.playerConsents = new HashMap<>();
    }

    private String consentKey(UUID playerId, ZoneTier tier) {
        return playerId + "_" + tier.name();
    }

    /**
     * Show the consent GUI to a player for the given zone tier.
     * The loot-loss and regression descriptions adapt to the tier.
     */
    public void showConsent(Player player, ZoneTier tier) {
        String key = consentKey(player.getUniqueId(), tier);
        ConsentState state = playerConsents.computeIfAbsent(key, k -> new ConsentState());

        // Reset cancelled flag when showing GUI (in case they try to enter again)
        state.cancelled = false;

        NamedTextColor tierColor = switch (tier) {
            case GREEN -> NamedTextColor.GREEN;
            case YELLOW -> NamedTextColor.YELLOW;
            case RED -> NamedTextColor.RED;
            case DEATH -> NamedTextColor.DARK_RED;
        };

        Gui gui = Gui.gui()
                .title(Component.text(tier.name() + " Zone - Confirm Entry", tierColor, TextDecoration.BOLD))
                .rows(3)
                .disableAllInteractions()
                .create();

        // PvP checkbox (same for all tiers)
        GuiItem pvpConsent = createCheckbox(
                "PvP Enabled",
                "Other players can kill you in this zone",
                state.pvpAgreed,
                () -> {
                    state.pvpAgreed = !state.pvpAgreed;
                    showConsent(player, tier);
                }
        );

        // Loot checkbox — description varies by tier
        String lootDescription = switch (tier) {
            case GREEN  -> "You will keep ALL items if you die.";
            case YELLOW -> "~33% of your items may drop on death.";
            case RED, DEATH -> "ALL of your items will drop on death.";
        };
        GuiItem lootConsent = createCheckbox(
                "Risk of Loot Loss",
                lootDescription,
                state.lootAgreed,
                () -> {
                    state.lootAgreed = !state.lootAgreed;
                    showConsent(player, tier);
                }
        );

        // Sequence regression checkbox — only dangerous in DEATH tier
        String regressionDescription = tier == ZoneTier.DEATH
                ? "Dying may cause sequence regression."
                : "No sequence regression in this zone.";
        GuiItem sequenceConsent = createCheckbox(
                "Sequence Regression",
                regressionDescription,
                state.sequenceAgreed,
                () -> {
                    state.sequenceAgreed = !state.sequenceAgreed;
                    showConsent(player, tier);
                }
        );

        gui.setItem(11, pvpConsent);
        gui.setItem(13, lootConsent);
        gui.setItem(15, sequenceConsent);

        if (state.hasAgreedToAll()) {
            GuiItem confirmButton = createConfirmButton(() -> {
                state.confirmed = true;
                player.closeInventory();
                player.sendMessage(Component.text("You have agreed to the " + tier.name() + " zone rules.", NamedTextColor.GREEN));
            });
            gui.setItem(22, confirmButton);
        } else {
            gui.setItem(22, createDisabledConfirmButton());
        }

        GuiItem cancelButton = createCancelButton(() -> {
            state.cancelled = true;
            player.closeInventory();
            player.sendMessage(Component.text("You can close this GUI anytime. You won't be able to enter without consenting.", NamedTextColor.YELLOW));
        });
        gui.setItem(18, cancelButton);

        gui.open(player);
    }

    /**
     * Create a checkbox item
     */
    private GuiItem createCheckbox(String title, String description, boolean checked, Runnable onClick) {
        Material material = checked ? Material.LIME_WOOL : Material.RED_WOOL;
        String checkMark = checked ? "✓" : "✗";

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(checkMark + " " + title, checked ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    /**
     * Create confirm button
     */
    private GuiItem createConfirmButton(Runnable onClick) {
        ItemStack item = new ItemStack(Material.EMERALD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Confirm & Enter", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Click to enter the incursion zone", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    /**
     * Create disabled confirm button
     */
    private GuiItem createDisabledConfirmButton() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Confirm & Enter", NamedTextColor.DARK_GRAY, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("You must agree to all terms first", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> event.setCancelled(true));
    }

    /**
     * Create cancel button
     */
    private GuiItem createCancelButton(Runnable onClick) {
        ItemStack item = new ItemStack(Material.REDSTONE_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Cancel", NamedTextColor.RED, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Do not enter the zone", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    /**
     * Check if a player has consented to a specific zone tier.
     */
    public boolean hasConsented(Player player, ZoneTier tier) {
        ConsentState state = playerConsents.get(consentKey(player.getUniqueId(), tier));
        return state != null && state.confirmed;
    }

    /**
     * Reset all tier consents for a player (called when event ends).
     */
    public void resetConsent(UUID playerId) {
        String prefix = playerId.toString() + "_";
        playerConsents.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Reset all consents (called when event ends).
     */
    public void resetAllConsents() {
        playerConsents.clear();
    }

    /**
     * Internal class to track consent state
     */
    private static class ConsentState {
        boolean pvpAgreed = false;
        boolean lootAgreed = false;
        boolean sequenceAgreed = false;
        boolean confirmed = false;
        boolean cancelled = false;

        boolean hasAgreedToAll() {
            return pvpAgreed && lootAgreed && sequenceAgreed;
        }
    }

}
