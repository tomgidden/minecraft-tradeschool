package cx.gid.minecraft.tradeschool.loot.config;

import java.util.HashMap;
import java.util.Map;

/// Root configuration object for loot table distribution.
/// Loaded from loot_distribution.json and used to configure enchanted book
/// generation across all structures.
///
/// This describes *loot tables* only. Everything an operator tunes about the mod's
/// behaviour lives in `config/tradeschool.json` instead — see
/// [cx.gid.minecraft.tradeschool.config.Config]. The two were once one file, which
/// meant settings were edited inside a data file the operator was otherwise told not to
/// touch.
public class LootConfig {
    public int version;

        /// Highest enchantment level each tier may yield, keyed by tier name.
    ///
    /// Loot data rather than a setting: it is what ties enchantment strength to how far the
    /// player has travelled, and it only makes sense alongside the structures that name
    /// those tiers. Absent keys fall back to the levels in
    /// [cx.gid.minecraft.tradeschool.loot.tier.StructureTier].
    public Map<String, Integer> maxEnchantmentLevelByTier;

    public Map<String, CategoryConfig> categories;
    public Map<String, StructureConfig> structures;

        ///  The configured ceiling for `tier`, or `fallback` if it names none.
    public int maxEnchantmentLevelFor(
            cx.gid.minecraft.tradeschool.loot.tier.StructureTier tier, int fallback) {
        if (maxEnchantmentLevelByTier == null) return fallback;
        Integer configured = maxEnchantmentLevelByTier.get(tier.name());
        return configured != null ? configured : fallback;
    }

    public LootConfig() {
        // Required for Gson deserialization
        this.structures = new HashMap<>();
        this.categories = new HashMap<>();
    }

        /// Creates a default configuration with minimal settings.
    /// Used as fallback if config file fails to load.
    ///
    /// @return A basic valid configuration
    public static LootConfig createDefault() {
        LootConfig config = new LootConfig();
        config.version = 1;
        config.categories = new HashMap<>();
        config.structures = new HashMap<>();
        return config;
    }

        /// Gets the structure configuration for a given loot table ID.
    ///
    /// @param lootTableId The loot table ID (e.g., "minecraft:chests/ancient_city")
    /// @return The structure config, or null if not configured
    public StructureConfig getStructure(String lootTableId) {
        return structures.get(lootTableId);
    }
}
