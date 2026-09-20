package cx.gid.minecraft.tradeschool.loot;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.config.Config;
import cx.gid.minecraft.tradeschool.loot.category.EnchantmentCategory;
import cx.gid.minecraft.tradeschool.loot.config.*;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
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
import net.minecraft.world.level.storage.loot.entries.AlternativesEntry;
import net.minecraft.world.level.storage.loot.entries.EntryGroup;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

/// Manages loot table modifications for enchanted book distribution.
/// Singleton that coordinates the injection of structure/biome-specific
/// enchanted books into loot tables.
public class LootDistributionManager
{
  private static LootDistributionManager instance;

  private LootConfig lootConfig;
  // volatile: isInitialized() is read from loot worker threads outside the lock that
  // initializeEarly() holds, so the write has to be visible to them without it.
  private volatile boolean initialized = false;

  // Statistics tracking
  private int totalModifications      = 0;
  private int totalEnchantmentEntries = 0;
  private int curseApplications       = 0;

  private LootDistributionManager()
  {
  }

  /// The singleton.
  ///
  /// Synchronised because loot tables load on a worker pool: the plain check-then-act
  /// this replaced could hand two threads two different managers, and whichever lost
  /// would quietly hold an unshared, half-initialised copy.
  public static synchronized LootDistributionManager getInstance()
  {
    if(instance == null) {
      instance = new LootDistributionManager();
    }
    return instance;
  }

  /// Gets the loaded configuration.
  /// If not initialized, returns a default configuration.
  ///
  /// @return The loot configuration
  public LootConfig getConfig()
  {
    if(lootConfig == null) {
      return LootConfig.createDefault();
    }
    return lootConfig;
  }

  /// Checks if the manager has been initialized.
  public boolean isInitialized()
  {
    return initialized;
  }

  /// Early initialization during loot table loading (without server instance).
  /// Loads configuration so loot tables can be modified.
  public synchronized void initializeEarly()
  {
    // Synchronised, and the flag is checked *inside* the lock, because loot tables
    // load on a worker pool: a dozen threads reach here at once. The plain
    // check-then-act this replaced let them all pass the guard before any of them
    // reached `initialized = true`, so every one of them re-ran the whole load -- and
    // Config.load() rewrites the config file, so they were also writing over each
    // other. That is how a valid tradeschool.json ended up quarantined as
    // `.broken-<timestamp>` on Fabric during the 26.3 work.
    if(initialized) {
      return;
    }

    Constants.LOGGER.info("Early initializing LootDistributionManager (during loot table load)");

    // Settings first: loot tables are built below, and the curse rate is a setting.
    Config.load();

    // Load loot table definitions from JSON
    this.lootConfig = LootConfigLoader.load();

    if(lootConfig == null) {
      Constants.LOGGER.error("Failed to load loot config, using default");
      this.lootConfig = LootConfig.createDefault();
    }

    this.initialized = true;

    // Count enabled structures
    int enabledStructures = 0;
    for(StructureConfig config: lootConfig.structures.values()) {
      if(config.enabled) {
        enabledStructures++;
      }
    }

    Constants.LOGGER.info("LootDistributionManager early initialized with {} total structures ({} enabled)", lootConfig.structures.size(), enabledStructures);
  }

  /// Initializes the loot distribution system by loading configuration.
  /// If already initialized early (during loot table load), this is a no-op.
  ///
  /// @param server The Minecraft server instance
  public void initialize(MinecraftServer server)
  {
    if(initialized) {
      Constants.LOGGER.info("LootDistributionManager already initialized, skipping");
      return;
    }

    // If not already initialized, do it now
    initializeEarly();
  }

  /// Modifies a loot table by injecting enchanted books based on configuration.
  /// Called by the Fabric loot table event handler.
  public void modifyLootTable(
      String lootTableId,
      LootTable.Builder tableBuilder,
      HolderGetter.Provider registries
  )
  {
    // See modifyLootTableWithConsumer: archaeology cannot take extra pools.
    if(isArchaeology(lootTableId)) return;
    injectEnchantedBooks(lootTableId, tableBuilder, registries);
  }

