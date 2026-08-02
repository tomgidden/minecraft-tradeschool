package cx.gid.minecraft.tradeschool.loot.config;

/**
 * Balance settings for teaching and for the trades it produces.
 */
public class TradeConfig {

    // ── Curse of Copyright book trade (master librarians) ─────────────────────

    /** Minimum librarian level that may stock blank curse books. */
    public int curseBookRequiredLevel = 5;

    /**
     * Fraction of eligible librarians carrying the trade. Decided per villager from their
     * UUID, not per restock, so a given librarian either always has it or never does.
     */
    public double curseBookAvailability = 0.5;

    /** Emerald cost of one blank curse book. Cheap by design — it is a shop consumable. */
    public int curseBookEmeraldCost = 1;

    // ── Teaching rewards ──────────────────────────────────────────────────────

    /**
     * Divisor applied to an item's sell price to get the emerald payment for a full learn.
     * 2 means the player is paid half what the villager will go on to charge.
     */
    public int fullLearnPaymentDivisor = 2;

    /** Multiplier on the sell price to get XP orbs granted for teaching. */
    public double teachXpMultiplier = 1.0;

    // ── Learned trade pricing ─────────────────────────────────────────────────

    /** Emerald cost bands for learned enchanted books, selected by the enchantment's max cost. */
    public int bookPriceCommon = 5;    // maxCost <= 20
    public int bookPriceUncommon = 10; // maxCost <= 30
    public int bookPriceRare = 15;     // maxCost <= 40
    public int bookPriceVeryRare = 20; // above that

    /** Extra emeralds charged per enchantment level above the first. */
    public int bookPricePerLevel = 5;

    /** Uses a learned trade allows before the villager must restock. */
    public int learnedTradeMaxUses = 12;

    // ── Teaching mechanics ────────────────────────────────────────────────────

    /**
     * Minimum villager level required to learn a single-level enchantment (Mending, Silk
     * Touch, and the Curse of Copyright). Multi-level enchantments are gated by their own
     * level instead.
     */
    public int singleLevelEnchantmentMinVillagerLevel = 3;

    /** Maximum simultaneous teach offers shown when a player carries several candidates. */
    public int maxTeachOffers = 6;

    /** Radius in blocks within which a villager previews a held item and sends messages. */
    public double teachPreviewRadius = 5.0;

    /** Radius in blocks within which the one-off profession hint is offered. */
    public double hintRadius = 8.0;

    // ── Filler trades ─────────────────────────────────────────────────────────

    /**
     * Commodity buy trades that fill profession levels left empty by removing teachable
     * item types. See {@link FillerTrade}; without these an untaught armorer or
     * weaponsmith has nothing to trade at low levels and can never gain the experience
     * needed to reach the level where teaching unlocks.
     */
    public java.util.List<FillerTrade> fillers = defaultFillers();

    private static java.util.List<FillerTrade> defaultFillers() {
        return java.util.List.of(
            // Armorer: L1/L2 lose all four iron and both chainmail pieces.
            filler("armorer", 1, "minecraft:coal", 15),
            filler("armorer", 2, "minecraft:leather", 6),
            // Weaponsmith: L1 loses iron_axe (iron_sword was enchanted).
            filler("weaponsmith", 1, "minecraft:coal", 15),
            filler("weaponsmith", 2, "minecraft:flint", 10),
            // Toolsmith: L1 loses all four stone tools; L2 has nothing in vanilla.
            filler("toolsmith", 1, "minecraft:coal", 15),
            filler("toolsmith", 2, "minecraft:gravel", 10)
        );
    }

    private static FillerTrade filler(String profession, int level, String material, int count) {
        FillerTrade f = new FillerTrade();
        f.profession = profession;
        f.level = level;
        f.material = material;
        f.count = count;
        return f;
    }

    public TradeConfig() {
        // Required for Gson deserialization
    }
}
