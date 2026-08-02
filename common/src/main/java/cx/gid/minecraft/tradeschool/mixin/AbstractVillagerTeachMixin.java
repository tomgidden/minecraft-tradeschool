package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.IVillagerTeachState;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.trade.EnchantedItemAnalyzer;
import cx.gid.minecraft.tradeschool.trade.ItemPricingCalculator;
import cx.gid.minecraft.tradeschool.trade.TeachingSlotUtil;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles notifyTrade and stopTrading for the teaching mechanic.
 * These methods live on AbstractVillager, not Villager, so they must be
 * injected here. State is shared with VillagerPickupMixin via IVillagerTeachState.
 */
@Mixin(AbstractVillager.class)
public abstract class AbstractVillagerTeachMixin {

    @Inject(method = "notifyTrade", at = @At("HEAD"))
    private void onNotifyTrade(MerchantOffer offer, CallbackInfo ci) {
        if (!((Object) this instanceof Villager villager)) return;
        if (!(villager instanceof IVillagerTeachState state)) return;

        List<Integer>            indices     = state.tradeschool$getTeachOfferIndices();
        List<List<Integer>>      slotGroups  = state.tradeschool$getTeachItemSlotGroups();
        List<List<ItemStack>>    stackGroups = state.tradeschool$getTeachItemStackGroups();
        if (indices.isEmpty()) return;

        MerchantOffers offers = villager.getOffers();

        // Find which teach offer was accepted
        int matchedSlot = -1;
        for (int i = 0; i < indices.size(); i++) {
            int idx = indices.get(i);
            if (idx >= 0 && idx < offers.size() && offers.get(idx) == offer) {
                matchedSlot = i;
                break;
            }
        }
        if (matchedSlot < 0) {
            tradeschool$removeAllTeachOffers(villager, state);
            return;
        }

        // Use the item captured from the trade slot by MerchantResultSlotMixin just before this fires.
        // That's the only reliable source — inventory-diff doesn't work because items are already consumed.
        ItemStack submitted = state.tradeschool$getLastSubmittedItem();
        state.tradeschool$setLastSubmittedItem(ItemStack.EMPTY);

        // If the submitted item matches the offer group's type+enchants, use it (it has correct damage/repair_cost).
        // Otherwise fall back to the first stored copy for this group.
        ItemStack groupFirst = stackGroups.isEmpty() || matchedSlot >= stackGroups.size() || stackGroups.get(matchedSlot).isEmpty()
            ? ItemStack.EMPTY : stackGroups.get(matchedSlot).get(0);

        // What the player actually put in the slot is what gets taught. The offer only
        // invites the trade; it must never decide the lesson, or a player inserting one
        // book can end up teaching a different one that merely matched the offer.
        ItemStack taught;
        if (!submitted.isEmpty()) {
            taught = submitted;
        } else {
            taught = groupFirst;
            Constants.LOGGER.warn("[TradeSchool] notifyTrade: no submitted item captured, falling back to stored copy");
        }

        // Debug logging
        {
            ItemEnchantments tEnchs = taught.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (tEnchs.isEmpty()) tEnchs = taught.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            Constants.debug("[TradeSchool] notifyTrade: teaching item={} enchants={} damage={} repair_cost={}",
                taught.getItem(),
                tEnchs,
                taught.getOrDefault(DataComponents.DAMAGE, 0),
                taught.getOrDefault(DataComponents.REPAIR_COST, 0));
        }

        String professionId = villager.getVillagerData().profession().toString();

        // The only point at which the villager complains about copyright: an actual sale.
        // Teach offers are never built for copyrighted items (VillagerPickupMixin skips
        // them), so this is a backstop — reachable only if an offer is constructed by some
        // path that bypassed that scan.
        if (cx.gid.minecraft.tradeschool.enchantment.ModEnchantments.isCurseProtected(taught)) {
            if (villager.level() instanceof ServerLevel sl) {
                sl.playSound(null, villager.blockPosition(),
                    SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
            }
            tradeschool$tellTeacher(villager,
                cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_COPYRIGHTED);
            tradeschool$refund(villager, taught);
            tradeschool$removeAllTeachOffers(villager, state);
            return;
        }

        if (professionId.contains("librarian")) {
            tradeschool$teachEnchantedBookNow(villager, taught);
        } else {
            tradeschool$teachEnchantedItemNow(villager, taught);
        }

        tradeschool$playTeachingEffects(villager);
        tradeschool$grantTeachingAdvancement(villager);
        tradeschool$grantTeachingReputation(villager);
        tradeschool$sendTaughtTitle(villager, taught, professionId);

        // Grant XP orbs to nearby player equal to the sell price of the learned item
        int professionLevel = villager.getVillagerData().level();
        String label = tradeschool$getProfessionLabel(professionId);
        var analysisResult = EnchantedItemAnalyzer.analyzeItem(taught, professionLevel, label);
        int xpAmount = ItemPricingCalculator.calculateSellingPrice(analysisResult.knowledge());
        tradeschool$spawnXpOrbs(villager, xpAmount);

        tradeschool$removeAllTeachOffers(villager, state);

        if (villager.level() instanceof ServerLevel sl) {
            ((VillagerTradesAccessor) villager).tradeschool$updateTrades(sl);
        }
    }

    @Inject(method = "stopTrading", at = @At("HEAD"))
    private void onStopTrading(CallbackInfo ci) {
        if (!((Object) this instanceof Villager villager)) return;
        // stopTrading also runs client-side (die() via ClientboundEntityEventPacket),
        // where getOffers() throws — offers only exist on the server.
        if (villager.level().isClientSide()) return;
        if (!(villager instanceof IVillagerTeachState state)) return;
        tradeschool$removeAllTeachOffers(villager, state);
    }

    @Unique
    private void tradeschool$removeAllTeachOffers(Villager villager, IVillagerTeachState state) {
        List<Integer> indices = new ArrayList<>(state.tradeschool$getTeachOfferIndices());
        MerchantOffers offers = villager.getOffers();

        // Remove in reverse index order so earlier indices remain valid
        indices.sort(java.util.Comparator.reverseOrder());
        for (int idx : indices) {
            if (idx >= 0 && idx < offers.size()) {
                offers.remove(idx);
            }
        }

        state.tradeschool$setTeachOfferIndices(new ArrayList<>());
        state.tradeschool$setTeachItemSlotGroups(new ArrayList<>());
        state.tradeschool$setTeachItemStackGroups(new ArrayList<>());
        state.tradeschool$setTeachingPlayer(null);

        // Clear mainhand if still holding a preview item
        if (!villager.getMainHandItem().isEmpty()) {
            villager.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    @Unique
    private void tradeschool$teachEnchantedItemNow(Villager villager, ItemStack item) {
        int professionLevel = villager.getVillagerData().level();
        String professionId = villager.getVillagerData().profession().toString();
        String label = tradeschool$getProfessionLabel(professionId);

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        var result = EnchantedItemAnalyzer.analyzeItem(item, professionLevel, label);

        knowledge.teachItem(professionLevel, result.knowledge());
        VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);
        if (villager.getVillagerXp() <= 0) villager.setVillagerXp(1);

        var k = result.knowledge();
        ItemStack learned = k.createItemStack();
        Constants.debug("[TradeSchool] Villager {} learned: item={} enchants={} damage={} repair_cost={} at level {}",
            villager.getUUID(), k.getBaseItem().value(), k.getEnchantments(),
            learned.getOrDefault(DataComponents.DAMAGE, 0),
            learned.getOrDefault(DataComponents.REPAIR_COST, 0),
            professionLevel);
        tradeschool$tellTeacher(villager,
            cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_LEARNED,
            cx.gid.minecraft.tradeschool.trade.Describe.villager(villager, label),
            result.learnedDescription());
    }

    /**
     * Hands an item back to the teaching player after a lesson could not go ahead.
     *
     * By the time notifyTrade runs, vanilla has already taken the payment and handed over
     * the reward — there is no way to veto the trade from here. Every bail-out below is
     * therefore an item the player has paid with and received nothing for, so it has to be
     * returned. The scan-time checks in VillagerPickupMixin should prevent all of these
     * from ever being reachable; this exists so that a gap in those checks costs the player
     * nothing worse than a confusing message.
     */
    @Unique
    private void tradeschool$refund(Villager villager, ItemStack item) {
        if (item.isEmpty()) return;
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        java.util.UUID teacher = ((IVillagerTeachState) villager).tradeschool$getTeachingPlayer();
        ServerPlayer player = teacher != null
            ? serverLevel.getServer().getPlayerList().getPlayer(teacher)
            : null;

        ItemStack copy = item.copy();
        if (player != null) {
            // Falls to the ground at the player's feet if their inventory is full.
            if (!player.getInventory().add(copy)) {
                player.drop(copy, false);
            }
        } else {
            // No player to hand it to — drop it by the villager rather than destroy it.
            net.minecraft.world.entity.item.ItemEntity dropped =
                new net.minecraft.world.entity.item.ItemEntity(serverLevel,
                    villager.getX(), villager.getY() + 0.5, villager.getZ(), copy);
            serverLevel.addFreshEntity(dropped);
        }

        // "Villager disagrees" over the sound of them going back to work.
        String professionId = villager.getVillagerData().profession().toString();
        serverLevel.playSound(null, villager.blockPosition(),
            SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
        var work = tradeschool$workSound(professionId);
        if (work != null) {
            serverLevel.playSound(null, villager.blockPosition(), work, SoundSource.NEUTRAL, 0.7f, 1.0f);
        }

        // Keep the player-facing note short; the detail belongs in the log below.
        if (player != null) {
            player.sendSystemMessage(cx.gid.minecraft.tradeschool.TradeSchoolMessages.of(
                player, cx.gid.minecraft.tradeschool.TradeSchoolMessages.REFUND,
                copy.getHoverName().getString()));
        }
        Constants.LOGGER.warn(
            "[TradeSchool] Refunded {} — a teach offer was accepted that should never have been "
            + "built. Vanilla completes the trade before notifyTrade fires, so it can only be "
            + "undone, not prevented. The scan in VillagerPickupMixin is meant to catch this "
            + "first; reaching here means a gap in those checks.", copy.getItem());
    }

    @Unique
    private void tradeschool$teachEnchantedBookNow(Villager villager, ItemStack book) {
        if (book.getItem() != Items.ENCHANTED_BOOK) return;
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.isEmpty()) {
            tradeschool$refund(villager, book);
            return;
        }

        int professionLevel = villager.getVillagerData().level();

        // Honour the same gate analyzeItem applies when building the offer: single-level
        // enchantments need a villager of at least the configured level. Picking the first
        // stored enchantment unconditionally would let a book the villager was never
        // offered — Silk Touch at level 2, say — be learned anyway.
        var analysis = EnchantedItemAnalyzer.analyzeItem(book, professionLevel, "librarian");
        var learnable = analysis.knowledge().getEnchantments();
        if (learnable.isEmpty()) {
            Constants.debug("Villager {} cannot learn anything from this book at level {}",
                villager.getUUID(), professionLevel);
            tradeschool$refund(villager, book);
            return;
        }

        // Every learnable enchantment from the book, not just the first — a Protection +
        // Unbreaking book teaches both, and produces one trade selling both.
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        if (!knowledge.teachEnchantments(professionLevel, learnable)) {
            tradeschool$refund(villager, book);
            tradeschool$tellTeacher(villager,
                cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_LESSON_USED,
                cx.gid.minecraft.tradeschool.trade.Describe.villager(villager, "librarian"));
            return;
        }
        VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);
        if (villager.getVillagerXp() <= 0) villager.setVillagerXp(1);

        StringBuilder names = new StringBuilder();
        for (var e : learnable.entrySet()) {
            if (names.length() > 0) names.append(", ");
            names.append(tradeschool$capitaliseWords(e.getKey().unwrapKey()
                .map(k -> k.identifier().getPath().replace('_', ' ')).orElse("unknown")));
            if (e.getKey().value().getMaxLevel() != 1) {
                names.append(' ').append(tradeschool$toRoman(e.getValue()));
            }
        }
        tradeschool$tellTeacher(villager,
            cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_LEARNED,
            cx.gid.minecraft.tradeschool.trade.Describe.villager(villager, "librarian"),
            cx.gid.minecraft.tradeschool.trade.Describe.enchantments(learnable));
        Constants.debug("Villager {} learned enchantments {}", villager.getUUID(), learnable);
    }

    /**
     * Rewards the teaching player with gossip, improving the prices this villager offers
     * them. Amounts are configurable; by default only MINOR_POSITIVE is granted, so
     * teaching never reaches the permanent cure-tier discount.
     */
    @Unique
    private void tradeschool$grantTeachingReputation(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        var repConfig = cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.reputation;
        if (repConfig.minorPositivePerTeach <= 0 && repConfig.majorPositivePerTeach <= 0) return;

        // Prefer the player the teach offer was built for; fall back to the nearest player
        // so a teach completed through some other path still credits someone.
        java.util.UUID teacher = ((IVillagerTeachState) villager).tradeschool$getTeachingPlayer();
        ServerPlayer player = teacher != null
            ? serverLevel.getServer().getPlayerList().getPlayer(teacher)
            : null;
        if (player == null) {
            player = serverLevel.getNearestPlayer(villager, 16.0) instanceof ServerPlayer sp ? sp : null;
        }
        if (player == null) return;

        var gossips = villager.getGossips();
        if (repConfig.minorPositivePerTeach > 0) {
            gossips.add(player.getUUID(),
                net.minecraft.world.entity.ai.gossip.GossipType.MINOR_POSITIVE,
                repConfig.minorPositivePerTeach);
        }
        if (repConfig.majorPositivePerTeach > 0) {
            gossips.add(player.getUUID(),
                net.minecraft.world.entity.ai.gossip.GossipType.MAJOR_POSITIVE,
                repConfig.majorPositivePerTeach);
        }

        Constants.debug("[TradeSchool] Granted teaching reputation to {} (minor +{}, major +{}); reputation now {}",
            player.getGameProfile().name(),
            repConfig.minorPositivePerTeach, repConfig.majorPositivePerTeach,
            villager.getPlayerReputation(player));
    }

    @Unique
    private void tradeschool$spawnXpOrbs(Villager villager, int amount) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        // Spawn at the nearby trading player's position if possible, else at villager
        serverLevel.getPlayers(p -> {
            if (p instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= 16 * 16) {
                ExperienceOrb.award(serverLevel, sp.position(), amount);
            }
            return false;
        });
    }

