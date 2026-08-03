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

/// Mixin to intercept and replace librarian villager trade generation.
/// This replaces vanilla random trades with our custom UUID-seeded and
/// teaching-based system.
@Mixin(Villager.class)
public abstract class VillagerTradesMixin {

        /// Inject AFTER vanilla updateTrades completes to overwrite trades.
    @Inject(method = "updateTrades", at = @At("RETURN"), require = 0)
    private void onUpdateTradesComplete(CallbackInfo ci) {
        interceptTradeUpdate(ci);
    }

        /// Checks if a profession supports item teaching (Weaponsmith, Toolsmith, Armourer, Fletcher).
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

        /// Generates custom trades for librarian villagers.
    /// Includes: initial UUID trade, teaching trades, learned trades, and vanilla
    /// non-book trades.
    private void generateCustomLibrarianTrades(Villager villager, VillagerData data) {
        MerchantOffers offers = villager.getOffers();
        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Everything taught up to this level, so the filter below can tell this mod's own
        // trades from vanilla's enchanted ones and leave them where they are.
        java.util.List<net.minecraft.world.item.ItemStack> taughtResults = new java.util.ArrayList<>();
        for (var k : knowledge.getKnowledgeUpToLevel(professionLevel)) {
            taughtResults.add(cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory
                .createEnchantedBook(k.getEnchantments()));
        }
        var keep = TradeFilter.taughtOffers(offers, taughtResults);

        // Strip vanilla's enchanted-book trades, keeping its commodity trades (paper,
        // bookshelf, lantern, glass, clock, compass, candles) with their real prices.
        int beforeFilter = offers.size();
        offers.removeIf(o -> TradeFilter.shouldRemove(o, "librarian", keep));
        int filtered = beforeFilter - offers.size();
        TradeFilter.removeDuplicates(offers);

        // Vanilla drew a fixed number of trades before we removed any, so put back what we
        // took using others from the same pool.
        topUpVanillaTrades(villager, offers, "librarian", professionLevel, filtered);

        addFillerTrades(villager, offers, "librarian", professionLevel);

        // Blank Curse of Copyright books — half of all master librarians stock them.
        MerchantOffer curseOffer = new cx.gid.minecraft.tradeschool.trade.CurseBookTradeFactory()
                .getOffer((ServerLevel) villager.level(), villager);
        if (curseOffer != null) {
            TradeFilter.addIfAbsent(offers, curseOffer);
        }

        // Every lesson up to this level — but addIfAbsent means only ones not already
        // present are built, so in practice that is just the level newly reached.
        //
        // Walking the whole history rather than only the current level is what restores a
        // villager whose offers and knowledge have fallen out of step: a world saved by an
        // older build, or a trade lost to deduplication. The knowledge is the record; the
        // offers list is a projection of it, and this reconciles the two.
        //
        // What it must not do is rebuild an offer that is already there. That would reset
        // its uses on every promotion, move it to the bottom of the list, and swap out the
        // object a trade in progress is holding.
        ServerLevel level = (ServerLevel) villager.level();
        for (var enchantKnowledge : knowledge.getKnowledgeUpToLevel(professionLevel)) {
            MerchantOffer offer = new cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory(
                    enchantKnowledge).getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                TradeFilter.addIfAbsent(offers, offer);
            }
        }

