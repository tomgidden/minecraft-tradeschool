package cx.gid.minecraft.tradeschool.loot.config;

import java.util.List;
import java.util.Map;

/**
 * Global configuration settings that apply to all loot table modifications and villager trading.
 */
public class GlobalConfig {
    public boolean enabled = true;

    /**
     * Verbose per-event logging (teach scans, loot table modifications, trade generation).
     * Off by default: these fire constantly and flood a busy server's log.
     */
    public boolean debugLogging = false;

    public Map<String, Integer> defaultTierMaxLevels;
    public double defaultCrossoverChance;

    /**
     * Chance that any found enchanted item carries the Curse of Copyright.
     * Flat across every source, regardless of rarity or difficulty.
     */
    public double curseProbability = 0.1;

    /**
     * Loot table prefixes treated as *found* loot and therefore eligible for the curse.
     * Block drops, mob drops and spawn equipment are excluded by omission — those are not
     * discovered treasure, and cursing them would let players farm the curse.
     */
    public List<String> foundLootPrefixes = List.of(
        "minecraft:chests/",
        "minecraft:archaeology/",
        "minecraft:gameplay/fishing"
    );

    /**
     * Enchantments no villager will learn. Namespace optional — "wind_burst" and
     * "minecraft:wind_burst" are equivalent. Defaults to the three vanilla treasure
     * enchantments that no villager trades; empty the list to allow everything.
     */
    public List<String> unlearnableEnchantments = List.of(
        "minecraft:soul_speed",
        "minecraft:swift_sneak",
        "minecraft:wind_burst"
    );

    /** Gossip granted for teaching a villager. */
    public ReputationConfig reputation = new ReputationConfig();

    /** Teaching and trade balance knobs. */
    public TradeConfig trade = new TradeConfig();

    /** Which channels player-facing messages use, and for how long. */
    public FeedbackConfig feedback = new FeedbackConfig();

    public GlobalConfig() {
        // Required for Gson deserialization
    }

    public GlobalConfig(
        Map<String, Integer> defaultTierMaxLevels,
        double defaultCrossoverChance
    ) {
        this.enabled = true;
        this.defaultTierMaxLevels = defaultTierMaxLevels;
        this.defaultCrossoverChance = defaultCrossoverChance;
    }

    /**
     * Gson leaves fields absent from the JSON untouched, but explicitly-null sub-objects
     * and a null list would NPE at use. Normalise after loading.
     */
    public void applyDefaults() {
        if (reputation == null) reputation = new ReputationConfig();
        if (trade == null) trade = new TradeConfig();
        if (feedback == null) feedback = new FeedbackConfig();
        // An explicitly empty list is meaningful (allow everything); only null needs fixing.
        if (unlearnableEnchantments == null) unlearnableEnchantments = List.of();
        if (foundLootPrefixes == null || foundLootPrefixes.isEmpty()) {
            foundLootPrefixes = List.of(
                "minecraft:chests/",
                "minecraft:archaeology/",
                "minecraft:gameplay/fishing"
            );
        }
    }
}
