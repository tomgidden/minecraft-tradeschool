package cx.gid.minecraft.tradeschool.loot.config;

import java.util.HashMap;
import java.util.Map;

/**
 * Root configuration object for loot table distribution.
 * Loaded from loot_distribution.json and used to configure enchanted book
 * generation across all structures.
 */
public class LootConfig {
    public int version;
    public GlobalConfig global;
    public Map<String, CategoryConfig> categories;
    public Map<String, StructureConfig> structures;

    public LootConfig() {
        // Required for Gson deserialization
        this.structures = new HashMap<>();
        this.categories = new HashMap<>();
    }

    /**
     * Creates a default configuration with minimal settings.
     * Used as fallback if config file fails to load.
     *
     * @return A basic valid configuration
     */
    public static LootConfig createDefault() {
        LootConfig config = new LootConfig();
        config.version = 1;

        Map<String, Integer> defaultTierMaxLevels = Map.of(
            "LOW", 2,
            "MEDIUM", 3,
            "HIGH", 4,
            "TOP", 5
        );

        config.global = new GlobalConfig(
            defaultTierMaxLevels,
            0.05, // defaultCrossoverChance
            true, // removeAllVanillaEnchantedBooks
            false // allowNonTradableTreasure defaults to false
        );

        config.categories = new HashMap<>();
        config.structures = new HashMap<>();

        return config;
    }

    /**
     * Gets the structure configuration for a given loot table ID.
     *
     * @param lootTableId The loot table ID (e.g., "minecraft:chests/ancient_city")
     * @return The structure config, or null if not configured
     */
    public StructureConfig getStructure(String lootTableId) {
        return structures.get(lootTableId);
    }
}