        Constants.debug("Librarian L{} now has {} trades", professionLevel, offers.size());
    }

    
        /// Generates custom trades for item-teaching professions
    /// (Weaponsmith, Toolsmith, Armourer, Fletcher).
    /// Includes: teaching trades, learned item trades, and vanilla basic trades.
    private void generateCustomItemProfessionTrades(Villager villager, VillagerData data, String professionId) {
        MerchantOffers offers = villager.getOffers();
        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Everything taught up to this level, so the filter below leaves this mod's own
        // trades alone — see the librarian path.
        java.util.List<net.minecraft.world.item.ItemStack> taughtResults = new java.util.ArrayList<>();
        for (var k : knowledge.getItemKnowledgeUpToLevel(professionId, professionLevel)) {
            taughtResults.add(k.createItemStack());
        }
        var keep = TradeFilter.taughtOffers(offers, taughtResults);

        // Strip anything this profession could be taught — enchanted or not — while
        // keeping vanilla's commodity buys (flint, diamond, lava bucket, feather, …).
        int beforeFilter = offers.size();
        offers.removeIf(o -> TradeFilter.shouldRemove(o, professionId, keep));
        int filtered = beforeFilter - offers.size();
        TradeFilter.removeDuplicates(offers);

        topUpVanillaTrades(villager, offers, professionId, professionLevel, filtered);

        addFillerTrades(villager, offers, professionId, professionLevel);

        // Every lesson up to this level, added only where one is missing — see the
        // librarian path for why it reconciles rather than rebuilds.
        ServerLevel level = (ServerLevel) villager.level();
        for (var itemKnowledge : knowledge.getItemKnowledgeUpToLevel(professionId, professionLevel)) {
            MerchantOffer offer = new cx.gid.minecraft.tradeschool.trade.LearnedItemTradeFactory(
                itemKnowledge).getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                TradeFilter.addIfAbsent(offers, offer);
            }
        }

        Constants.debug("{} L{} now has {} trades", professionId, professionLevel, offers.size());
    }

        /// Replaces vanilla trades this mod removed with others from the same level's set.
    ///
    /// ### Why a level can come up short
    ///
    /// Vanilla picks a fixed number of trades at random from the pool for that level — two,
    /// for a novice librarian, drawn from paper, bookshelf and an enchanted book. Filtering
    /// runs afterwards, so whether the villager ends up with two trades or one depends
    /// entirely on whether the random draw happened to include the book. Two librarians
    /// spawned side by side get visibly different deals for no reason the player can see.
    ///
    /// So the shortfall is made up from what is left of the same pool: the trades vanilla
    /// could have picked and didn't. Nothing is invented, and a level whose every trade was
    /// removed stays empty rather than being padded — [#addFillerTrades] exists for
    /// that case, and answers a different question.
    ///
    /// Only the current level's pool is consulted. Earlier levels' trades are already in
    /// the list and topping up from them would hand a master villager duplicates of its
    /// novice trades.
    @org.spongepowered.asm.mixin.Unique
    private void topUpVanillaTrades(Villager villager, MerchantOffers offers,
                                    String professionId, int professionLevel, int removed) {
        if (removed <= 0) return;

        ServerLevel level = (ServerLevel) villager.level();
        var profession = villager.getVillagerData().profession().value();
        var tradeSetKey = profession.getTrades(professionLevel);
        if (tradeSetKey == null) return;

        var tradeSet = level.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.TRADE_SET)
            .getOptional(tradeSetKey).orElse(null);
        if (tradeSet == null) return;

        net.minecraft.world.level.storage.loot.LootContext lootContext =
            new net.minecraft.world.level.storage.loot.LootContext.Builder(
                new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN,
                        villager.position())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY,
                        villager)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams
                        .ADDITIONAL_COST_COMPONENT_ALLOWED, net.minecraft.util.Unit.INSTANCE)
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.VILLAGER_TRADE))
            .create(tradeSet.randomSequence());

        // Shuffled so the replacement is as arbitrary as the original draw was, rather than
        // every villager of this level falling back to the same trade.
        var candidates = new java.util.ArrayList<>(
            tradeSet.getTrades().stream().toList());
        java.util.Collections.shuffle(candidates, new java.util.Random(villager.getRandom().nextLong()));

        int added = 0;
        for (var candidate : candidates) {
            if (added >= removed) break;
            MerchantOffer offer = candidate.value().getOffer(lootContext);
            if (offer == null) continue;
            if (TradeFilter.shouldRemove(offer, professionId, java.util.List.of())) continue;
            if (TradeFilter.addIfAbsent(offers, offer)) added++;
        }

        if (added > 0) {
            Constants.debug("Replaced {} of {} removed vanilla trades for {} L{}",
                added, removed, professionId, professionLevel);
        }
    }

        /// Adds one commodity buy trade for a level that would otherwise be empty.
    ///
    /// Removing teachable item types leaves several levels with no trades at all (armorer
    /// L1/L2, weaponsmith L1/L2, toolsmith L1/L2). A villager with no trades earns no
    /// experience and can never reach the level at which teaching unlocks, so this is a
    /// functional floor rather than flavour. One trade suffices: a taught trade will
    /// occupy the other slot.
    ///
    /// Buy-side by design — it matches vanilla's idiom for low-value profession trades, and
    /// gives the player an emerald income now that the gear sales are gone.
    @org.spongepowered.asm.mixin.Unique
    private void addFillerTrades(Villager villager, MerchantOffers offers,
                                 String professionId, int professionLevel) {
        // Keyed rather than a list so an operator can amend or delete one entry by name
        // instead of restating the whole set; the keys mean nothing here.
        var fillers = cx.gid.minecraft.tradeschool.config.Config.get().trading.fillers;
        if (fillers == null) return;

        for (var filler : fillers.values()) {
            if (!filler.matches(professionId, professionLevel)) continue;
            MerchantOffer offer = filler.toOffer();
            if (offer == null) continue;
            if (TradeFilter.addIfAbsent(offers, offer)) {
                Constants.debug("Added filler trade {} for {} L{}",
                    filler.item, professionId, professionLevel);
            }
        }
    }
}
