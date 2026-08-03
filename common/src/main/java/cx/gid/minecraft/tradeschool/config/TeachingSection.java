package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

import java.util.List;

///  What villagers will learn, and what the player gets for teaching them.
public class TeachingSection {

    @Comment({"Enchantments no villager will learn. Namespace optional.",
              "Empty means anything can be learned."})
    public List<String> unlearnableEnchantments = List.of();

    @Comment({"Minimum villager level for a single-level enchantment — Mending, Silk Touch",
              "and the like. They have no weaker version to learn first, so villager rank is",
              "the only lever available. 0 or 1 removes the restriction."})
    public int singleLevelEnchantmentMinimumLevel = 1;

    @Comment({"Divisor on an item's sell price to get the emerald payment for a full learn.",
              "2 pays the player half what the villager will go on to charge."})
    public int fullLearnPaymentDivisor = 1;

    @Comment({"Experience orbs the player gets for teaching, as a multiple of what the",
              "lesson is worth — trading.learned_trade_xp for the villager's level, which",
              "is the same figure the resulting trade goes on to grant.",
              "",
              "1.0 pays the player what buying that book from a vanilla librarian would",
              "have given them. 0 disables it."})
    public double playerXpMultiplier = 0.0;

    @Comment({"Villager experience for being taught, as a multiple of the same figure.",
              "",
              "1.0 means a lesson advances a villager exactly as much as selling the book",
              "it just learned would — teaching a novice is worth one sale, not ten.",
              "",
              "0 means teaching never levels a villager, and it must earn its promotions",
              "by trading. That is a coherent design — the lesson is the player's",
              "investment and the trade is the villager's work — so it is the benign",
              "default, but 1.0 is what ships."})
    public double villagerXpMultiplier = 0.0;

    public ReputationSection reputation = new ReputationSection();

    public void applyDefaults() {
        if (unlearnableEnchantments == null) unlearnableEnchantments = List.of();
        if (reputation == null) reputation = new ReputationSection();
        // A divisor of zero would divide by zero; treat it as "pay full price".
        if (fullLearnPaymentDivisor < 1) fullLearnPaymentDivisor = 1;
    }
}
