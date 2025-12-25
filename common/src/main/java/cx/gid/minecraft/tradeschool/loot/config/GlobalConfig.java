package cx.gid.minecraft.tradeschool.loot.config;

import java.util.Map;

/**
 * Global configuration settings that apply to all loot table modifications and villager trading.
 */
public class GlobalConfig {
    public Map<String, Integer> defaultTierMaxLevels;
    public double defaultCrossoverChance;
    public boolean removeAllVanillaEnchantedBooks;

    /**
     * Whether villagers can learn non-tradable treasure enchantments
     * (Soul Speed, Swift Sneak, Wind Burst).
     * Default: false (these enchantments cannot be learned).
     */
    public boolean allowNonTradableTreasure;

    public GlobalConfig() {
        // Required for Gson deserialization
        this.allowNonTradableTreasure = false;
    }

    public GlobalConfig(
        Map<String, Integer> defaultTierMaxLevels,
        double defaultCrossoverChance,
        boolean removeAllVanillaEnchantedBooks,
        boolean allowNonTradableTreasure
    ) {
        this.defaultTierMaxLevels = defaultTierMaxLevels;
        this.defaultCrossoverChance = defaultCrossoverChance;
        this.removeAllVanillaEnchantedBooks = removeAllVanillaEnchantedBooks;
        this.allowNonTradableTreasure = allowNonTradableTreasure;
    }
}
