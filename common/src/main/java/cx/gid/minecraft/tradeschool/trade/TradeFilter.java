package cx.gid.minecraft.tradeschool.trade;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/// Decides which vanilla trades survive, and whether two offers are the same trade.
///
/// The governing rule: a profession must not sell by default anything it can be *taught*.
/// Unenchanted iron, stone and copper gear is trivially craftable, so selling it undercuts
/// the teaching mechanic for no benefit -- the point of the mod is that gear and
/// enchantments come from exploration and teaching, not from rerolling villagers.
///
/// Three item types are exempt because their materials are genuinely scarce, so buying them
/// is a real convenience rather than a shortcut past teaching:
/// bows and crossbows (string, especially in peaceful), plain books (leather and paper),
/// and shields (iron).
public class TradeFilter
{
  ///  Enchantments carried by a stack, whether worn (gear) or stored (books).
  public static ItemEnchantments enchantmentsOf(ItemStack stack)
  {
    ItemEnchantments e = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
    if(e.isEmpty()) {
      e = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }
    return e;
  }

  /// True if this item may still be sold untaught despite being a teachable type.
  /// See the class comment for why each is exempt.
  public static boolean isScarcityException(Item item)
  {
    return item == Items.BOW
        || item == Items.CROSSBOW
        || item == Items.BOOK
        || item == Items.SHIELD;
  }

  /// True if the offer should be stripped from a villager's default trades.
  ///
  /// Two independent reasons: the result is enchanted (every vanilla enchant-function
  /// trade produces an enchanted output), or the result is an item type this profession
  /// can be taught.
  public static boolean shouldRemove(MerchantOffer offer, String professionId, java.util.Collection<MerchantOffer> keep)
  {
    ItemStack result = offer.getResult();
    if(result.isEmpty()) return false;

    // A trade the villager was taught is this mod's own output, not vanilla's. It is
    // enchanted, so the test below would strip it -- and since offers are only appended
    // for the level just reached, anything stripped here is gone for good. Identity,
    // not equivalence: the point is to spare *these* offer objects, so that their uses
    // survive and a trade in progress keeps the object the menu is holding.
    for(MerchantOffer taught: keep) {
      if(taught == offer) return false;
    }

    if(!enchantmentsOf(result).isEmpty()) return true;

    Item item = result.getItem();
    if(isScarcityException(item)) return false;
    return EnchantedItemAnalyzer.isItemForProfession(item, professionId);
  }

  /// Whether two offers are the same trade for deduplication purposes: same result item
  /// with the same enchantments, bought with the same currency. Price is deliberately
  /// excluded -- two offers for the same goods at different prices are duplicates, and the
  /// caller picks which to keep.
  ///
  /// **The secondary cost is deliberately excluded too, and that matters on upgrade.**
  /// A villager taught before `book_price_secondary` existed has a learned trade
  /// with no `buyB`; the one this build would produce for the same lesson has one.
  /// Because only `costA` is compared, the two count as the same trade, so
  /// [#addIfAbsent] leaves the old offer alone rather than adding a second copy of
  /// something the villager already sells. Verified against a rigged old-format librarian.
  ///
  /// Comparing `costB` as well would look like a tightening and would silently
  /// give every already-taught villager duplicate trades the next time a config setting
  /// changed what a trade costs.
  public static boolean isEquivalent(MerchantOffer a, MerchantOffer b)
  {
    ItemStack ra = a.getResult(), rb = b.getResult();
    if(ra.getItem() != rb.getItem()) return false;
    if(!enchantmentsOf(ra).equals(enchantmentsOf(rb))) return false;
    return a.getCostA().getItem() == b.getCostA().getItem();
  }

  /// The offers in `offers` that match something the villager has been taught.
  ///
  /// Used to spare this mod's own trades from the filtering that strips vanilla's
  /// enchanted ones. Matching is by result item and enchantments, since an offer carries
  /// no marker saying where it came from -- the villager's knowledge is the only record of
  /// what it was taught, and two offers selling the identical enchanted item are the same
  /// trade whoever built them.
  public static java.util.List<MerchantOffer> taughtOffers(
      MerchantOffers offers, java.util.List<ItemStack> taughtResults
  )
  {
    java.util.List<MerchantOffer> found = new java.util.ArrayList<>();
    for(MerchantOffer offer: offers) {
      ItemStack result = offer.getResult();
      for(ItemStack taught: taughtResults) {
        if(result.getItem() == taught.getItem()
           && enchantmentsOf(result).equals(enchantmentsOf(taught))) {
          found.add(offer);
          break;
        }
      }
    }
    return found;
  }

  /// Adds `candidate` unless an equivalent offer is already present.
  ///
  /// ### Why it never replaces
  ///
  /// An earlier version kept whichever of the two was cheaper, which meant an existing
  /// offer could be swapped for a freshly built one. That silently discarded the old
  /// offer's `uses` -- handing the player a free restock -- and broke the trade in
  /// progress when it happened during `notifyTrade`, because the menu holds a
  /// reference to the offer it is completing.
  ///
  /// Leaving the existing offer alone matches vanilla, which appends and never
  /// revisits. Vanilla can produce two book trades for the same enchantment at different
  /// prices and simply keeps both; a villager taught the same thing twice does the same.
  ///
  /// @return true if the candidate was added.
  public static boolean addIfAbsent(MerchantOffers offers, MerchantOffer candidate)
  {
    for(MerchantOffer existing: offers) {
      if(isEquivalent(existing, candidate)) return false;
    }
    offers.add(candidate);
    return true;
  }

  /// Drops offers that duplicate an earlier one in the list.
  ///
  /// `Villager.updateTrades` appends the current level's trade set without clearing,
  /// and it is re-entered on every level-up and restock. Any trade that survives filtering
  /// is therefore re-added each time, which is how a librarian accumulates three identical
  /// ink sac trades on the way to level 5.
  public static void removeDuplicates(MerchantOffers offers)
  {
    for(int i = offers.size() - 1; i >= 0; i--) {
      for(int j = 0; j < i; j++) {
        if(isEquivalent(offers.get(i), offers.get(j))) {
          offers.remove(i);
          break;
        }
      }
    }
  }

  /// True if the villager already offers this item with these enchantments -- meaning
  /// there is nothing to teach. Checked against the individual villager's live offers
  /// rather than a per-profession rule, so if only some armorers happen to sell plain
  /// shields, the others can still be taught one.
  public static boolean alreadySells(MerchantOffers offers, ItemStack stack)
  {
    ItemEnchantments wanted = enchantmentsOf(stack);
    for(MerchantOffer offer: offers) {
      ItemStack result = offer.getResult();
      if(result.getItem() != stack.getItem()) continue;
      if(enchantmentsOf(result).equals(wanted)) return true;
    }
    return false;
  }
}
