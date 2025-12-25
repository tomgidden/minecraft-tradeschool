package cx.gid.minecraft.tradeschool.loot.category;

import java.util.List;

/**
 * Categories of enchantments based on their thematic grouping.
 * Used to distribute enchantments across different biomes and structure types.
 * Based on Minecraft's Villager Trade Rebalance enchantment groupings.
 */
public enum EnchantmentCategory {
    WATER(List.of(
        "minecraft:aqua_affinity",
        "minecraft:depth_strider",
        "minecraft:respiration",
        "minecraft:frost_walker",
        "minecraft:riptide",
        "minecraft:loyalty",
        "minecraft:channeling",
        "minecraft:impaling",
        "minecraft:luck_of_the_sea",
        "minecraft:lure"
    )),

    NETHER(List.of(
        "minecraft:fire_protection",
        "minecraft:fire_aspect",
        "minecraft:flame",
        "minecraft:soul_speed"
    )),

    COMBAT(List.of(
        "minecraft:sharpness",
        "minecraft:smite",
        "minecraft:bane_of_arthropods",
        "minecraft:knockback",
        "minecraft:sweeping_edge",
        "minecraft:looting",
        "minecraft:power",
        "minecraft:punch",
        "minecraft:piercing",
        "minecraft:multishot",
        "minecraft:quick_charge"
    )),

    PROTECTION(List.of(
        "minecraft:protection",
        "minecraft:blast_protection",
        "minecraft:projectile_protection",
        "minecraft:thorns",
        "minecraft:feather_falling"
    )),

    UTILITY(List.of(
        "minecraft:efficiency",
        "minecraft:fortune",
        "minecraft:silk_touch",
        "minecraft:unbreaking"
    )),

    RARE(List.of(
        "minecraft:mending",
        "minecraft:swift_sneak",
        "minecraft:wind_burst",
        "minecraft:breach",
        "minecraft:density"
    ));

    private final List<String> enchantmentIds;

    EnchantmentCategory(List<String> enchantmentIds) {
        this.enchantmentIds = enchantmentIds;
    }

    /**
     * Checks if this category contains the given enchantment ID.
     *
     * @param enchantmentId The enchantment ID to check (e.g., "minecraft:sharpness")
     * @return true if this category contains the enchantment
     */
    public boolean contains(String enchantmentId) {
        return enchantmentIds.contains(enchantmentId);
    }

    /**
     * Gets all enchantment IDs in this category.
     *
     * @return Unmodifiable list of enchantment IDs
     */
    public List<String> getEnchantmentIds() {
        return enchantmentIds;
    }
}
