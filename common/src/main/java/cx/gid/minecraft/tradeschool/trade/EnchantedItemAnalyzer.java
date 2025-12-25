package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import cx.gid.minecraft.tradeschool.enchantment.EnchantmentProperties;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.HashMap;
import java.util.Map;

/**
 * Analyzes enchanted items to extract enchantment data and validate materials.
 */
public class EnchantedItemAnalyzer {

    /**
     * Material tier for validation based on villager level.
     */
    public enum MaterialTier {
        IRON(1),      // Can be taught at any level
        DIAMOND(4),   // Requires Expert (4) or Master (5)
        NETHERITE(5); // Requires Master (5) only

        private final int minimumLevel;

        MaterialTier(int minimumLevel) {
            this.minimumLevel = minimumLevel;
        }

        public int getMinimumLevel() {
            return minimumLevel;
        }
    }

    /**
     * Gets the material tier of an item.
     *
     * @param item The item to check
     * @return The material tier, or null if not a valid teachable item
     */
    public static MaterialTier getMaterialTier(Item item) {
        // Netherite items
        if (item == Items.NETHERITE_SWORD || item == Items.NETHERITE_PICKAXE ||
            item == Items.NETHERITE_AXE || item == Items.NETHERITE_SHOVEL ||
            item == Items.NETHERITE_HOE || item == Items.NETHERITE_HELMET ||
            item == Items.NETHERITE_CHESTPLATE || item == Items.NETHERITE_LEGGINGS ||
            item == Items.NETHERITE_BOOTS) {
            return MaterialTier.NETHERITE;
        }

        // Diamond items
        if (item == Items.DIAMOND_SWORD || item == Items.DIAMOND_PICKAXE ||
            item == Items.DIAMOND_AXE || item == Items.DIAMOND_SHOVEL ||
            item == Items.DIAMOND_HOE || item == Items.DIAMOND_HELMET ||
            item == Items.DIAMOND_CHESTPLATE || item == Items.DIAMOND_LEGGINGS ||
            item == Items.DIAMOND_BOOTS) {
            return MaterialTier.DIAMOND;
        }

        // Iron items
        if (item == Items.IRON_SWORD || item == Items.IRON_PICKAXE ||
            item == Items.IRON_AXE || item == Items.IRON_SHOVEL ||
            item == Items.IRON_HOE || item == Items.IRON_HELMET ||
            item == Items.IRON_CHESTPLATE || item == Items.IRON_LEGGINGS ||
            item == Items.IRON_BOOTS) {
            return MaterialTier.IRON;
        }

        // Bows and Crossbows (for Fletchers) - treat as IRON tier (can be taught at any level)
        if (item == Items.BOW || item == Items.CROSSBOW) {
            return MaterialTier.IRON;
        }

        return null; // Not a teachable item
    }

    /**
     * Checks if an item can be taught to a villager at their current level.
     * This checks both material tier requirements AND enchantment requirements.
     *
     * @param stack The item stack to check (with enchantments)
     * @param villagerLevel The villager's profession level (1-5)
     * @return true if the item can be taught at this level
     */
    public static boolean canTeachAtLevel(ItemStack stack, int villagerLevel) {
        // Check material tier
        MaterialTier tier = getMaterialTier(stack.getItem());
        if (tier == null) {
            return false;
        }
        if (villagerLevel < tier.getMinimumLevel()) {
            return false;
        }

        // Check config setting for non-tradable treasure enchantments
        boolean allowNonTradableTreasure = LootDistributionManager.getInstance()
            .getConfig()
            .global
            .allowNonTradableTreasure;

        // Check enchantment requirements
        // Max-level-1 enchantments (Silk Touch, Mending, etc.) require at least level 3
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Constants.LOGGER.info("Item {} has {} enchantments to validate", stack.getItem(), enchantments.size());

        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            int level = enchantments.getLevel(enchantment);
            int effectiveLevel = EnchantmentProperties.getEffectiveLevel(enchantment, level);
            int minimumVillagerLevel = EnchantmentProperties.getMinimumVillagerLevel(enchantment, level);

            String enchName = enchantment.unwrapKey().map(k -> k.toString()).orElse("unknown");
            Constants.LOGGER.info("  Enchantment: {}, Level: {}, Effective: {}, MinVillagerLevel: {}, VillagerLevel: {}",
                enchName, level, effectiveLevel, minimumVillagerLevel, villagerLevel);

            if (villagerLevel < minimumVillagerLevel) {
                Constants.LOGGER.info("  REJECTED: Villager level {} < required {}", villagerLevel, minimumVillagerLevel);
                return false; // Villager level too low for this enchantment
            }

            // Check for non-tradable treasure enchantments (Soul Speed, Swift Sneak, Wind Burst)
            if (!EnchantmentProperties.isTradable(enchantment) && !allowNonTradableTreasure) {
                Constants.LOGGER.info("  REJECTED: Non-tradable treasure enchantment not allowed: {}", enchName);
                return false; // Non-tradable treasure not allowed by config
            }
        }

