package cx.gid.minecraft.tradeschool.loot.config;

import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;

/**
 * Configuration for Curse of Copyright enchantment probabilities.
 * Defines how often the curse appears on enchanted items in loot.
 */
public class CurseConfig {
    /**
     * Gets the curse probability.
     *
     * Flat across every source. An earlier scheme scaled this by structure tier (10% in
     * villages down to 1% in ancient cities), but a single rate is easier to reason about
     * and to explain to players: one in ten of everything you find is copyrighted,
     * wherever you found it.
     *
     * @param tier The structure tier — ignored; retained so callers that thread a tier
     *             through the loot pipeline for other purposes need not special-case this.
     * @return Probability as a decimal (0.0 - 1.0)
     */
    public static double getCurseProbability(StructureTier tier) {
        return LootDistributionManager.getInstance().getConfig().global.curseProbability;
    }
}