  /// This mod's pool for a brushed block, or null to leave the table alone.
  ///
  /// **A brushed block keeps only one item**, so this cannot simply be added alongside
  /// vanilla's pool -- see [#isArchaeology]. The two are made mutually exclusive instead:
  /// this pool carries a random-chance condition, and the caller puts the *inverted*
  /// condition on vanilla's existing pool via its own builder. Exactly one of them
  /// yields, this one gets first refusal, and no warning is logged.
  ///
  /// [#archaeologyChance] is the shared probability. Both loaders must use the same
  /// value on both pools or the same seed gives different loot on each.
  public LootPool.Builder buildArchaeologyOverridePool(
      String lootTableId,
      HolderGetter.Provider registries
  )
  {
    if(!initialized || !isArchaeology(lootTableId)) return null;
    StructureConfig config = lootConfig.getStructure(lootTableId);
    LootPool.Builder pool  = buildArchaeologyPool(lootTableId, config, registries);
    if(pool != null) totalModifications++;
    return pool;
  }

  /// How often a brushed block yields this mod's content rather than vanilla's.
  ///
  /// The structure's own `book_chance`, so the existing per-structure tuning keeps its
  /// meaning. Returns 0 when the table is not configured or is disabled, which leaves
  /// vanilla's pool unconditional and the table effectively untouched.
  public float archaeologyChance(String lootTableId)
  {
    if(!initialized) return 0.0f;
    StructureConfig config = lootConfig.getStructure(lootTableId);
    if(config == null || !config.enabled) return 0.0f;
    return Math.max(0.0f, Math.min(1.0f, config.bookChance));
  }

  /// Injects an enchanted book pool via the provided consumer.
  /// Used by NeoForge where a LootTable.Builder is not available.
  /// The consumer receives a configured LootPool.Builder to add to the loot table.
  public void modifyLootTableWithConsumer(
      String lootTableId,
      java.util.function.Consumer<LootPool.Builder> poolConsumer,
      HolderGetter.Provider registries
  )
  {
    if(!initialized) {
      Constants.LOGGER.warn("LootDistributionManager not initialized, skipping modification");
      return;
    }

    // Archaeology is handled by replaceArchaeologyPool, not here: a brushed block
    // keeps only one item, so extra pools are silently discarded. See isArchaeology.
    if(isArchaeology(lootTableId)) return;

    LootPool.Builder gearPool = buildGearPoolBuilder(lootTableId, registries);
    if(gearPool != null) {
      poolConsumer.accept(gearPool);
    }

    StructureConfig config = lootConfig.getStructure(lootTableId);
    if(config == null || !config.enabled) {
      return;
    }

    Constants.debug("Modifying loot table: {} (tier: {}, rolls: {})", lootTableId, config.tier, config.rollsPerChest);

    LootPool.Builder poolBuilder = buildPoolBuilder(lootTableId, config, registries);
    if(poolBuilder != null) {
      poolConsumer.accept(poolBuilder);
      totalModifications++;
    }
  }

  /// Whether this loot table should be touched at all.
  ///
  /// Both loaders must gate on exactly the same conditions or the same world seed yields
  /// different loot on Fabric and NeoForge. The checks therefore live here rather than in
  /// each loader's event handler, where they previously drifted apart.
  ///
  /// Only vanilla tables are touched, identified by namespace. NeoForge's
  /// LootTableLoadEvent carries no origin flag equivalent to Fabric's
  /// LootTableSource#isBuiltin, so the namespace is the one signal both loaders can read
  /// identically -- and it is the signal that actually matters here, since the intent is
  /// to leave other mods' and datapacks' loot alone.
  public boolean shouldModifyLootTable(String lootTableId)
  {
    if(!initialized) return false;
    if(!Config.get().enabled) return false;
    return lootTableId.startsWith("minecraft:");
  }