        return true;
    }

    /**
     * Checks if an item (without enchantments) can be taught to a villager at their current level.
     * This only checks material tier requirements.
     *
     * @param item The item to check
     * @param villagerLevel The villager's profession level (1-5)
     * @return true if the item can be taught at this level
     */
    public static boolean canTeachAtLevel(Item item, int villagerLevel) {
        MaterialTier tier = getMaterialTier(item);
        if (tier == null) {
            return false;
        }
        return villagerLevel >= tier.getMinimumLevel();
    }

    /**
     * Creates ItemKnowledge from an ItemStack, capping enchantment levels by villager level
     * and preserving relevant item attributes (damage, repair cost, trim, custom name, etc.).
     *
     * @param stack The enchanted item stack
     * @param villagerLevel The villager's current profession level
     * @return ItemKnowledge with capped enchantment levels and preserved attributes
     */
    public static ItemKnowledge analyzeItem(ItemStack stack, int villagerLevel) {
        // Get base item
        Holder<Item> itemHolder = stack.getItemHolder();

        // Extract and cap enchantments
        Map<Holder<Enchantment>, Integer> cappedEnchantments = new HashMap<>();
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            int originalLevel = enchantments.getLevel(enchantment);
            // Cap enchantment level by villager's current profession level
            int cappedLevel = Math.min(originalLevel, villagerLevel);
            cappedEnchantments.put(enchantment, cappedLevel);

            if (cappedLevel < originalLevel) {
                Constants.LOGGER.debug("Capped enchantment {} from level {} to {} for villager level {}",
                    enchantment.unwrapKey().map(k -> k.toString()).orElse("unknown"),
                    originalLevel, cappedLevel, villagerLevel);
            }
        }

        // Preserve relevant item attributes (damage, repair cost, trim, custom name, etc.)
        // We build a patch that includes everything except the enchantments themselves
        // since we handle enchantments separately with capping
        DataComponentPatch.Builder preservedBuilder = DataComponentPatch.builder();

        // Preserve these specific components if they exist on the item:
        // - DAMAGE: Item durability state
        // - REPAIR_COST: Anvil repair cost
        // - TRIM: Armor trim
        // - CUSTOM_NAME: Custom item name
        // - CUSTOM_MODEL_DATA: Custom model data
        // - CUSTOM_DATA: Custom NBT data
        // - DYED_COLOR: Leather armor color
        // - ITEM_NAME: Item name component
        // - LORE: Item lore text

        if (stack.has(DataComponents.DAMAGE)) {
            preservedBuilder.set(DataComponents.DAMAGE, stack.get(DataComponents.DAMAGE));
        }
        if (stack.has(DataComponents.REPAIR_COST)) {
            preservedBuilder.set(DataComponents.REPAIR_COST, stack.get(DataComponents.REPAIR_COST));
        }
        if (stack.has(DataComponents.TRIM)) {
            preservedBuilder.set(DataComponents.TRIM, stack.get(DataComponents.TRIM));
        }
        if (stack.has(DataComponents.CUSTOM_NAME)) {
            preservedBuilder.set(DataComponents.CUSTOM_NAME, stack.get(DataComponents.CUSTOM_NAME));
        }
        if (stack.has(DataComponents.CUSTOM_MODEL_DATA)) {
            preservedBuilder.set(DataComponents.CUSTOM_MODEL_DATA, stack.get(DataComponents.CUSTOM_MODEL_DATA));
        }
        if (stack.has(DataComponents.CUSTOM_DATA)) {
            preservedBuilder.set(DataComponents.CUSTOM_DATA, stack.get(DataComponents.CUSTOM_DATA));
        }
        if (stack.has(DataComponents.DYED_COLOR)) {
            preservedBuilder.set(DataComponents.DYED_COLOR, stack.get(DataComponents.DYED_COLOR));
        }
        if (stack.has(DataComponents.ITEM_NAME)) {
            preservedBuilder.set(DataComponents.ITEM_NAME, stack.get(DataComponents.ITEM_NAME));
        }
        if (stack.has(DataComponents.LORE)) {
            preservedBuilder.set(DataComponents.LORE, stack.get(DataComponents.LORE));
        }

        return new ItemKnowledge(itemHolder, cappedEnchantments, villagerLevel, preservedBuilder.build());
    }

    /**
     * Checks if an item is appropriate for a given profession.
     *
     * @param item The item to check
     * @param profession The villager profession ID (may be a Reference string)
     * @return true if this profession can teach this item
     */
    public static boolean isItemForProfession(Item item, String profession) {
        // Use .contains() because profession might be a Reference object string
        if (profession.contains("weaponsmith")) {
            return isWeapon(item);
        } else if (profession.contains("toolsmith")) {
            return isTool(item);
        } else if (profession.contains("armorer")) {
            return isArmor(item);
        } else if (profession.contains("fletcher")) {
            return isRangedWeapon(item);
        }
        return false;
    }

    private static boolean isWeapon(Item item) {
        return item == Items.IRON_SWORD || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD ||
               item == Items.IRON_AXE || item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE;
    }

    private static boolean isTool(Item item) {
        return item == Items.IRON_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE ||
               item == Items.IRON_SHOVEL || item == Items.DIAMOND_SHOVEL || item == Items.NETHERITE_SHOVEL ||
               item == Items.IRON_HOE || item == Items.DIAMOND_HOE || item == Items.NETHERITE_HOE;
    }

    private static boolean isArmor(Item item) {
        return item == Items.IRON_HELMET || item == Items.DIAMOND_HELMET || item == Items.NETHERITE_HELMET ||
               item == Items.IRON_CHESTPLATE || item == Items.DIAMOND_CHESTPLATE || item == Items.NETHERITE_CHESTPLATE ||
               item == Items.IRON_LEGGINGS || item == Items.DIAMOND_LEGGINGS || item == Items.NETHERITE_LEGGINGS ||
               item == Items.IRON_BOOTS || item == Items.DIAMOND_BOOTS || item == Items.NETHERITE_BOOTS;
    }

    private static boolean isRangedWeapon(Item item) {
        return item == Items.BOW || item == Items.CROSSBOW;
    }
}
