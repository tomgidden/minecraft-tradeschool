package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.trade.TradeFilter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept and replace librarian villager trade generation.
 * This replaces vanilla random trades with our custom UUID-seeded and
 * teaching-based system.
 */
@Mixin(Villager.class)
public abstract class VillagerTradesMixin {

    /**
     * Inject AFTER vanilla updateTrades completes to overwrite trades.
     */
    @Inject(method = "updateTrades", at = @At("RETURN"), require = 0)
    private void onUpdateTradesComplete(CallbackInfo ci) {
        interceptTradeUpdate(ci);
    }

    /**
     * Checks if a profession supports item teaching (Weaponsmith, Toolsmith, Armourer, Fletcher).
     */
    @org.spongepowered.asm.mixin.Unique
    private boolean isItemTeachingProfession(String profession) {
        return profession.contains("weaponsmith") ||
               profession.contains("toolsmith") ||
               profession.contains("armorer") ||
               profession.contains("fletcher");
    }

    private void interceptTradeUpdate(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        // Offers only exist server-side; getOffers() throws on the client.
        if (!(villager.level() instanceof ServerLevel)) return;

        VillagerData data = villager.getVillagerData();
        String professionId = data.profession().toString();

        Constants.debug("Intercepting potential trade update. Profession: {}, Level: {}",
                data.profession(), data.level());

        // Check if this is a profession we're modifying
        boolean isLibrarian = professionId.contains("librarian");
        boolean isItemProfession = isItemTeachingProfession(professionId);

        if (!isLibrarian && !isItemProfession) {
            return; // Not a profession we're handling
        }

        Constants.debug("Intercepting trade update (Overwrite) for {} villager at level {}",
                professionId, data.level());

        // DO NOT CANCEL. Let vanilla run, then overwrite.

        // Generate our custom trades based on profession
        if (isLibrarian) {
            generateCustomLibrarianTrades(villager, data);
        } else if (isItemProfession) {
            generateCustomItemProfessionTrades(villager, data, professionId);
        }
    }

    /**
     * Generates custom trades for librarian villagers.
     * Includes: initial UUID trade, teaching trades, learned trades, and vanilla
     * non-book trades.
     */
    private void generateCustomLibrarianTrades(Villager villager, VillagerData data) {
        MerchantOffers offers = villager.getOffers();
        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Strip vanilla's enchanted-book trades, keeping its commodity trades (paper,
        // bookshelf, lantern, glass, clock, compass, candles) with their real prices.
        offers.removeIf(o -> TradeFilter.shouldRemove(o, "librarian"));
        TradeFilter.removeDuplicates(offers);

        addFillerTrades(villager, offers, "librarian", professionLevel);

        // Blank Curse of Copyright books — half of all master librarians stock them.
        MerchantOffer curseOffer = new cx.gid.minecraft.tradeschool.trade.CurseBookTradeFactory()
                .getOffer((ServerLevel) villager.level(), villager);
        if (curseOffer != null) {
            TradeFilter.addOrReplaceCheaper(offers, curseOffer);
        }

        // Learned enchantment trades at the bottom
        java.util.List<cx.gid.minecraft.tradeschool.data.EnchantmentKnowledge> learnedEnchants = knowledge
                .getKnowledgeUpToLevel(professionLevel);
        for (cx.gid.minecraft.tradeschool.data.EnchantmentKnowledge enchantKnowledge : learnedEnchants) {
            ServerLevel level = (ServerLevel) villager.level();
            cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory learnedTrade = new cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory(
                    enchantKnowledge);
            MerchantOffer offer = learnedTrade.getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                TradeFilter.addOrReplaceCheaper(offers, offer);
            }
        }

        Constants.debug("Librarian L{} now has {} trades", professionLevel, offers.size());
    }

    
    /**
     * Generates custom trades for item-teaching professions
     * (Weaponsmith, Toolsmith, Armourer, Fletcher).
     * Includes: teaching trades, learned item trades, and vanilla basic trades.
     */
    private void generateCustomItemProfessionTrades(Villager villager, VillagerData data, String professionId) {
        MerchantOffers offers = villager.getOffers();
        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Strip anything this profession could be taught — enchanted or not — while
        // keeping vanilla's commodity buys (flint, diamond, lava bucket, feather, …).
        offers.removeIf(o -> TradeFilter.shouldRemove(o, professionId));
        TradeFilter.removeDuplicates(offers);

        addFillerTrades(villager, offers, professionId, professionLevel);

        // Learned item trades at the bottom — one per learned level, up to current level
        java.util.List<cx.gid.minecraft.tradeschool.data.ItemKnowledge> learnedItems =
            knowledge.getItemKnowledgeUpToLevel(professionLevel);
        for (cx.gid.minecraft.tradeschool.data.ItemKnowledge itemKnowledge : learnedItems) {
            ServerLevel level = (ServerLevel) villager.level();
            cx.gid.minecraft.tradeschool.trade.LearnedItemTradeFactory learnedTrade =
                new cx.gid.minecraft.tradeschool.trade.LearnedItemTradeFactory(itemKnowledge);
            MerchantOffer offer = learnedTrade.getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                TradeFilter.addOrReplaceCheaper(offers, offer);
            }
        }

        Constants.debug("{} L{} now has {} trades", professionId, professionLevel, offers.size());
    }

    /**
     * Adds one commodity buy trade for a level that would otherwise be empty.
     *
     * Removing teachable item types leaves several levels with no trades at all (armorer
     * L1/L2, weaponsmith L1/L2, toolsmith L1/L2). A villager with no trades earns no
     * experience and can never reach the level at which teaching unlocks, so this is a
     * functional floor rather than flavour. One trade suffices: a taught trade will
     * occupy the other slot.
     *
     * Buy-side by design — it matches vanilla's idiom for low-value profession trades, and
     * gives the player an emerald income now that the gear sales are gone.
     */
    @org.spongepowered.asm.mixin.Unique
    private void addFillerTrades(Villager villager, MerchantOffers offers,
                                 String professionId, int professionLevel) {
        var fillers = cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.trade.fillers;
        if (fillers == null) return;

        for (var filler : fillers) {
            if (!filler.matches(professionId, professionLevel)) continue;
            MerchantOffer offer = filler.toOffer();
            if (offer == null) continue;
            if (TradeFilter.addOrReplaceCheaper(offers, offer)) {
                Constants.debug("Added filler trade {} for {} L{}",
                    filler.material, professionId, professionLevel);
            }
        }
    }
}
