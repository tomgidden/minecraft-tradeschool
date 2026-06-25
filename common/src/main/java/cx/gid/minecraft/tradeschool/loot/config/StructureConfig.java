package cx.gid.minecraft.tradeschool.loot.config;

import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a specific structure's loot table modifications.
 * Defines which enchantments appear, their weights, and tier settings.
 */
public class StructureConfig {
    public boolean enabled = true;
    public String tier; // "LOW", "MEDIUM", "HIGH", "TOP"
    public Integer maxLevelOverride; // Override tier's max level
    public int rollsPerChest = 1;
    public float bookChance = 1.0f;
    public boolean removeVanillaBooks = true;
    public List<String> primaryCategories;
    public Map<String, Double> crossoverCategories; // Category name -> probability
    public Map<String, EnchantmentWeight> customEnchantments; // Enchantment ID -> weight config

    public StructureConfig() {
        // Required for Gson deserialization
    }

    /**
     * Gets the structure tier enum value.
     *
     * @return The tier, or null if tier string is invalid
     */
    public StructureTier getTier() {
        if (tier == null) {
            return null;
        }
        try {
            return StructureTier.valueOf(tier.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Gets the maximum enchantment level for this structure.
     * Uses override if set, otherwise uses tier's default.
     *
     * @return The maximum enchantment level
     */
    public int getMaxEnchantmentLevel() {
        if (maxLevelOverride != null) {
            return maxLevelOverride;
        }
        StructureTier tierEnum = getTier();
        if (tierEnum != null) {
            return tierEnum.getMaxEnchantmentLevel();
        }
        return 5; // Default to max if tier invalid
    }
}
