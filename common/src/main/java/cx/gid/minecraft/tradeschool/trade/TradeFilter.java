package cx.gid.minecraft.tradeschool.trade;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

/**
 * Decides which vanilla trades survive, and whether two offers are the same trade.
 *
 * The governing rule: a profession must not sell by default anything it can be *taught*.
 * Unenchanted iron, stone and copper gear is trivially craftable, so selling it undercuts
 * the teaching mechanic for no benefit — the point of the mod is that gear and
 * enchantments come from exploration and teaching, not from rerolling villagers.
 *
 * Three item types are exempt because their materials are genuinely scarce, so buying them
 * is a real convenience rather than a shortcut past teaching:
 * bows and crossbows (string, especially in peaceful), plain books (leather and paper),
 * and shields (iron).
 */
public class TradeFilter {

    /** Enchantments carried by a stack, whether worn (gear) or stored (books). */
    public static ItemEnchantments enchantmentsOf(ItemStack stack) {
        ItemEnchantments e = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (e.isEmpty()) {
            e = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        }
        return e;
    }

    /**
     * True if this item may still be sold untaught despite being a teachable type.
     * See the class comment for why each is exempt.
     */
    public static boolean isScarcityException(Item item) {
        return item == Items.BOW
            || item == Items.CROSSBOW
            || item == Items.BOOK
            || item == Items.SHIELD;
    }

    /**
     * True if the offer should be stripped from a villager's default trades.
     *
     * Two independent reasons: the result is enchanted (every vanilla enchant-function
     * trade produces an enchanted output), or the result is an item type this profession
     * can be taught.
     */
    public static boolean shouldRemove(MerchantOffer offer, String professionId) {
        ItemStack result = offer.getResult();
        if (result.isEmpty()) return false;

        if (!enchantmentsOf(result).isEmpty()) return true;

        Item item = result.getItem();
        if (isScarcityException(item)) return false;
        return EnchantedItemAnalyzer.isItemForProfession(item, professionId);
    }

    /**
     * Whether two offers are the same trade for deduplication purposes: same result item
     * with the same enchantments, bought with the same currency. Price is deliberately
     * excluded — two offers for the same goods at different prices are duplicates, and the
     * caller picks which to keep.
     */
    public static boolean isEquivalent(MerchantOffer a, MerchantOffer b) {
        ItemStack ra = a.getResult(), rb = b.getResult();
        if (ra.getItem() != rb.getItem()) return false;
        if (!enchantmentsOf(ra).equals(enchantmentsOf(rb))) return false;
        return a.getCostA().getItem() == b.getCostA().getItem();
    }

    /** The offer's price in its primary cost item, used to decide which duplicate wins. */
    private static int priceOf(MerchantOffer offer) {
        return offer.getCostA().getCount();
    }

    /**
     * Adds {@code candidate} unless an equivalent offer is already present; if one is,
     * keeps whichever is cheaper for the player.
     *
     * Vanilla appends each level's trades on promotion rather than rebuilding, and the
     * mod re-derives every learned trade from L1 upward on each update, so without this
     * a taught villager accumulates a fresh copy of each learned trade at every rank.
     *
     * @return true if the candidate ended up in the list.
     */
    public static boolean addOrReplaceCheaper(MerchantOffers offers, MerchantOffer candidate) {
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer existing = offers.get(i);
            if (!isEquivalent(existing, candidate)) continue;
            if (priceOf(candidate) < priceOf(existing)) {
                offers.set(i, candidate);
                return true;
            }
            return false; // existing is cheaper or equal — leave it alone
        }
        offers.add(candidate);
        return true;
    }

    /**
     * Drops offers that duplicate an earlier one in the list.
     *
     * {@code Villager.updateTrades} appends the current level's trade set without clearing,
     * and it is re-entered on every level-up and restock. Any trade that survives filtering
     * is therefore re-added each time, which is how a librarian accumulates three identical
     * ink sac trades on the way to level 5.
     */
    public static void removeDuplicates(MerchantOffers offers) {
        for (int i = offers.size() - 1; i >= 0; i--) {
            for (int j = 0; j < i; j++) {
                if (isEquivalent(offers.get(i), offers.get(j))) {
                    offers.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * True if the villager already offers this item with these enchantments — meaning
     * there is nothing to teach. Checked against the individual villager's live offers
     * rather than a per-profession rule, so if only some armorers happen to sell plain
     * shields, the others can still be taught one.
     */
    public static boolean alreadySells(MerchantOffers offers, ItemStack stack) {
        ItemEnchantments wanted = enchantmentsOf(stack);
        for (MerchantOffer offer : offers) {
            ItemStack result = offer.getResult();
            if (result.getItem() != stack.getItem()) continue;
            if (enchantmentsOf(result).equals(wanted)) return true;
        }
        return false;
    }
}
