package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;
import cx.gid.minecraft.tradeschool.Constants;

import java.util.LinkedHashMap;
import java.util.Map;

///  The trades villagers offer, taught and otherwise.
public class TradingSection {

  @Comment("Uses a learned trade allows before the villager must restock.")
  public int maxUses = 0;

  @Comment({"Most teach offers shown at once when a player carries several candidates.",
            "Vanilla's trade list scrolls, so this is about clutter rather than space."})
  public int maxTeachOffers = 0;

  @Comment({"Villager experience a learned trade grants, indexed by the villager's level.",
            "",
            "These are vanilla's own figures for enchanted-book trades: 1, 5, 10, 15 at",
            "levels 1-4. Vanilla has no book trade at level 5, so 30 follows its other",
            "level-5 trades.",
            "",
            "A flat value cannot work at both ends. Leaving level 1 takes 10 experience but",
            "leaving level 4 takes 100, so a number generous enough for the last promotion",
            "makes the first instant, and one balanced for the first cannot finish the last",
            "within max_uses at all.",
            "",
            "Levelling is slower here than in vanilla even so: a vanilla librarian has three",
            "trades per level contributing experience and a taught one may have a single",
            "trade. Operators who would rather match vanilla's *pace* than its numbers will",
            "find [2, 10, 20, 30, 30] feels closer to familiar."})
  public java.util.List<Integer> learnedTradeXp = java.util.List.of(1, 5, 10, 15, 30);

  @Comment({"What the player must supply alongside the emeralds when buying a learned",
            "enchanted book, as vanilla's own book trades require a blank book:",
            "[item, count]. Empty means emeralds alone.",
            "",
            "Books only for now. Gear would want its own material rather than paper, and",
            "expressing that needs a mapping from what is being sold to what it costs —",
            "26.1 work, not another setting beside this one."})
  public java.util.List<com.google.gson.JsonElement> bookPriceSecondary =
    java.util.List.of();

  @Comment({"How much a learned trade's price responds to reputation and demand.",
            "0.2 is what vanilla uses for enchanted books; its commodity trades use 0.05."})
  public float learnedTradePriceMultiplier = 0.2F;

  /// The secondary cost for a learned book trade, or empty if it takes emeralds alone.
  ///
  /// Resolved per call rather than cached, since the item registry is not populated when
  /// the config loads. An unknown id yields no cost rather than failing the trade: a typo
  /// should make the book cheaper, not make it impossible to sell.
  public java.util.Optional<net.minecraft.world.item.trading.ItemCost> bookCost()
  {
    var cost = bookPriceSecondary;
    if (cost == null || cost.isEmpty()) return java.util.Optional.empty();

    // Hand-written config, so anything could be in here. A malformed entry costs the
    // player nothing rather than failing the trade or the server.
    if (cost.get(0) == null || !cost.get(0).isJsonPrimitive()) {
      Constants.LOGGER.warn(
        "book_price_secondary should be [item, count]; got {}", cost);
      return java.util.Optional.empty();
    }

    var id = net.minecraft.resources.Identifier.tryParse(cost.get(0).getAsString());
    if (id == null) {
      Constants.LOGGER.warn(
        "book_price_secondary names an unparseable item '{}'; "
        + "learned books will cost emeralds alone", cost.get(0));
      return java.util.Optional.empty();
    }

    // A count is optional; one is the sensible reading of ["minecraft:book"].
    int count = 1;
    if (cost.size() > 1 && cost.get(1) != null && cost.get(1).isJsonPrimitive()) {
      try {
        count = Math.max(1, cost.get(1).getAsInt());
      }
      catch (NumberFormatException e) {
        Constants.LOGGER.warn(
          "book_price_secondary count '{}' is not a number; using 1",
          cost.get(1));
      }
    }

    final int resolvedCount = count;

    var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id);
    if (item.isEmpty())
      Constants.LOGGER.warn("book_price_secondary names unknown item '{}'; learned books will cost emeralds alone",
                            id);