  /// Whether the Curse of Copyright should be applied to items from this loot table.
  ///
  /// Deliberately flat across every found-loot source regardless of rarity or value, so
  /// the rule stays explainable: one in ten of what you find is copyrighted.
  ///
  /// Excluded by omission: block drops (a player breaking their own enchanted item's
  /// container is not finding treasure), mob drops and spawn equipment under
  /// `entities/` and `equipment/` -- cursing those would let players farm the
  /// curse from a spawner -- and shearing, harvest, and dispenser tables, which cannot
  /// yield enchanted items at all.
  ///
  /// Callers must have cleared [#shouldModifyLootTable] first.
  public boolean shouldCurseLootTable(String lootTableId)
  {
    return Config.get().loot.foundLootPrefixes.stream().anyMatch(lootTableId::startsWith);
  }

  /// Gets the tier for a loot table (used to size injected enchantment levels).
  public StructureTier getTierForLootTable(String lootTableId)
  {
    StructureConfig config = lootConfig.getStructure(lootTableId);
    if(config == null || !config.enabled) {
      return null;
    }
    StructureTier tier = config.getTier();
    return tier != null ? tier : StructureTier.MEDIUM;
  }

  private void injectEnchantedBooks(
      String lootTableId,
      LootTable.Builder tableBuilder,
      HolderGetter.Provider registries
  )
  {
    if(!initialized) {
      Constants.LOGGER.warn("LootDistributionManager not initialized, skipping modification");
      return;
    }

    StructureConfig config = lootConfig.getStructure(lootTableId);
    if(config == null || !config.enabled) {
      return;
    }

    Constants.debug("Modifying loot table: {} (tier: {}, rolls: {})", lootTableId, config.tier, config.rollsPerChest);

    LootPool.Builder gearPool = buildGearPoolBuilder(lootTableId, registries);
    if(gearPool != null) {
      tableBuilder.withPool(gearPool);
    }

    LootPool.Builder poolBuilder = buildPoolBuilder(lootTableId, config, registries);
    if(poolBuilder != null) {
      tableBuilder.withPool(poolBuilder);
      totalModifications++;
    }
  }

  private static final String ARCHAEOLOGY_PREFIX    = "minecraft:archaeology/";
  private static final String TRADESCHOOL_NAMESPACE = "tradeschool";

  /// Whether this table is brushed rather than opened.
  ///
  /// **Archaeology keeps only one item, and discards the rest.**
  /// `BrushableBlockEntity.dropContent` does:
  ///
  /// ```java
  /// this.item = switch (loot.size()) {
  ///     case 0 -> ItemStack.EMPTY;
  ///     case 1 -> loot.getFirst();
  ///     default -> { LOGGER.warn("Expected max 1 loot from loot table {}, but got {}", …);
  ///                  yield loot.getFirst(); }
  /// };
  /// ```
  ///
  /// Pools are evaluated in list order and ours are appended, so `getFirst()` is always
  /// vanilla's roll and everything this mod contributes is thrown away -- while the log
  /// fills with that warning. Chests are unaffected: they take every item.
  ///
  /// So for archaeology the injection cannot be additional pools. It has to be *one*
  /// pool that yields one item, which is what [#buildArchaeologyPool] builds.
  public static boolean isArchaeology(String lootTableId)
  {
    return lootTableId.startsWith(ARCHAEOLOGY_PREFIX);
  }

