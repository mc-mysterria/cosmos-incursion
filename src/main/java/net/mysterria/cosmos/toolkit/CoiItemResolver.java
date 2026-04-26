package net.mysterria.cosmos.toolkit;

import dev.ua.ikeepcalm.coi.api.CircleOfImaginationAPI;
import net.mysterria.cosmos.CosmosIncursion;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CoiItemResolver {

    private static final Pattern TYPED_ITEM =
        Pattern.compile("^(char|potion|recipe)-([a-z]+)-(\\d)$");
    private static final Pattern INGREDIENT_ITEM =
        Pattern.compile("^(ingredients|main-ingredients|supplementary-ingredients)-([a-z]+)-(\\d)$");

    private CoiItemResolver() {}

    public static boolean isMultiItem(String coiId) {
        return INGREDIENT_ITEM.matcher(coiId).matches();
    }

    /** Resolves a single COI item by its raw ID (no {@code coi:} prefix). */
    public static ItemStack resolveItem(String coiId) {
        CircleOfImaginationAPI api = CosmosIncursion.getInstance().getCoiAPI();
        if (api == null) return null;

        Matcher m = TYPED_ITEM.matcher(coiId);
        if (m.matches()) {
            String type     = m.group(1);
            String pathway  = m.group(2);
            int    sequence = Integer.parseInt(m.group(3));
            return switch (type) {
                case "char"   -> api.getChar(pathway, sequence);
                case "potion" -> api.getPotion(pathway, sequence);
                case "recipe" -> api.getRecipeBook(pathway, sequence);
                default       -> api.getIngredient(coiId);
            };
        }

        return api.getIngredient(coiId);
    }

    /**
     * Resolves one or more COI items by ID.
     * Ingredient patterns ({@code ingredients-*}, {@code main-ingredients-*},
     * {@code supplementary-ingredients-*}) return a list; everything else a singleton.
     */
    public static List<ItemStack> resolveItems(String coiId) {
        CircleOfImaginationAPI api = CosmosIncursion.getInstance().getCoiAPI();
        if (api == null) return List.of();

        Matcher m = INGREDIENT_ITEM.matcher(coiId);
        if (m.matches()) {
            String type     = m.group(1);
            String pathway  = m.group(2);
            int    sequence = Integer.parseInt(m.group(3));
            return switch (type) {
                case "main-ingredients"           -> api.getMainIngredientsForSequence(pathway, sequence);
                case "supplementary-ingredients"  -> api.getSupplementaryIngredientsForSequence(pathway, sequence);
                default                           -> api.getIngredientsForSequence(pathway, sequence);
            };
        }

        ItemStack single = resolveItem(coiId);
        return single != null ? List.of(single) : List.of();
    }
}
