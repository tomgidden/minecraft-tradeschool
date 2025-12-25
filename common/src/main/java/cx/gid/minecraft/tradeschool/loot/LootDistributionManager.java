package cx.gid.minecraft.tradeschool.loot;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.builder.EnchantedBookBuilder;
import cx.gid.minecraft.tradeschool.loot.category.EnchantmentCategory;
import cx.gid.minecraft.tradeschool.loot.config.*;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Manages loot table modifications for enchanted book distribution.
 * Singleton that coordinates the injection of structure/biome-specific
 * enchanted books into loot tables.
 */
public class LootDistributionManager {
    private static LootDistributionManager instance;

    private LootConfig lootConfig;
    private boolean initialized = false;

    // Statistics tracking
    private int totalModifications = 0;
    private int totalEnchantmentEntries = 0;
    private int curseApplications = 0;

    private LootDistributionManager() {
    }

    public static LootDistributionManager getInstance() {
        if (instance == null) {
            instance = new LootDistributionManager();
        }
        return instance;
    }

    /**
     * Gets the loaded configuration.
     * If not initialized, returns a default configuration.
     *
     * @return The loot configuration
     */
    public LootConfig getConfig() {
        if (lootConfig == null) {
            return LootConfig.createDefault();
        }
        return lootConfig;
    }

    /**
     * Checks if the manager has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Early initialization during loot table loading (without server instance).
     * Loads configuration so loot tables can be modified.
     */
    public void initializeEarly() {
        if (initialized) {
            return;
        }

        Constants.LOGGER.info("Early initializing LootDistributionManager (during loot table load)");

        // Load configuration from JSON
        this.lootConfig = LootConfigLoader.load();

        if (lootConfig == null) {
            Constants.LOGGER.error("Failed to load loot config, using default");
            this.lootConfig = LootConfig.createDefault();
        }

        this.initialized = true;

        // Count enabled structures
        int enabledStructures = 0;
        for (StructureConfig config : lootConfig.structures.values()) {
            if (config.enabled) {
                enabledStructures++;
            }
        }

        Constants.LOGGER.info("LootDistributionManager early initialized with {} total structures ({} enabled)",
            lootConfig.structures.size(), enabledStructures);
    }

    /**
     * Initializes the loot distribution system by loading configuration.
     * If already initialized early (during loot table load), this is a no-op.
     *
     * @param server The Minecraft server instance
     */
    public void initialize(MinecraftServer server) {
        if (initialized) {
            Constants.LOGGER.info("LootDistributionManager already initialized, skipping");
            return;
        }

        // If not already initialized, do it now
        initializeEarly();
    }

    /**
     * Modifies a loot table by injecting enchanted books based on configuration.
     * Called by the Fabric loot table event handler.
     *
     * @param lootTableId The ID of the loot table being modified
     * @param tableBuilder The loot table builder to modify
     * @param registries The registry access for enchantment lookups
     */
    public void modifyLootTable(
        String lootTableId,
        LootTable.Builder tableBuilder,
        HolderLookup.Provider registries
    ) {
        if (!initialized) {
            Constants.LOGGER.warn("LootDistributionManager not initialized, skipping modification");
            return;
        }

        Constants.LOGGER.debug("Checking loot table: {}", lootTableId);

        StructureConfig config = lootConfig.getStructure(lootTableId);
        if (config == null) {
            // Not a configured structure
            return;
        }

        if (!config.enabled) {
            Constants.LOGGER.debug("Loot table {} is disabled in config", lootTableId);
            return;
        }

        Constants.LOGGER.info("Modifying loot table: {} (tier: {}, rolls: {})",
            lootTableId, config.tier, config.rollsPerChest);

        // Remove vanilla enchanted books if configured
        if (config.removeVanillaBooks) {
            // Note: Actual removal would require accessing loot pools
            // For now we'll just add our own pool which will dominate
            Constants.LOGGER.debug("Vanilla book removal configured for {}", lootTableId);
        }

        // Inject our enchanted books
        int entriesAdded = injectEnchantedBooks(tableBuilder, lootTableId, config, registries);

        totalModifications++;
        totalEnchantmentEntries += entriesAdded;
    }

    /**
     * Gets the tier for a loot table (used by Fabric handler to apply curse to existing pools).
     */
    public StructureTier getTierForLootTable(String lootTableId) {
        StructureConfig config = lootConfig.getStructure(lootTableId);
        if (config == null || !config.enabled) {
            return null;
        }
        StructureTier tier = config.getTier();
        return tier != null ? tier : StructureTier.MEDIUM;
    }

