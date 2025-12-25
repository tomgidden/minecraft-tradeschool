package cx.gid.minecraft.tradeschool.loot.config;

/**
 * Represents the weight and level configuration for a specific enchantment
 * in a structure's loot table.
 */
public class EnchantmentWeight {
    public int weight;
    public Integer maxLevel; // null means use enchantment's natural max level

    public EnchantmentWeight() {
        // Required for Gson deserialization
    }

    public EnchantmentWeight(int weight, Integer maxLevel) {
        this.weight = weight;
        this.maxLevel = maxLevel;
    }

    public int getWeight() {
        return weight;
    }

    public Integer getMaxLevel() {
        return maxLevel;
    }
}
