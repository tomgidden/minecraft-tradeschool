package cx.gid.minecraft.tradeschool.loot.config;

import java.util.List;

/**
 * Configuration for an enchantment category, defining which enchantments
 * belong to the category and their default weight.
 */
public class CategoryConfig {
    public List<String> enchantments;
    public int defaultWeight;

    public CategoryConfig() {
        // Required for Gson deserialization
    }

    public CategoryConfig(List<String> enchantments, int defaultWeight) {
        this.enchantments = enchantments;
        this.defaultWeight = defaultWeight;
    }
}