  /// The single replacement pool for a brushed block: this mod's content first, falling
  /// back to vanilla's own entries.
  ///
  /// Vanilla's stock archaeology loot is mostly dye and sherds, so the intent is that
  /// this mod's gear and books take precedence and vanilla fills in the rest.
  /// [AlternativesEntry] gives exactly that: children are tried in order and the first
  /// one to produce an item wins. Every child but the last needs a condition or it would
  /// make the rest unreachable -- which vanilla's own validator reports -- so ours carry a
  /// random-chance gate and vanilla's entries sit last, unconditional, as the fallback.
  ///
  /// The result is one item per brush, no warning, and the configured `book_chance`
  /// keeps its meaning as "how often this table yields one of ours".
  private LootPool.Builder buildArchaeologyPool(String lootTableId, StructureConfig config, HolderGetter.Provider registries)
  {
    var alternatives = new ArrayList<LootPoolEntryContainer.Builder<?>>();

    // Gear first: the rarest and most interesting outcome.
    LootPool.Builder gear = buildGearPoolBuilder(lootTableId, registries);
    if(gear != null) {
      var gearKey = archaeologyGearKey(lootTableId);
      var lookup  = registries.lookup(Registries.LOOT_TABLE);
      if(lookup.isPresent()) {
        var gearTable = lookup.get().get(gearKey);
        // The gear table carries its own `minecraft:empty` weighting, so it
        // declines on its own often enough; the chance here is the outer gate.
        gearTable.ifPresent(holder -> alternatives.add(NestedLootTable.lootTableReference(holder).when(LootItemRandomChanceCondition.randomChance(archaeologyGearChance(config)))));
      }
    }

    // Books last and unconditional. The pool as a whole is already gated by
    // archaeologyChance, with vanilla's pool carrying the inverse, so reaching here
    // means this mod's content *is* the answer -- the final alternative must therefore
    // always produce something, or a brush would yield nothing at all after vanilla
    // had been ruled out.
    if(config != null && config.enabled) {
      List<LootPoolEntryContainer.Builder<?>> bookEntries =
          buildBookEntries(lootTableId, config, registries);
      if(!bookEntries.isEmpty()) {
        alternatives.add(EntryGroup.list(
            bookEntries.toArray(new LootPoolEntryContainer.Builder<?>[0])
        ));
      }
    }

    // Nothing to offer: leave the table alone rather than gating vanilla behind an
    // empty pool, which would make brushing produce nothing at all.
    if(alternatives.isEmpty()) return null;

    return LootPool.lootPool()
        .setRolls(ContextIntProviders.exactly(1))
        .when(LootItemRandomChanceCondition.randomChance(archaeologyChance(lootTableId)))
        .add(AlternativesEntry.alternatives(
            alternatives.toArray(new LootPoolEntryContainer.Builder<?>[0])
        ));
  }

  /// How often a brushed block yields gear rather than falling through.
  ///
  /// Expressed as a multiple of the structure's own `book_chance` rather than an
  /// absolute rate, so an operator who tunes a structure's books has its gear follow --
  /// which is the relationship they would expect. The multiplier is
  /// `loot.archaeology_gear_chance_multiplier`; 0 turns gear off without disturbing the
  /// book rate.
  private float archaeologyGearChance(StructureConfig config)
  {
    float multiplier = Config.get().loot.archaeologyGearChanceMultiplier;
    if(config == null) return 0.0f;
    return Math.max(0.0f, Math.min(1.0f, config.bookChance * multiplier));
  }

  private ResourceKey<LootTable> archaeologyGearKey(String lootTableId)
  {
    String name = lootTableId.substring(ARCHAEOLOGY_PREFIX.length());
    return ResourceKey.create(
        Registries.LOOT_TABLE,
        Identifier.fromNamespaceAndPath(TRADESCHOOL_NAMESPACE, "archaeology/" + name)
    );
  }

  private LootPool.Builder buildGearPoolBuilder(String lootTableId, HolderGetter.Provider registries)
  {
    if(!lootTableId.startsWith(ARCHAEOLOGY_PREFIX)) return null;

    String name                    = lootTableId.substring(ARCHAEOLOGY_PREFIX.length());
    ResourceKey<LootTable> gearKey = ResourceKey.create(
        Registries.LOOT_TABLE,
        Identifier.fromNamespaceAndPath(TRADESCHOOL_NAMESPACE, "archaeology/" + name)
    );

    var lookup = registries.lookup(Registries.LOOT_TABLE);
    if(lookup.isEmpty()) return null;
    // 26.3 takes the resolved Holder rather than the key, so the lookup that was
    // only an existence check now supplies the reference itself.
    var gearTable = lookup.get().get(gearKey);
    if(gearTable.isEmpty()) return null;

    return LootPool.lootPool()
        .setRolls(ContextIntProviders.exactly(1))
        .add(NestedLootTable.lootTableReference(gearTable.get()));
  }