    /**
     * Injects enchanted books into a loot table based on structure configuration.
     *
     * @return The number of enchantment entries added
     */
    private int injectEnchantedBooks(
        LootTable.Builder tableBuilder,
        String lootTableId,
        StructureConfig config,
        HolderLookup.Provider registries
    ) {
        // Build list of enchantments to include
        List<EnchantmentEntry> enchantments = buildEnchantmentList(config);

        if (enchantments.isEmpty()) {
            Constants.LOGGER.warn("No enchantments configured for {} - skipping", lootTableId);
            return 0;
        }

        // Get structure tier for curse probability
        StructureTier tier = config.getTier();
        if (tier == null) {
            Constants.LOGGER.warn("Invalid tier for {}, defaulting to MEDIUM", lootTableId);
            tier = StructureTier.MEDIUM;
        }

        // Create loot pool for enchanted books
        LootPool.Builder poolBuilder = LootPool.lootPool()
            .setRolls(ConstantValue.exactly(config.rollsPerChest))
            .setBonusRolls(ConstantValue.exactly(0));

        int entriesAdded = 0;
        int maxLevel = config.getMaxEnchantmentLevel();

        // Add entries for each enchantment at each level
        for (EnchantmentEntry entry : enchantments) {
            int enchantMaxLevel = entry.maxLevel != null ? entry.maxLevel : maxLevel;
            enchantMaxLevel = Math.min(enchantMaxLevel, maxLevel); // Cap by tier

            for (int level = 1; level <= enchantMaxLevel; level++) {
                // Calculate weight - decreases exponentially with level
                int weight = calculateWeight(entry.weight, level, enchantMaxLevel);

                // Create enchanted book WITHOUT curse (curse applied dynamically via loot function)
                ItemStack book = EnchantedBookBuilder.create(
                    entry.enchantmentId,
                    level,
                    registries
                );

                // Add to loot pool with curse function applied dynamically
                poolBuilder.add(LootItem.lootTableItem(book.getItem())
                    .setWeight(weight)
                    .apply(SetComponentsFunction.setComponent(DataComponents.STORED_ENCHANTMENTS,
                        book.get(DataComponents.STORED_ENCHANTMENTS)))
                    .apply(ApplyCurseOfCopyrightFunction.applyCurse(tier))
                );

                Constants.LOGGER.debug("Added {} level {} to loot pool with curse function (tier: {})",
                    entry.enchantmentId, level, tier);

                entriesAdded++;
            }
        }

        tableBuilder.withPool(poolBuilder);

        Constants.LOGGER.info("Added {} enchanted book entries to {} ({} unique enchantments, max level {})",
            entriesAdded, lootTableId, enchantments.size(), maxLevel);

        return entriesAdded;
    }

    /**
     * Builds the list of enchantments that should appear in this structure.
     */
    private List<EnchantmentEntry> buildEnchantmentList(StructureConfig config) {
        List<EnchantmentEntry> result = new ArrayList<>();

        // Add custom enchantments (highest priority)
        if (config.customEnchantments != null) {
            for (Map.Entry<String, EnchantmentWeight> entry : config.customEnchantments.entrySet()) {
                result.add(new EnchantmentEntry(
                    entry.getKey(),
                    entry.getValue().getWeight(),
                    entry.getValue().getMaxLevel()
                ));
            }
        }

        // Add enchantments from primary categories
        if (config.primaryCategories != null) {
            for (String categoryName : config.primaryCategories) {
                addEnchantmentsFromCategory(categoryName, result, false);
            }
        }

        // Add enchantments from crossover categories (with probability check)
        // For now, we'll add them all but with reduced weight
        if (config.crossoverCategories != null) {
            for (Map.Entry<String, Double> entry : config.crossoverCategories.entrySet()) {
                addEnchantmentsFromCategory(entry.getKey(), result, true);
            }
        }

        return result;
    }

    /**
     * Adds enchantments from a category to the result list.
     */
    private void addEnchantmentsFromCategory(
        String categoryName,
        List<EnchantmentEntry> result,
        boolean isCrossover
    ) {
        // Try to find category in config
        CategoryConfig category = lootConfig.categories.get(categoryName);
        if (category != null) {
            for (String enchantmentId : category.enchantments) {
                // Check if not already added
                if (result.stream().noneMatch(e -> e.enchantmentId.equals(enchantmentId))) {
                    int weight = isCrossover ? category.defaultWeight / 2 : category.defaultWeight;
                    result.add(new EnchantmentEntry(enchantmentId, weight, null));
                }
            }
            return;
        }

        // Try to find enum category
        try {
            EnchantmentCategory enumCategory = EnchantmentCategory.valueOf(categoryName.toUpperCase());
            for (String enchantmentId : enumCategory.getEnchantmentIds()) {
                if (result.stream().noneMatch(e -> e.enchantmentId.equals(enchantmentId))) {
                    int weight = isCrossover ? 5 : 10; // Default weights
                    result.add(new EnchantmentEntry(enchantmentId, weight, null));
                }
            }
        } catch (IllegalArgumentException e) {
            Constants.LOGGER.warn("Unknown category: {}", categoryName);
        }
    }

    /**
     * Calculates the weight for an enchantment at a given level.
     * Higher levels are exponentially rarer.
     *
     * @param baseWeight The base weight from configuration
     * @param currentLevel The current enchantment level
     * @param maxLevel The maximum level for this enchantment
     * @return The calculated weight
     */
    private int calculateWeight(int baseWeight, int currentLevel, int maxLevel) {
        // Formula: baseWeight / (2^(currentLevel - 1))
        // Level 1: baseWeight
        // Level 2: baseWeight / 2
        // Level 3: baseWeight / 4
        // Level 4: baseWeight / 8
        // Level 5: baseWeight / 16
        int weight = baseWeight >> (currentLevel - 1);
        return Math.max(1, weight); // Minimum weight of 1
    }

    /**
     * Internal class for tracking enchantment entries during list building.
     */
    private static class EnchantmentEntry {
        final String enchantmentId;
        final int weight;
        final Integer maxLevel;

        EnchantmentEntry(String enchantmentId, int weight, Integer maxLevel) {
            this.enchantmentId = enchantmentId;
            this.weight = weight;
            this.maxLevel = maxLevel;
        }
    }

    /**
     * Gets statistics about loot modifications.
     * Useful for debugging and testing.
     */
    public String getStatistics() {
        return String.format(
            "LootDistributionManager Statistics: %d loot tables modified, %d enchantment entries added, %d curses applied",
            totalModifications, totalEnchantmentEntries, curseApplications
        );
    }

    /**
     * Resets statistics counters.
     */
    public void resetStatistics() {
        totalModifications = 0;
        totalEnchantmentEntries = 0;
        curseApplications = 0;
    }
}