    return item.map(
             i -> new net.minecraft.world.item.trading.ItemCost(i, resolvedCount)
           );
  }

  @Comment({"Emerald cost bands for learned enchanted books, by the enchantment's own",
            "cost: common, uncommon, rare, very rare. Stated as a whole because they are",
            "one scale; setting a rare band below an uncommon one would invert the",
            "ordering."})
  public java.util.List<Integer> bookPriceBands = java.util.List.of(5, 10, 15, 20);

  @Comment({"Emeralds added per enchantment level above the first, on top of the band.",
            "Sharpness III in the common band costs 5 + 2 x 5."})
  public int bookPricePerLevel = 5;

  @Comment({"Least a learned trade may cost, whatever the bands work out to: [book, item].",
            "",
            "Books floor higher than gear because a book is pure knowledge — the villager",
            "supplies nothing but the enchantment — whereas a cheap tool is mostly its",
            "material."})
  public java.util.List<Integer> minimumPrice = java.util.List.of(5, 1);

  @Comment({"Most a learned trade may cost. 64 is a hard ceiling rather than a balance",
            "choice: a trade's cost is one item stack, so more would be truncated."})
  public int maximumPrice = 64;

  @Comment({"A book with several enchantments is priced on the dearest, plus each",
            "remaining one divided by this. 2 charges half for extras: a two-enchantment",
            "book should beat buying both separately, or there is no reason to want one.",
            "1 charges full price for every enchantment."})
  public int extraEnchantmentPriceDivisor = 2;

  ///  Cheapest a learned book may be.
  public int minimumBookPrice()
  {
    return minPrice(0, 5);
  }

  ///  Cheapest a learned item may be.
  public int minimumItemPrice()
  {
    return minPrice(1, 1);
  }

  private int minPrice(int index, int fallback)
  {
    return
      minimumPrice != null && minimumPrice.size() > index && minimumPrice.get(index) != null
      ? minimumPrice.get(index)
      : fallback;
  }

  @Comment({"Commodity trades filling profession levels that would otherwise be empty.",
            "",
            "Stripping the items a profession can be taught leaves some levels with",
            "nothing at all, and a villager with no trades earns no experience — so it",
            "could never reach the level where teaching unlocks. These keep progression",
            "possible rather than being flavour.",
            "",
            "Keyed so entries can be amended, added or removed one at a time: give a key",
            "to change a field, or null to delete that filler entirely."})
  public Map<String, FillerTrade> fillers = new LinkedHashMap<>();

  /// Experience a learned trade grants a villager of `villagerLevel` (1-5).
  ///
  /// Out-of-range levels take the nearest end of the list rather than nothing: a villager
  /// at an unexpected level should still be able to advance, and silently granting zero
  /// would strand it there with no indication why.
  public int xpForLevel(int villagerLevel)
  {
    if (learnedTradeXp == null || learnedTradeXp.isEmpty())
      return 1;

    int index = Math.min(Math.max(villagerLevel - 1, 0), learnedTradeXp.size() - 1);

    Integer value = learnedTradeXp.get(index);

    return value != null
           ? value
           : 1;
  }

  public void applyDefaults()
  {
    if (fillers == null)
      fillers = new LinkedHashMap<>();

    if (learnedTradeXp == null)
      learnedTradeXp = java.util.List.of(1, 5, 10, 15, 30);

    // A null value is how a config deletes an inherited filler; drop those now so
    // nothing downstream has to guard against them.
    fillers.values().removeIf(java.util.Objects::isNull);

    if (bookPriceBands == null)
      bookPriceBands = java.util.List.of(5, 10, 15, 20);

    if (minimumPrice == null)
      minimumPrice = java.util.List.of(5, 1);

    // A divisor of zero would divide by zero; treat it as "charge full price".
    if (extraEnchantmentPriceDivisor < 1)
      extraEnchantmentPriceDivisor = 1;

    if (maximumPrice < 1)
      maximumPrice = 64;
  }

  ///  One band from [#bookPriceBands], or a sensible value if the list is short.
  public int band(int index, int fallback)
  {
    return
      bookPriceBands != null && bookPriceBands.size() > index
      ? bookPriceBands.get(index)
      : fallback;
  }
}