  private LootPool.Builder buildPoolBuilder(
      String lootTableId,
      StructureConfig config,
      HolderGetter.Provider registries
  )
  {
    List<EnchantmentEntry> enchantments = buildEnchantmentList(config);

    if(enchantments.isEmpty()) {
      Constants.LOGGER.warn("No enchantments configured for {} - skipping", lootTableId);
      return null;
    }

    StructureTier tier = config.getTier();
    if(tier == null) {
      Constants.LOGGER.warn("Invalid tier for {}, defaulting to MEDIUM", lootTableId);
      tier = StructureTier.MEDIUM;
    }

    LootPool.Builder poolBuilder = LootPool.lootPool()
                                       .setRolls(config.bookChance < 1.0f ? ContextIntProviders.binomial(config.rollsPerChest, config.bookChance) : ContextIntProviders.exactly(config.rollsPerChest));
    // No setBonusRolls: 26.3 split bonus rolls onto a float provider, and the
    // builder already defaults it to exactly 0.0F -- which is what this set.

    for(var entry: buildBookEntries(lootTableId, config, registries)) {
      poolBuilder.add(entry);
    }

    return poolBuilder;
  }

  /// The weighted enchanted-book entries for a table, without the pool around them.
  ///
  /// Split out of [#buildPoolBuilder] so archaeology can reuse the identical entries
  /// inside an [AlternativesEntry] instead of a pool of their own -- see
  /// [#buildArchaeologyPool]. Keeping one implementation is the point: two copies of the
  /// weighting would drift, and the rates are the mod's balance.
  private List<LootPoolEntryContainer.Builder<?>> buildBookEntries(
      String lootTableId,
      StructureConfig config,
      HolderGetter.Provider registries
  )
  {
    List<LootPoolEntryContainer.Builder<?>> built = new ArrayList<>();
    List<EnchantmentEntry> enchantments           = buildEnchantmentList(config);
    if(enchantments.isEmpty()) return built;

    int maxLevel = config.getMaxEnchantmentLevel();

    for(EnchantmentEntry entry: enchantments) {
      int enchantMaxLevel = entry.maxLevel != null ? entry.maxLevel : maxLevel;
      enchantMaxLevel     = Math.min(enchantMaxLevel, maxLevel);

      for(int level = 1; level <= enchantMaxLevel; level++) {
        int weight = calculateWeight(entry.weight, level, enchantMaxLevel);

        try {
          Identifier enchantmentLoc = Identifier.parse(entry.enchantmentId);
          ResourceKey<net.minecraft.world.item.enchantment.Enchantment> enchantmentKey =
              ResourceKey.create(Registries.ENCHANTMENT, enchantmentLoc);
          Holder<net.minecraft.world.item.enchantment.Enchantment> enchantmentHolder =
              registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(enchantmentKey);

          ItemEnchantments.Mutable storedEnchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
          storedEnchantments.set(enchantmentHolder, level);

          built.add(LootItem.lootTableItem(Items.ENCHANTED_BOOK)
                        .setWeight(weight)
                        .apply(SetComponentsFunction.setComponent(DataComponents.STORED_ENCHANTMENTS, storedEnchantments.toImmutable()))
                        .apply(ApplyCurseOfCopyrightFunction.applyCurse()));
        }
        catch(Exception e) {
          Constants.LOGGER.error("Failed to add loot entry for enchantment {} level {}", entry.enchantmentId, level, e);
        }
      }
    }

    totalEnchantmentEntries += built.size();

    Constants.debug("Added {} enchanted book entries to {} ({} unique enchantments, max level {})", built.size(), lootTableId, enchantments.size(), maxLevel);

    return built;
  }

