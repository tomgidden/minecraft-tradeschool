package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/// Sells blank Curse of Copyright books from master librarians.
///
/// This is the supply line for SMP bookshops: a shopkeeper buys blank curse books here,
/// then stamps them onto the enchanted books they sell so competitors can't teach those
/// enchantments onward to their own librarians.
///
/// Unlike the rest of this package, the trade is *native* rather than taught — a master
/// librarian simply has it. That's deliberate: a shop needs a dependable restocking
/// source, which a teaching-gated trade could not provide.
public class CurseBookTradeFactory {

    private static cx.gid.minecraft.tradeschool.config.CurseSection.BookTradeSection config() {
        return cx.gid.minecraft.tradeschool.config.Config.get().curseOfCopyright.bookTrade;
    }

        /// Mixed into the UUID hash so this roll stays independent of any other
    /// per-villager decision that might later seed off the same UUID.
    private static final long SEED_SALT = 0x1EC7C0DEL;

        /// Whether this particular villager stocks the trade.
    ///
    /// Derived from the villager's UUID rather than rolled per restock, so the answer is
    /// stable for the life of the villager: trades regenerate on every level-up and
    /// restock, and a fresh roll each time would make the trade flicker in and out.
    public static boolean isAvailableFor(Villager villager) {
        long seed = villager.getUUID().getMostSignificantBits()
                  ^ villager.getUUID().getLeastSignificantBits()
                  ^ SEED_SALT;
        // java.util.Random with a fixed seed gives a stable, well-distributed first draw.
        return new java.util.Random(seed).nextDouble() < config().availability;
    }

    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Villager villager) {
        // A required level of zero is how the config switches the trade off entirely;
        // without this it would read as "every level qualifies", the exact opposite.
        int requiredLevel = config().requiredLevel;
        if (requiredLevel <= 0) return null;

        if (villager.getVillagerData().level() < requiredLevel) return null;
        if (!isAvailableFor(villager)) return null;

        ItemStack book = createCurseBook(level);
        if (book.isEmpty()) return null;

        Constants.LOGGER.debug("Added Curse of Copyright trade for master librarian {}",
                villager.getUUID());

        // Emeralds and a blank book, as with any other book a librarian sells — the
        // stamp is applied to paper the player provides.
        var trading = cx.gid.minecraft.tradeschool.config.Config.get().trading;
        return new MerchantOffer(
                new ItemCost(Items.EMERALD, bookPrice(level)),  // player pays
                trading.bookCost(),                             // …and a blank book
                book,                                           // player receives
                trading.maxUses,
                config().xp,
                config().priceMultiplier);
    }

        /// What a blank curse book costs in emeralds.
    ///
    /// A flat price by default, because this is a shop supply rather than a find: a
    /// shopkeeper stamping a day's stock needs the cost to be predictable. Setting
    /// `book_price` to null instead prices it like any other enchanted book, for
    /// operators who would rather it scaled with the rest of the economy.
    private static int bookPrice(ServerLevel level) {
        Integer configured = config().bookPrice;
        if (configured != null) return Math.max(1, configured);

        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return registry.get(ModEnchantments.CURSE_OF_COPYRIGHT)
            .map(curse -> LearnedTradeFactory.estimateBookPrice(curse, 1))
            .orElse(1);
    }

        /// Builds a book carrying the curse and nothing else. That purity matters: it is what
    /// marks the book as a blank stamp rather than protected content, and it is what lets
    /// a librarian learn it — see [ModEnchantments#isPureCurseBook].
    private ItemStack createCurseBook(ServerLevel level) {
        // Absent if the mod's datapack hasn't loaded (game tests construct bare levels).
        var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var curseOpt = registry.get(ModEnchantments.CURSE_OF_COPYRIGHT);
        if (curseOpt.isEmpty()) {
            Constants.LOGGER.warn("Curse of Copyright not in registry; skipping librarian trade");
            return ItemStack.EMPTY;
        }
        Holder<Enchantment> curse = curseOpt.get();

        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchantments = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchantments.set(curse, 1);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchantments.toImmutable());
        return book;
    }
}
