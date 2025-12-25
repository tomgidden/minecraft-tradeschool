package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import cx.gid.minecraft.tradeschool.enchantment.EnchantmentProperties;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Map;

/**
 * Calculates emerald pricing for enchanted items based on material, type, and enchantments.
 */
public class ItemPricingCalculator {

    /**
     * Calculate the price a villager should pay to be taught an item.
     * Always returns 1-2 emeralds regardless of item value (as per Phase 8 requirements).
     *
     * @param professionLevel The villager's profession level
     * @return The number of emeralds to pay (1-2)
     */
    public static int calculateTeachingPayment(int professionLevel) {
        // Simple formula: 1 emerald for levels 1-3, 2 emeralds for levels 4-5
        return professionLevel >= 4 ? 2 : 1;
    }

    /**
     * Calculate the price at which a villager should sell a learned item.
     * Considers material cost, item type, and enchantment values.
     *
     * @param knowledge The item knowledge
     * @return The number of emeralds to charge (1-64)
     */
    public static int calculateSellingPrice(ItemKnowledge knowledge) {
        int basePrice = getBaseItemPrice(knowledge.getBaseItem().value());
        int enchantmentPrice = calculateEnchantmentPrice(knowledge.getEnchantments());

        // Total price, capped at 64 (max emerald stack)
        return Math.min(64, basePrice + enchantmentPrice);
    }

    /**
     * Gets the base price for an unenchanted item based on material and type.
     */
    private static int getBaseItemPrice(Item item) {
        // Netherite items (most expensive)
        if (item == Items.NETHERITE_CHESTPLATE) return 18;
        if (item == Items.NETHERITE_LEGGINGS) return 16;
        if (item == Items.NETHERITE_HELMET) return 14;
        if (item == Items.NETHERITE_BOOTS) return 12;
        if (item == Items.NETHERITE_SWORD || item == Items.NETHERITE_PICKAXE) return 15;
        if (item == Items.NETHERITE_AXE || item == Items.NETHERITE_SHOVEL) return 13;
        if (item == Items.NETHERITE_HOE) return 11;

        // Diamond items
        if (item == Items.DIAMOND_CHESTPLATE) return 14;
        if (item == Items.DIAMOND_LEGGINGS) return 12;
        if (item == Items.DIAMOND_HELMET) return 10;
        if (item == Items.DIAMOND_BOOTS) return 8;
        if (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE) return 11;
        if (item == Items.DIAMOND_AXE || item == Items.DIAMOND_SHOVEL) return 9;
        if (item == Items.DIAMOND_HOE) return 7;

        // Iron items (least expensive)
        if (item == Items.IRON_CHESTPLATE) return 8;
        if (item == Items.IRON_LEGGINGS) return 7;
        if (item == Items.IRON_HELMET) return 6;
        if (item == Items.IRON_BOOTS) return 5;
        if (item == Items.IRON_SWORD || item == Items.IRON_PICKAXE) return 6;
        if (item == Items.IRON_AXE || item == Items.IRON_SHOVEL) return 5;
        if (item == Items.IRON_HOE) return 4;

        // Ranged weapons (for Fletchers)
        if (item == Items.BOW) return 5;
        if (item == Items.CROSSBOW) return 6;

        // Default for unknown items
        return 5;
    }

    /**
     * Calculates the additional price contributed by enchantments.
     * Higher level enchantments add more value.
     * Tradable treasure enchantments (Mending, Frost Walker) have double price.
     * Max-level-1 enchantments (Silk Touch, Mending, Flame) are treated as level 3 for pricing.
     */
    private static int calculateEnchantmentPrice(Map<Holder<Enchantment>, Integer> enchantments) {
        int totalPrice = 0;

        for (Map.Entry<Holder<Enchantment>, Integer> entry : enchantments.entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            int actualLevel = entry.getValue();

            // Get effective level (max-level-1 enchantments are treated as level 3)
            int effectiveLevel = EnchantmentProperties.getEffectiveLevel(enchantment, actualLevel);

            int enchantmentValue = getEnchantmentValue(enchantment);

            // Price increases exponentially with effective level
            // Level 1: base value * 1
            // Level 2: base value * 2
            // Level 3: base value * 3
            // Level 4: base value * 5
            // Level 5: base value * 8
            int multiplier = switch (effectiveLevel) {
                case 1 -> 1;
                case 2 -> 2;
                case 3 -> 3;
                case 4 -> 5;
                case 5 -> 8;
                default -> 1;
            };

            int enchantmentPrice = enchantmentValue * multiplier;

            // Double the price for tradable treasure enchantments (Mending, Frost Walker)
            if (EnchantmentProperties.isTradableTreasure(enchantment)) {
                enchantmentPrice *= 2;
            }

            totalPrice += enchantmentPrice;
        }

        return totalPrice;
    }

    /**
     * Gets the base value of an enchantment for pricing purposes.
     * Rare/powerful enchantments are worth more.
     */
    private static int getEnchantmentValue(Holder<Enchantment> enchantment) {
        String enchantId = enchantment.unwrapKey()
            .map(key -> key.toString())
            .orElse("");

        // Treasure enchantments (highest value)
        if (enchantId.contains("mending") || enchantId.contains("swift_sneak")) {
            return 8;
        }

        // Very rare enchantments
        if (enchantId.contains("infinity") || enchantId.contains("silk_touch") ||
            enchantId.contains("channeling") || enchantId.contains("frost_walker")) {
            return 6;
        }

        // Rare enchantments
        if (enchantId.contains("fortune") || enchantId.contains("looting") ||
            enchantId.contains("sharpness") || enchantId.contains("protection")) {
            return 4;
        }

        // Common enchantments
        if (enchantId.contains("unbreaking") || enchantId.contains("efficiency")) {
            return 3;
        }

        // Basic enchantments
        return 2;
    }
}
