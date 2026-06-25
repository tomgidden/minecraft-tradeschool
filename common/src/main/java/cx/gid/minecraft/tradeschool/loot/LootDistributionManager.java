package cx.gid.minecraft.tradeschool.loot;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.category.EnchantmentCategory;
import cx.gid.minecraft.tradeschool.loot.config.*;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.providers.number.BinomialDistributionGenerator;
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
     */
    public void modifyLootTable(
        String lootTableId,
        LootTable.Builder tableBuilder,
        HolderLookup.Provider registries
    ) {
        injectEnchantedBooks(lootTableId, tableBuilder, registries);
    }

    /**
     * Injects an enchanted book pool via the provided consumer.
     * Used by NeoForge where a LootTable.Builder is not available.
     * The consumer receives a configured LootPool.Builder to add to the loot table.
     */
    public void modifyLootTableWithConsumer(
        String lootTableId,
        java.util.function.Consumer<LootPool.Builder> poolConsumer,
        HolderLookup.Provider registries
    ) {
        if (!initialized) {
            Constants.LOGGER.warn("LootDistributionManager not initialized, skipping modification");
            return;
        }

        LootPool.Builder gearPool = buildGearPoolBuilder(lootTableId, registries);
        if (gearPool != null) {
            poolConsumer.accept(gearPool);
        }

        StructureConfig config = lootConfig.getStructure(lootTableId);
        if (config == null || !config.enabled) {
            return;
        }

        Constants.LOGGER.info("Modifying loot table: {} (tier: {}, rolls: {})",
            lootTableId, config.tier, config.rollsPerChest);

        LootPool.Builder poolBuilder = buildPoolBuilder(lootTableId, config, registries);
        if (poolBuilder != null) {
            poolConsumer.accept(poolBuilder);
            totalModifications++;
        }
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

    private void injectEnchantedBooks(
        String lootTableId,
        LootTable.Builder tableBuilder,
        HolderLookup.Provider registries
    ) {
        if (!initialized) {
            Constants.LOGGER.warn("LootDistributionManager not initialized, skipping modification");
            return;
        }

        StructureConfig config = lootConfig.getStructure(lootTableId);
        if (config == null || !config.enabled) {
            return;
        }

        Constants.LOGGER.info("Modifying loot table: {} (tier: {}, rolls: {})",
            lootTableId, config.tier, config.rollsPerChest);

        LootPool.Builder gearPool = buildGearPoolBuilder(lootTableId, registries);
        if (gearPool != null) {
            tableBuilder.withPool(gearPool);
        }

        LootPool.Builder poolBuilder = buildPoolBuilder(lootTableId, config, registries);
        if (poolBuilder != null) {
            tableBuilder.withPool(poolBuilder);
            totalModifications++;
        }
    }

    private static final String ARCHAEOLOGY_PREFIX = "minecraft:archaeology/";
    private static final String TRADESCHOOL_NAMESPACE = "tradeschool";

    private LootPool.Builder buildGearPoolBuilder(String lootTableId, HolderLookup.Provider registries) {
        if (!lootTableId.startsWith(ARCHAEOLOGY_PREFIX)) return null;

        String name = lootTableId.substring(ARCHAEOLOGY_PREFIX.length());
        ResourceKey<LootTable> gearKey = ResourceKey.create(
            Registries.LOOT_TABLE,
            Identifier.fromNamespaceAndPath(TRADESCHOOL_NAMESPACE, "archaeology/" + name)
        );

        var lookup = registries.lookup(Registries.LOOT_TABLE);
        if (lookup.isEmpty() || lookup.get().get(gearKey).isEmpty()) return null;

        return LootPool.lootPool()
            .setRolls(ConstantValue.exactly(1))
            .add(NestedLootTable.lootTableReference(gearKey));
    }

    private LootPool.Builder buildPoolBuilder(
        String lootTableId,
        StructureConfig config,
        HolderLookup.Provider registries
    ) {
        List<EnchantmentEntry> enchantments = buildEnchantmentList(config);

        if (enchantments.isEmpty()) {
            Constants.LOGGER.warn("No enchantments configured for {} - skipping", lootTableId);
            return null;
        }

        StructureTier tier = config.getTier();
        if (tier == null) {
            Constants.LOGGER.warn("Invalid tier for {}, defaulting to MEDIUM", lootTableId);
            tier = StructureTier.MEDIUM;
        }

        LootPool.Builder poolBuilder = LootPool.lootPool()
            .setRolls(config.bookChance < 1.0f
                ? BinomialDistributionGenerator.binomial(config.rollsPerChest, config.bookChance)
                : ConstantValue.exactly(config.rollsPerChest))
            .setBonusRolls(ConstantValue.exactly(0));

        int entriesAdded = 0;
        int maxLevel = config.getMaxEnchantmentLevel();

        for (EnchantmentEntry entry : enchantments) {
            int enchantMaxLevel = entry.maxLevel != null ? entry.maxLevel : maxLevel;
            enchantMaxLevel = Math.min(enchantMaxLevel, maxLevel);

            for (int level = 1; level <= enchantMaxLevel; level++) {
                int weight = calculateWeight(entry.weight, level, enchantMaxLevel);

                try {
                    Identifier enchantmentLoc = Identifier.parse(entry.enchantmentId);
                    ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchantmentKey =
                        ResourceKey.create(Registries.ENCHANTMENT, enchantmentLoc);
                    Holder<net.minecraft.world.item.enchantment.Enchantment> enchantmentHolder =
                        registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantmentKey);

                    ItemEnchantments.Mutable storedEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
                    storedEnchantments.set(enchantmentHolder, level);

                    poolBuilder.add(LootItem.lootTableItem(Items.ENCHANTED_BOOK)
                        .setWeight(weight)
                        .apply(SetComponentsFunction.setComponent(DataComponents.STORED_ENCHANTMENTS,
                            storedEnchantments.toImmutable()))
                        .apply(ApplyCurseOfCopyrightFunction.applyCurse(tier))
                    );
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to add loot entry for enchantment {} level {}",
                        entry.enchantmentId, level, e);
                    continue;
                }

                entriesAdded++;
            }
        }

        totalEnchantmentEntries += entriesAdded;

        Constants.LOGGER.info("Added {} enchanted book entries to {} ({} unique enchantments, max level {})",
            entriesAdded, lootTableId, enchantments.size(), maxLevel);

        return poolBuilder;
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