  /// Builds the list of enchantments that should appear in this structure.
  private List<EnchantmentEntry> buildEnchantmentList(StructureConfig config)
  {
    List<EnchantmentEntry> result = new ArrayList<>();

    // Add custom enchantments (highest priority)
    if(config.customEnchantments != null) {
      for(Map.Entry<String, EnchantmentWeight> entry: config.customEnchantments.entrySet()) {
        result.add(new EnchantmentEntry(
            entry.getKey(),
            entry.getValue().getWeight(),
            entry.getValue().getMaxLevel()
        ));
      }
    }

    // Add enchantments from primary categories
    if(config.primaryCategories != null) {
      for(String categoryName: config.primaryCategories) {
        addEnchantmentsFromCategory(categoryName, result, false);
      }
    }

    // Add enchantments from crossover categories (with probability check)
    // For now, we'll add them all but with reduced weight
    if(config.crossoverCategories != null) {
      for(Map.Entry<String, Double> entry: config.crossoverCategories.entrySet()) {
        addEnchantmentsFromCategory(entry.getKey(), result, true);
      }
    }

    return result;
  }

  /// Adds enchantments from a category to the result list.
  private void addEnchantmentsFromCategory(
      String categoryName,
      List<EnchantmentEntry> result,
      boolean isCrossover
  )
  {
    // Try to find category in config
    CategoryConfig category = lootConfig.categories.get(categoryName);
    if(category != null) {
      for(String enchantmentId: category.enchantments) {
        // Check if not already added
        if(result.stream().noneMatch(e -> e.enchantmentId.equals(enchantmentId))) {
          int weight = isCrossover ? category.defaultWeight / 2 : category.defaultWeight;
          result.add(new EnchantmentEntry(enchantmentId, weight, null));
        }
      }
      return;
    }

    // Try to find enum category
    try {
      EnchantmentCategory enumCategory = EnchantmentCategory.valueOf(categoryName.toUpperCase());
      for(String enchantmentId: enumCategory.getEnchantmentIds()) {
        if(result.stream().noneMatch(e -> e.enchantmentId.equals(enchantmentId))) {
          int weight = isCrossover ? 5 : 10; // Default weights
          result.add(new EnchantmentEntry(enchantmentId, weight, null));
        }
      }
    }
    catch(IllegalArgumentException e) {
      Constants.LOGGER.warn("Unknown category: {}", categoryName);
    }
  }

  /// Calculates the weight for an enchantment at a given level.
  /// Higher levels are exponentially rarer.
  ///
  /// @param baseWeight The base weight from configuration
  /// @param currentLevel The current enchantment level
  /// @param maxLevel The maximum level for this enchantment
  /// @return The calculated weight
  private int calculateWeight(int baseWeight, int currentLevel, int maxLevel)
  {
    // Formula: baseWeight / (2^(currentLevel - 1))
    // Level 1: baseWeight
    // Level 2: baseWeight / 2
    // Level 3: baseWeight / 4
    // Level 4: baseWeight / 8
    // Level 5: baseWeight / 16
    int weight = baseWeight >> (currentLevel - 1);
    return Math.max(1, weight); // Minimum weight of 1
  }

  /// Internal class for tracking enchantment entries during list building.
  private static class EnchantmentEntry
  {
    final String enchantmentId;
    final int weight;
    final Integer maxLevel;

    EnchantmentEntry(String enchantmentId, int weight, Integer maxLevel)
    {
      this.enchantmentId = enchantmentId;
      this.weight        = weight;
      this.maxLevel      = maxLevel;
    }
  }

  /// Gets statistics about loot modifications.
  /// Useful for debugging and testing.
  public String getStatistics()
  {
    return String.format(
        "LootDistributionManager Statistics: %d loot tables modified, %d enchantment entries added, %d curses applied",
        totalModifications,
        totalEnchantmentEntries,
        curseApplications
    );
  }

  /// Resets statistics counters.
  public void resetStatistics()
  {
    totalModifications      = 0;
    totalEnchantmentEntries = 0;
    curseApplications       = 0;
  }
}
