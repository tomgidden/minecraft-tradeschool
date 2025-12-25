package cx.gid.minecraft.tradeschool.loot.config;

import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;

/**
 * Configuration for Curse of Copyright enchantment probabilities.
 * Defines how often the curse appears on enchanted books in loot.
 */
public class CurseConfig {
    /**
     * Gets the curse probability for a given structure tier.
     * Lower tier (easier/safer) structures have higher curse rates.
     * Higher tier (harder/rarer) structures have lower curse rates.
     *
     * @param tier The structure tier
     * @return Probability as a decimal (0.0 - 1.0)
     */
    public static double getCurseProbability(StructureTier tier) {
        return switch (tier) {
            case LOW -> 0.1;      // 10% - villages, fishing
            case MEDIUM -> 0.05;   // 5% - shipwrecks, temples
            case HIGH -> 0.02;     // 2% - strongholds, bastions
            case TOP -> 0.01;     // 1% - ancient cities, end cities
        };
    }
}
