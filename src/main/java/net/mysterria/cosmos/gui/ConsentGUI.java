package net.mysterria.cosmos.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consent GUI shown when players approach incursion zones
 * Players must agree to PvP rules before entering
 */
public class ConsentGUI {

    private final CosmosIncursion plugin;
    private final Map<UUID, ConsentState> playerConsents;

    public ConsentGUI(CosmosIncursion plugin) {
        this.plugin = plugin;
        this.playerConsents = new HashMap<>();
    }

    /**
     * Show the consent GUI to a player
     */
    public void showConsent(Player player) {
        Gui gui = Gui.gui()
                .title(Component.text("Cosmos Incursion - Zone Entry", NamedTextColor.RED, TextDecoration.BOLD))
                .rows(3)
                .disableAllInteractions()
                .create();

        ConsentState state = playerConsents.computeIfAbsent(player.getUniqueId(), k -> new ConsentState());

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
            player.closeInventory();
            player.sendMessage(Component.text("You must agree to all terms to enter the incursion zone.", NamedTextColor.RED));
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

        ItemStack item = ItemBuilder.from(material)
                .name(Component.text(checkMark + " " + title, checked ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD))
                .lore(Component.text(description, NamedTextColor.GRAY))
                .build();

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    /**
     * Create confirm button
     */
    private GuiItem createConfirmButton(Runnable onClick) {
        ItemStack item = ItemBuilder.from(Material.EMERALD)
                .name(Component.text("Confirm & Enter", NamedTextColor.GREEN, TextDecoration.BOLD))
                .lore(Component.text("Click to enter the incursion zone", NamedTextColor.GRAY))
                .build();

        return new GuiItem(item, event -> {
            event.setCancelled(true);
            onClick.run();
        });
    }

    /**
     * Create disabled confirm button
     */
    private GuiItem createDisabledConfirmButton() {
        ItemStack item = ItemBuilder.from(Material.BARRIER)
                .name(Component.text("Confirm & Enter", NamedTextColor.DARK_GRAY, TextDecoration.BOLD))
                .lore(Component.text("You must agree to all terms first", NamedTextColor.GRAY))
                .build();

        return new GuiItem(item, event -> event.setCancelled(true));
    }

    /**
     * Create cancel button
     */
    private GuiItem createCancelButton(Runnable onClick) {
        ItemStack item = ItemBuilder.from(Material.REDSTONE_BLOCK)
                .name(Component.text("Cancel", NamedTextColor.RED, TextDecoration.BOLD))
                .lore(Component.text("Do not enter the zone", NamedTextColor.GRAY))
                .build();

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

        boolean hasAgreedToAll() {
            return pvpAgreed && lootAgreed && sequenceAgreed;
        }
    }

}