    /**
     * A centre-screen title announcing what the villager learned.
     *
     * Teaching is the mod's payoff moment and easy to miss in chat, so it gets the loudest
     * feedback the server can send without a client mod. Titles are pure packets — no
     * resource pack or client-side code involved.
     */
    @Unique
    private void tradeschool$sendTaughtTitle(Villager villager, ItemStack taught, String professionId) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        var feedback = cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.feedback;
        if (!feedback.title) return;

        java.util.UUID teacher = ((IVillagerTeachState) villager).tradeschool$getTeachingPlayer();
        ServerPlayer player = teacher != null
            ? serverLevel.getServer().getPlayerList().getPlayer(teacher)
            : null;
        if (player == null) return;

        String label = tradeschool$getProfessionLabel(professionId);
        int professionLevel = villager.getVillagerData().level();
        var result = EnchantedItemAnalyzer.analyzeItem(taught, professionLevel, label);
        String learned = result.learnedDescription();

        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(
            feedback.titleFadeInTicks, feedback.titleStayTicks, feedback.titleFadeOutTicks));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
            cx.gid.minecraft.tradeschool.TradeSchoolMessages.of(
                player, cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_SUBTITLE,
                label, learned)));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
            cx.gid.minecraft.tradeschool.TradeSchoolMessages.of(
                player, cx.gid.minecraft.tradeschool.TradeSchoolMessages.TAUGHT_TITLE)));
    }

    /**
     * The sound of a villager plying their trade — a librarian's page-turn, a weaponsmith's
     * grindstone. Null for anything unexpected rather than guessing at a wrong profession.
     *
     * Sound choice is constrained by subtitles. A server-side-only mod cannot register its
     * own sound events (the client resolves identifiers against assets it holds, so an
     * unknown id is simply silent), which rules out a bespoke "Villager learns" cue. Every
     * vanilla event used here was picked because its subtitle is *honest*: "Librarian
     * works" and "Villager cheers" both describe what happened. Tempting alternatives were
     * rejected on that basis — enchantment_table.use subtitles as "Enchanting Table used"
     * when no table exists, and player.levelup as "Player dings" when it is the villager
     * who gained a level.
     */
    @Unique
    private net.minecraft.sounds.SoundEvent tradeschool$workSound(String professionId) {
        if (professionId.contains("librarian"))   return SoundEvents.VILLAGER_WORK_LIBRARIAN;
        if (professionId.contains("armorer"))     return SoundEvents.VILLAGER_WORK_ARMORER;
        if (professionId.contains("toolsmith"))   return SoundEvents.VILLAGER_WORK_TOOLSMITH;
        if (professionId.contains("weaponsmith")) return SoundEvents.VILLAGER_WORK_WEAPONSMITH;
        if (professionId.contains("fletcher"))    return SoundEvents.VILLAGER_WORK_FLETCHER;
        return null;
    }

    @Unique
    private void tradeschool$playTeachingEffects(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        // Enchanting glyphs rising through the usual happy-villager sparkle, to read as
        // knowledge transferring rather than an ordinary trade.
        for (int i = 0; i < 20; i++) {
            double ox = (villager.getRandom().nextDouble() - 0.5) * 0.6;
            double oy = villager.getRandom().nextDouble() * 1.8;
            double oz = (villager.getRandom().nextDouble() - 0.5) * 0.6;
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                villager.getX() + ox, villager.getY() + oy, villager.getZ() + oz,
                1, 0.0, 0.1, 0.0, 0.0);
        }
        for (int i = 0; i < 12; i++) {
            double ox = (villager.getRandom().nextDouble() - 0.5) * 1.2;
            double oy = 0.4 + villager.getRandom().nextDouble() * 1.6;
            double oz = (villager.getRandom().nextDouble() - 0.5) * 1.2;
            serverLevel.sendParticles(ParticleTypes.ENCHANT,
                villager.getX() + ox, villager.getY() + oy, villager.getZ() + oz,
                1, 0.0, -0.2, 0.0, 0.6);
        }

        String professionId = villager.getVillagerData().profession().toString();
        var work = tradeschool$workSound(professionId);
        if (work != null) {
            serverLevel.playSound(null, villager.blockPosition(), work, SoundSource.NEUTRAL, 1.0f, 1.0f);
        }
        serverLevel.playSound(null, villager.blockPosition(),
            SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0f, 1.0f);
    }

    @Unique
    private void tradeschool$grantTeachingAdvancement(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= 16 * 16) {
                try {
                    Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teach_villager");
                    net.minecraft.advancements.AdvancementHolder adv =
                        serverLevel.getServer().getAdvancements().get(id);
                    if (adv != null) sp.getAdvancements().award(adv, "taught_villager");
                } catch (Exception e) {
                    Constants.LOGGER.error("Failed to grant teaching advancement", e);
                }
            }
            return false;
        });
    }

    /**
     * Tells the player who did the teaching.
     *
     * Replaces an earlier broadcast to everyone within 16 blocks. That could not be
     * translated — one literal string went to every recipient regardless of their
     * language — and it was telling bystanders about a lesson they had no part in. Every
     * message sent this way is the outcome of a trade one player just made, so it belongs
     * to that player.
     */
    @Unique
    private void tradeschool$tellTeacher(Villager villager, String key, Object... args) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;

        java.util.UUID teacher = ((IVillagerTeachState) villager).tradeschool$getTeachingPlayer();
        ServerPlayer player = teacher != null
            ? serverLevel.getServer().getPlayerList().getPlayer(teacher)
            : null;
        if (player == null) {
            // A teach completed by some path that did not record the player; fall back to
            // whoever is closest rather than saying nothing.
            player = serverLevel.getNearestPlayer(villager, 16.0) instanceof ServerPlayer sp ? sp : null;
        }
        if (player == null) return;

        var msg = cx.gid.minecraft.tradeschool.TradeSchoolMessages.of(player, key, args);
        Constants.debug(msg.getString());
        if (cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
                .getConfig().global.feedback.chat) {
            player.sendSystemMessage(msg);
        }
        tradeschool$indicateSpeaker(villager);
    }

    /**
     * Particles and a mumble from the villager that just spoke, so it is clear which one
     * meant it. Skipped where a louder effect already plays, such as a successful lesson.
     */
    @Unique
    private void tradeschool$indicateSpeaker(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            villager.getX(), villager.getY() + villager.getBbHeight() + 0.4, villager.getZ(),
            6, 0.25, 0.15, 0.25, 0.0);
        serverLevel.playSound(null, villager.blockPosition(),
            SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, 0.7f, 1.0f);
    }

    @Unique
    private String tradeschool$getProfessionLabel(String professionId) {
        if (professionId.contains("librarian"))   return "librarian";
        if (professionId.contains("weaponsmith")) return "weaponsmith";
        if (professionId.contains("toolsmith"))   return "toolsmith";
        if (professionId.contains("armorer"))     return "armorer";
        if (professionId.contains("fletcher"))    return "fletcher";
        return "villager";
    }

    @Unique
    private static String tradeschool$capitaliseWords(String s) {
        String[] words = s.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    @Unique
    private static String tradeschool$toRoman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III";
            case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n);
        };
    }
}
