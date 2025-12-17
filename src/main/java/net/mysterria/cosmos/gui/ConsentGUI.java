package net.mysterria.cosmos.gui;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

    private final Map<UUID, ConsentState> playerConsents;

    public ConsentGUI() {
        this.playerConsents = new HashMap<>();
    }

    /**
     * Show the consent GUI to a player
     */
    public void showConsent(Player player) {
        ConsentState state = playerConsents.computeIfAbsent(player.getUniqueId(), k -> new ConsentState());

        // Reset cancelled flag when showing GUI (in case they try to enter again)
        state.cancelled = false;

        Gui gui = Gui.gui()
                .title(Component.text("Cosmos Incursion - Zone Entry", NamedTextColor.RED, TextDecoration.BOLD))
                .rows(3)
                .disableAllInteractions()
                .create();

        // Note: No close handler - players can close freely
        // Zone entry is already protected by consent checks in ZoneCheckTask

        // Create checkbox items
        GuiItem pvpConsent = createCheckbox(
                "PvP Enabled",
                "Other players can kill you in this zone",
                state.pvpAgreed,
                () -> {
                    state.pvpAgreed = !state.pvpAgreed;
                    showConsent(player); // Refresh GUI
                }
        );

        GuiItem lootConsent = createCheckbox(
                "Risk of Loot Loss",
                "You may lose items if you die",
                state.lootAgreed,
                () -> {
                    state.lootAgreed = !state.lootAgreed;
                    showConsent(player); // Refresh GUI
                }
        );

        GuiItem sequenceConsent = createCheckbox(
                "Sequence Regression",
                "Dying may cause you to lose a sequence",
                state.sequenceAgreed,
                () -> {
                    state.sequenceAgreed = !state.sequenceAgreed;
                    showConsent(player); // Refresh GUI
                }
        );

        // Add items to GUI
        gui.setItem(11, pvpConsent);
        gui.setItem(13, lootConsent);
        gui.setItem(15, sequenceConsent);

        // Add confirm button if all agreed
        if (state.hasAgreedToAll()) {
            GuiItem confirmButton = createConfirmButton(() -> {
                state.confirmed = true;
                player.closeInventory();
                player.sendMessage(Component.text("You have agreed to the incursion zone rules.", NamedTextColor.GREEN));
            });
            gui.setItem(22, confirmButton);
        } else {
            GuiItem disabledButton = createDisabledConfirmButton();
            gui.setItem(22, disabledButton);
        }

        // Add cancel button
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
     * Check if a player has consented for this session
     */
    public boolean hasConsented(Player player) {
        ConsentState state = playerConsents.get(player.getUniqueId());
        return state != null && state.confirmed;
    }

    /**
     * Reset consent for a player (when event ends)
     */
    public void resetConsent(UUID playerId) {
        playerConsents.remove(playerId);
    }

    /**
     * Reset all consents (when event ends)
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
