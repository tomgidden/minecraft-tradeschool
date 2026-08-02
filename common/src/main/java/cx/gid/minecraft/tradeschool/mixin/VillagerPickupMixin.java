package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.TradeSchoolMessages;
import cx.gid.minecraft.tradeschool.IVillagerTeachState;
import cx.gid.minecraft.tradeschool.PlayerHintAccessor;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import cx.gid.minecraft.tradeschool.trade.EnchantedItemAnalyzer;
import cx.gid.minecraft.tradeschool.trade.ItemPricingCalculator;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mixin to enable villagers to learn from items traded via the UI.
 *
 * When a player right-clicks a tradeschool villager, teachable items in the mainhand,
 * hotbar and main inventory (up to max_teach_offers, excluding netherite) get ephemeral
 * teach offers inserted. Worn armour and the offhand are excluded: MerchantMenu can only
 * draw payment from menu slots 3..38, so an offer built from either could never be paid.
 *
 * Dedup is by enchantments only (not damage). Two swords with the same enchants
 * but different damage collapse to one offer. We record all inventory slots that
 * hold a qualifying item for each offer group. At notifyTrade time we inspect
 * the player's inventory to find which slot was consumed, then use that slot's
 * stored copy (which has the correct damage/repair_cost) as the taught item.
 */
@Mixin(Villager.class)
public abstract class VillagerPickupMixin implements IVillagerTeachState {

    @Unique private UUID tradeschool$teachingPlayer = null;
    @Unique private List<Integer>            tradeschool$teachOfferIndices    = new ArrayList<>();
    @Unique private List<List<Integer>>      tradeschool$teachItemSlotGroups  = new ArrayList<>();
    @Unique private List<List<ItemStack>>    tradeschool$teachItemStackGroups = new ArrayList<>();
    @Unique private ItemStack                tradeschool$lastSubmittedItem    = ItemStack.EMPTY;

    @Override public UUID tradeschool$getTeachingPlayer() { return tradeschool$teachingPlayer; }
    @Override public void tradeschool$setTeachingPlayer(UUID uuid) { tradeschool$teachingPlayer = uuid; }
    @Override public List<Integer> tradeschool$getTeachOfferIndices() { return tradeschool$teachOfferIndices; }
    @Override public void tradeschool$setTeachOfferIndices(List<Integer> i) { tradeschool$teachOfferIndices = i; }
    @Override public List<List<Integer>> tradeschool$getTeachItemSlotGroups() { return tradeschool$teachItemSlotGroups; }
    @Override public void tradeschool$setTeachItemSlotGroups(List<List<Integer>> g) { tradeschool$teachItemSlotGroups = g; }
    @Override public List<List<ItemStack>> tradeschool$getTeachItemStackGroups() { return tradeschool$teachItemStackGroups; }
    @Override public void tradeschool$setTeachItemStackGroups(List<List<ItemStack>> g) { tradeschool$teachItemStackGroups = g; }
    @Override public ItemStack tradeschool$getLastSubmittedItem() { return tradeschool$lastSubmittedItem; }
    @Override public void tradeschool$setLastSubmittedItem(ItemStack s) { tradeschool$lastSubmittedItem = s; }

    /** Set by refusalReason when a message needs a detail; read straight after. */
    @Unique private String tradeschool$lastRefusalDetail = "";

    /**
     * What each nearby player was last told about, and when.
     *
     * Keyed by player, holding the hash of the item described and the game time it was
     * mentioned. Entries expire rather than being cleared the moment a player stops being
     * relevant: a villager pathing in and out of range would otherwise erase its own
     * memory and greet the player again every few seconds, which is what a wandering
     * librarian does constantly.
     */
    @Unique private final Map<UUID, long[]> tradeschool$notifiedHeldItems = new HashMap<>();

    /**
     * How long before the same player can be told the same thing again, in ticks.
     * Twenty minutes: long enough not to nag, short enough that a player who has genuinely
     * forgotten gets a reminder.
     */
    @Unique private static final long TRADESCHOOL$REMIND_AFTER_TICKS = 24000L;
    @Unique private int tradeschool$lastKnownLevel = 0;

    /**
     * True if this player has already been told about this exact thing recently.
     * Records the mention when it has not.
     */
    @Unique
    private boolean tradeschool$alreadyTold(ServerPlayer player, int what, long now) {
        long[] last = tradeschool$notifiedHeldItems.get(player.getUUID());
        if (last != null && last[0] == what && now - last[1] < TRADESCHOOL$REMIND_AFTER_TICKS) {
            return true;
        }
        tradeschool$notifiedHeldItems.put(player.getUUID(), new long[]{what, now});
        return false;
    }

    @Unique
    private static cx.gid.minecraft.tradeschool.loot.config.TradeConfig tradeschool$config() {
        return cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.trade;
    }

    @Unique
    private static cx.gid.minecraft.tradeschool.loot.config.FeedbackConfig tradeschool$feedback() {
        return cx.gid.minecraft.tradeschool.loot.LootDistributionManager.getInstance()
            .getConfig().global.feedback;
    }

    // ── mobInteract — insert teach offers before UI opens ────────────────────

    @Inject(method = "mobInteract", at = @At("HEAD"))
    private void onMobInteract(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        if (!(player instanceof ServerPlayer sp)) return;
        Villager villager = (Villager) (Object) this;
        if (villager.level().isClientSide()) return;

        String professionId = villager.getVillagerData().profession().toString();
        if (!isTradeSchoolProfession(professionId)) return;

        // Only one player at a time gets ephemeral teach offers
        if (tradeschool$teachingPlayer != null && !tradeschool$teachingPlayer.equals(sp.getUUID())) return;

        int professionLevel = villager.getVillagerData().level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);

        // No offers if already learned at current level
        // Note: the "already learned at this level" case is handled per-item in
        // tradeschool$refusalReason, so the player is told rather than silently ignored.

        // Scan both hands, grouped by dedup signature (enchantments only, not damage).
        ScanResult scan = tradeschool$scanAndGroup(sp, professionId, villager);

        // Explain anything turned down, so a refusal is never a mystery.
        if (!scan.refusals.isEmpty()) tradeschool$indicateSpeaker(villager);
        for (String refusalKey : scan.refusals) {
            var msg = TradeSchoolMessages.of(sp, refusalKey,
                tradeschool$villagerName(villager, professionId), scan.refusalDetail);
            if (tradeschool$feedback().chat) sp.sendSystemMessage(msg);
            if (tradeschool$feedback().actionBar) sp.sendSystemMessage(msg, true);
        }

        if (scan.groups.isEmpty()) return;

        // Debug: log all found groups and their slot copies
        for (OfferGroup g : scan.groups) {
            for (int gi = 0; gi < g.slots.size(); gi++) {
                ItemStack s = g.stacks.get(gi);
                ItemEnchantments ge = s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                if (ge.isEmpty()) ge = s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                Constants.debug("[TradeSchool] Teachable slot {}: item={} enchants={} damage={} repair_cost={}",
                    g.slots.get(gi), s.getItem(), ge,
                    s.getOrDefault(DataComponents.DAMAGE, 0),
                    s.getOrDefault(DataComponents.REPAIR_COST, 0));
            }
        }

        MerchantOffers offers = villager.getOffers();
        List<Integer>         insertedIndices    = new ArrayList<>();
        List<List<Integer>>   insertedSlotGroups = new ArrayList<>();
        List<List<ItemStack>> insertedStackGroups = new ArrayList<>();

        for (OfferGroup group : scan.groups) {
            // Use the first stack in the group to build the offer (enchantments are the same)
            MerchantOffer offer = tradeschool$buildTeachOffer(villager, group.firstStack, professionId, professionLevel);
            if (offer == null) continue;

            insertedIndices.add(offers.size());
            offers.add(offer);
            insertedSlotGroups.add(group.slots);
            insertedStackGroups.add(group.stacks);
        }

        if (!insertedIndices.isEmpty()) {
            tradeschool$teachOfferIndices    = insertedIndices;
            tradeschool$teachItemSlotGroups  = insertedSlotGroups;
            tradeschool$teachItemStackGroups = insertedStackGroups;
            tradeschool$teachingPlayer       = sp.getUUID();
        }
    }

    // ── slot scanning ────────────────────────────────────────────────────────

    private static class OfferGroup {
        final String sig;
        final ItemStack firstStack;
        final List<Integer> slots   = new ArrayList<>();
        final List<ItemStack> stacks = new ArrayList<>();

        OfferGroup(String sig, int slot, ItemStack stack) {
            this.sig = sig;
            this.firstStack = stack.copy();
            slots.add(slot);
            stacks.add(stack.copy());
        }
    }

    private static class ScanResult {
        final List<OfferGroup> groups = new ArrayList<>();
        /** Message keys for reasons the held item was turned down. */
        final List<String> refusals = new ArrayList<>();
        /** Extra detail for the refusal message — an enchantment name, usually. */
        String refusalDetail = "";
        /**
         * Items this villager won't learn, shown as greyed-out offers so the player can see
         * they were considered. Kept apart from {@link #groups} because they only fill the
         * offer limit once every viable trade has a place.
         */
        final List<OfferGroup> rejected = new ArrayList<>();
    }

    /**
     * Scans the slots a trade can actually be paid from for something teachable.
     *
     * Groups by enchantment-only signature (dedup identical-enchant swords with different damage).
     * Records all slot indices per group so notifyTrade can identify which slot was consumed.
     *
     * Slot numbers follow Inventory internal layout: 0-8 hotbar, 9-35 main inventory.
     * 36-39 (armour) and 40 (offhand) are deliberately not scanned.
     */
    @Unique
    private ScanResult tradeschool$scanAndGroup(ServerPlayer sp, String professionId, Villager villager) {
        int villagerLevel = villager.getVillagerData().level();
        Inventory inv = sp.getInventory();

        // Scan the slots a trade can actually be paid from, mirroring vanilla: selling
        // pumpkins to a farmer draws from anywhere in the backpack, so teaching should too.
        //
        // MerchantMenu holds menu slots 3..38 — the 27 extended inventory slots
        // (Inventory 9..35) plus the 9 hotbar slots (Inventory 0..8). Worn armour and the
        // offhand are not in the menu at all, so an offer built from either can never be
        // paid: moveFromInventoryToPaymentSlot would find nothing and the trade would sit
        // there unfulfillable. Those slots are therefore excluded.
        //
        // Mainhand first so the item being held is the most prominent offer.
        ScanResult result = new ScanResult();
        Map<String, OfferGroup> seenSigs = new HashMap<>();

        int selectedHotbar = 0;
        for (int i = 0; i <= 8; i++) {
            if (inv.getItem(i) == sp.getMainHandItem()) { selectedHotbar = i; break; }
        }

        List<Integer> slotOrder = new ArrayList<>();
        slotOrder.add(selectedHotbar);
        for (int i = 0; i <= 8; i++) if (i != selectedHotbar) slotOrder.add(i);
        for (int i = 9; i <= 35; i++) slotOrder.add(i);

        int maxOffers = tradeschool$config().maxTeachOffers;

        for (int slotIdx : slotOrder) {
            ItemStack stack = inv.getItem(slotIdx);
            if (stack.isEmpty()) continue;

            tradeschool$lastRefusalDetail = "";
            String refusal = tradeschool$refusalReason(stack, professionId, villager, villagerLevel);
            if (refusal != null) {
                // Only the held item earns an explanation; narrating every rejected stack
                // in the backpack would bury the player in messages about things they
                // never offered. SILENT_REFUSAL is never worth reporting at all.
                if (slotIdx == selectedHotbar && !SILENT_REFUSAL.equals(refusal)) {
                    result.refusals.add(refusal);
                    result.refusalDetail = tradeschool$lastRefusalDetail;
                }
                // Items outside this profession's remit aren't "rejected" in any meaningful
                // sense — a librarian is not declining your carrot — so they get no
                // greyed-out row. Genuine near-misses do.
                if (!SILENT_REFUSAL.equals(refusal)) {
                    String rsig = "rejected:" + tradeschool$enchantSignature(stack);
                    if (!seenSigs.containsKey(rsig) && result.rejected.size() < maxOffers) {
                        seenSigs.put(rsig, null);
                        result.rejected.add(new OfferGroup(rsig, slotIdx, stack));
                    }
                }
                continue;
            }

            if (result.groups.size() >= maxOffers) continue;

            String sig = tradeschool$enchantSignature(stack);
            OfferGroup group = seenSigs.get(sig);
            if (group == null) {
                group = new OfferGroup(sig, slotIdx, stack);
                seenSigs.put(sig, group);
                result.groups.add(group);
            } else {
                // Same type and enchantments, different damage: one offer covers them all,
                // and whichever copy the player submits is the one that gets consumed.
                group.slots.add(slotIdx);
                group.stacks.add(stack.copy());
            }
        }

        // Armor worn rather than carried is a common near-miss: the player is holding
        // exactly what the villager wants, but MerchantMenu cannot reach equipment slots,
        // so nothing appears and the mod looks broken. Say so, but only when there was
        // nothing teachable elsewhere — otherwise it is noise.
        if (result.groups.isEmpty() && result.refusals.isEmpty()) {
            for (EquipmentSlot slot : new EquipmentSlot[]{
                    EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack worn = sp.getItemBySlot(slot);
                if (worn.isEmpty()) continue;
                if (EnchantedItemAnalyzer.isTeachableForProfession(worn.getItem(), professionId)) {
                    result.refusals.add(TradeSchoolMessages.REFUSAL_WORN_ARMOR);
                    result.refusalDetail = "";
                    break;
                }
            }
        }

        // Viable offers claim the limit first; rejects only fill what is left over.
        int spare = Math.max(0, maxOffers - result.groups.size());
        if (result.rejected.size() > spare) {
            result.rejected.subList(spare, result.rejected.size()).clear();
        }
        return result;
    }

    /** Refusal marker meaning "reject, but say nothing". Compared by identity. */
    @Unique
    private static final String SILENT_REFUSAL = "tradeschool.refusal.silent";

    /**
     * A message key for why this villager will not learn from this item, or null if they
     * will. {@link #SILENT_REFUSAL} rejects without telling the player.
     *
     * Returning a reason rather than a boolean lets the caller explain the refusal; several
     * of these are invisible otherwise, which reads as the mod being broken. Untradable
     * treasure (Soul Speed, Swift Sneak, Wind Burst) is the most opaque of them.
     */
    @Unique
    private String tradeschool$refusalReason(ItemStack stack, String professionId,
                                             Villager villager, int villagerLevel) {
        if (EnchantedItemAnalyzer.isNetheriteForProfession(stack.getItem(), professionId)) {
            return TradeSchoolMessages.REFUSAL_NETHERITE;
        }
        // Not this profession's business at all — a carrot, a spawn egg, an emerald. Refuse
        // it, but silently: returning null here would mean "acceptable", which is how every
        // item in the player's pack ended up with a teach offer.
        if (!EnchantedItemAnalyzer.isTeachableForProfession(stack.getItem(), professionId)) {
            return SILENT_REFUSAL;
        }
        if (stack.getItem() == Items.SHIELD && villagerLevel < 3) {
            return TradeSchoolMessages.REFUSAL_SHIELD_TOO_JUNIOR;
        }
        if (ModEnchantments.isCurseProtected(stack)) {
            return TradeSchoolMessages.REFUSAL_COPYRIGHTED;
        }
        if (cx.gid.minecraft.tradeschool.trade.TradeFilter.alreadySells(villager.getOffers(), stack)) {
            return TradeSchoolMessages.REFUSAL_ALREADY_SELLS;
        }
        if (tradeschool$nothingToLearn(stack, professionId, villagerLevel)) {
            // Distinguish "never learnable" from "not yet" — the former is otherwise
            // baffling, since levelling the villager will never help.
            var enchs = ModEnchantments.effectiveEnchantments(stack);
            boolean allUnlearnable = !enchs.isEmpty() && enchs.keySet().stream()
                .noneMatch(cx.gid.minecraft.tradeschool.enchantment.EnchantmentProperties::isTradable);
            if (allUnlearnable) {
                // Name the offending enchantment: "cannot learn Swift Sneak" tells the
                // player something, where "that enchantment" leaves them guessing.
                var first = enchs.keySet().iterator().next();
                tradeschool$lastRefusalDetail =
                    tradeschool$enchantmentName(first, enchs.getLevel(first));
                return TradeSchoolMessages.REFUSAL_NEVER_LEARNABLE;
            }
            return TradeSchoolMessages.REFUSAL_TOO_JUNIOR;
        }
        // Already used this profession level's single lesson.
        VillagerKnowledgeData kd = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        boolean isBook = stack.getItem() == Items.ENCHANTED_BOOK;
        if (isBook ? kd.getKnowledgeAtLevel(villagerLevel) != null
                   : kd.getItemKnowledgeAtLevel(villagerLevel) != null) {
            return TradeSchoolMessages.REFUSAL_LESSON_USED;
        }
        return null;
    }

    /**
     * Signature for dedup: item type + sorted enchantments only (no damage/repair_cost).
     * Two items with the same type and enchantments collapse to one offer regardless of damage.
     */
    @Unique
    private String tradeschool$enchantSignature(ItemStack held) {
        StringBuilder sb = new StringBuilder();
        sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()));
        ItemEnchantments enchsRaw = held.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        final ItemEnchantments enchs = enchsRaw.isEmpty()
            ? held.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY) : enchsRaw;
        enchs.keySet().stream()
            .sorted(java.util.Comparator.comparing(h -> h.unwrapKey().map(r -> r.toString()).orElse("")))
            .forEach(e -> sb.append("|").append(e.unwrapKey().map(r -> r.toString()).orElse("?"))
                .append(":").append(enchs.getLevel(e)));
        return sb.toString();
    }

    /**
     * Builds one ephemeral teach offer for a given held item.
     * Cost = the held item (with enchantment predicate, matches any damage).
     * Result = the learned item (downtiered) if partial learn, or emeralds if full learn.
     */
    @Unique
    private MerchantOffer tradeschool$buildTeachOffer(Villager villager, ItemStack held,
                                                       String professionId, int professionLevel) {
        if (held.isEmpty()) return null;

        String label = getProfessionLabel(professionId);
        var analysisResult = EnchantedItemAnalyzer.analyzeItem(held, professionLevel, label);
        var knowledge = analysisResult.knowledge();

        // An enchanted book is nothing but its enchantment, so if every one was filtered
        // out — too advanced for this villager, or untradable — there is nothing left to
        // teach and the result would be a blank book. Gear is different: learning to make
        // a plain diamond pickaxe is still worth something even when its enchantments were
        // out of reach, so this only guards books.
        if (held.getItem() == Items.ENCHANTED_BOOK && knowledge.getEnchantments().isEmpty()) {
            return null;
        }

        // Build the result stack — what the player receives after the teach trade
        ItemStack resultStack;
        boolean fullLearn = EnchantedItemAnalyzer.isFullLearn(held, knowledge);
        if (fullLearn) {
            int sellPrice = ItemPricingCalculator.calculateSellingPrice(knowledge);
            int payment = Math.max(1, (sellPrice + tradeschool$config().fullLearnPaymentDivisor - 1)
                / tradeschool$config().fullLearnPaymentDivisor);
            resultStack = new ItemStack(Items.EMERALD, payment);
        } else {
            // Result built from groupFirst's knowledge. MerchantResultSlotMixin will overwrite
            // damage/repair_cost at trade time to match whichever copy was actually submitted.
            resultStack = knowledge.createItemStack();
        }

        // Build ItemCost with an exact enchantment predicate, so this offer accepts only
        // the item it was built for. Reading the wrong component here yields an empty
        // predicate, which matches *any* item of that type — that is how a book the player
        // never held could be consumed by an offer built for a different one.
        //
        // Which component carries the enchantments depends on the item, not the
        // profession: books use STORED_ENCHANTMENTS, gear uses ENCHANTMENTS.
        boolean isBook = held.getItem() == Items.ENCHANTED_BOOK;
        var enchComponent = isBook ? DataComponents.STORED_ENCHANTMENTS : DataComponents.ENCHANTMENTS;
        ItemEnchantments offerEnchs = held.getOrDefault(enchComponent, ItemEnchantments.EMPTY);
        ItemCost cost = new ItemCost(held.typeHolder(), 1,
            net.minecraft.core.component.DataComponentExactPredicate.expect(enchComponent, offerEnchs));

        Constants.debug("[TradeSchool] Teach offer: {} -> {} (fullLearn={})",
            EnchantedItemAnalyzer.itemDisplayName(held.getItem()),
            fullLearn ? resultStack.getCount() + " emeralds" : EnchantedItemAnalyzer.itemDisplayName(resultStack.getItem()),
            fullLearn);

        return new MerchantOffer(cost, resultStack, 1, 0, 0.0f);
    }

    // ── customServerAiStep — stare/preview ───────────────────────────────────

    @Inject(method = "customServerAiStep", at = @At("RETURN"))
    private void onCustomServerAiStep(ServerLevel level, CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;
        String professionId = villager.getVillagerData().profession().toString();
        if (!isTradeSchoolProfession(professionId)) return;

        int professionLevel = villager.getVillagerData().level();
        if (professionLevel != tradeschool$lastKnownLevel) {
            tradeschool$notifiedHeldItems.clear();
            tradeschool$lastKnownLevel = professionLevel;
        }

        sendProximityHintIfNeeded(villager, professionId);

        VillagerKnowledgeData kd = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        boolean alreadyLearned = kd.getItemKnowledgeAtLevel(professionLevel) != null;

        AABB box = villager.getBoundingBox().inflate(tradeschool$config().teachPreviewRadius);
        List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box, p -> true);

        Set<UUID> stillInterested = new HashSet<>();
        for (ServerPlayer player : nearby) {
            // Copyrighted goods held in hand get a one-off refusal. The trade UI offers no
            // hook for "player inserted an item matching no offer", so without this the
            // villager would sit mute while the player wonders why nothing happens.
            // Hands only: a cursed book buried in the hotbar is not being offered.
            ItemStack cursedInHand = tradeschool$findCurseProtectedInHand(player, professionId, villager);
            if (!cursedInHand.isEmpty()) {
                stillInterested.add(player.getUUID());
                int curseHash = System.identityHashCode(cursedInHand.getItem())
                    ^ ModEnchantments.effectiveEnchantments(cursedInHand).hashCode()
                    ^ "curse".hashCode();
                if (!tradeschool$alreadyTold(player, curseHash, level.getGameTime())) {
                    tradeschool$sendCurseRefusal(villager, player);
                }
                continue;
            }

            ItemStack previewItem = tradeschool$findFirstTeachableItem(player, professionId, villager);
            if (previewItem == null || previewItem.isEmpty()) continue;

            ItemEnchantments heldEnchs = ModEnchantments.effectiveEnchantments(previewItem);

            if (alreadyLearned) continue;

            stillInterested.add(player.getUUID());

            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
            villager.getLookControl().setLookAt(player, 30f, 30f);
            String label = getProfessionLabel(professionId);
            ItemStack showItem = tradeschool$buildPreviewItem(previewItem, professionId, professionLevel, label);
            villager.setItemSlot(EquipmentSlot.MAINHAND, showItem);
            villager.setDropChance(EquipmentSlot.MAINHAND, 0.0f);

            int heldHash = System.identityHashCode(previewItem.getItem()) ^ heldEnchs.hashCode();
            if (tradeschool$alreadyTold(player, heldHash, level.getGameTime())) continue;

            Component msg = tradeschool$buildPreviewMessage(player, villager, previewItem,
                professionId, label, kd, professionLevel);
            if (msg != null) {
                Constants.debug(msg.getString());
                tradeschool$indicateSpeaker(villager);
                if (tradeschool$feedback().chat) player.sendSystemMessage(msg);
                // The action bar sits where the player is already looking and fades on its
                // own, but it cannot be held long enough to read a full sentence — off by
                // default, since these belong in chat where they can be re-read.
                if (tradeschool$feedback().actionBar) player.sendSystemMessage(msg, true);
            }
        }

        // Forget only what has aged out. Dropping entries for absent players instead
        // would reset the reminder every time the villager wandered off.
        long now = level.getGameTime();
        tradeschool$notifiedHeldItems.values()
            .removeIf(entry -> now - entry[1] > TRADESCHOOL$REMIND_AFTER_TICKS);
        if (stillInterested.isEmpty()) {
            if (!villager.getMainHandItem().isEmpty()) {
                villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }
    }

    /**
     * A copyrighted item held in either hand that this villager would otherwise have been
     * interested in. Restricted to the hands because holding one out is the player's way
     * of offering it; carrying one in a backpack slot is not.
     */
    @Unique
    private ItemStack tradeschool$findCurseProtectedInHand(ServerPlayer sp, String professionId, Villager villager) {
        int level = villager.getVillagerData().level();
        for (ItemStack stack : List.of(sp.getMainHandItem(), sp.getOffhandItem())) {
            if (stack.isEmpty()) continue;
            if (!ModEnchantments.isCurseProtected(stack)) continue;
            if (EnchantedItemAnalyzer.isNetheriteForProfession(stack.getItem(), professionId)) continue;
            if (!EnchantedItemAnalyzer.isTeachableForProfession(stack.getItem(), professionId)) continue;
            if (stack.getItem() == Items.SHIELD && level < 3) continue;
            return stack;
        }
        return ItemStack.EMPTY;
    }

    @Unique
    private void tradeschool$sendCurseRefusal(Villager villager, ServerPlayer player) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.playSound(null, villager.blockPosition(),
            net.minecraft.sounds.SoundEvents.VILLAGER_NO,
            net.minecraft.sounds.SoundSource.NEUTRAL, 1.0f, 1.0f);
        String text = "Villagers can't learn from copyrighted books and items.";
        Constants.debug(text);
        player.sendSystemMessage(Component.literal(text));
    }

    /**
     * True if nothing would survive analysis for this villager — an enchanted book whose
     * every enchantment is out of reach at their level. Such a book would otherwise
     * produce a teach offer yielding a blank, enchantment-less book.
     */
    @Unique
    private boolean tradeschool$nothingToLearn(ItemStack stack, String professionId, int professionLevel) {
        if (stack.getItem() != Items.ENCHANTED_BOOK) return false;
        String label = getProfessionLabel(professionId);
        var result = EnchantedItemAnalyzer.analyzeItem(stack, professionLevel, label);
        return result.knowledge().getEnchantments().isEmpty();
    }

    /**
     * The item the villager should look at and preview.
     *
     * Mainhand only. The teach scan reaches the whole inventory, but the villager should
     * visibly react to what the player is holding out — miming an item buried in their
     * backpack would be meaningless.
     */
    @Unique
    private ItemStack tradeschool$findFirstTeachableItem(ServerPlayer sp, String professionId, Villager villager) {
        int level = villager.getVillagerData().level();
        ItemStack held = sp.getMainHandItem();
        if (held.isEmpty()) return ItemStack.EMPTY;
        if (tradeschool$refusalReason(held, professionId, villager, level) != null) return ItemStack.EMPTY;
        return held;
    }

    @Unique
    private ItemStack tradeschool$buildPreviewItem(ItemStack held, String professionId, int professionLevel, String label) {
        if (EnchantedItemAnalyzer.isNetheriteForProfession(held.getItem(), professionId)) {
            return held.copyWithCount(1);
        }
        var result = EnchantedItemAnalyzer.analyzeItem(held, professionLevel, label);
        return result.knowledge().createItemStack();
    }

    @Unique
    private Component tradeschool$buildPreviewMessage(ServerPlayer sp, Villager villager, ItemStack held,
                                                    String professionId, String label,
                                                    VillagerKnowledgeData knowledge, int professionLevel) {
        if (EnchantedItemAnalyzer.isNetheriteForProfession(held.getItem(), professionId)) {
            return TradeSchoolMessages.of(sp, TradeSchoolMessages.PREVIEW_NETHERITE, label);
        }
        // Mid-sentence, so lower-case: "You can teach this Librarian…".
        String who = cx.gid.minecraft.tradeschool.trade.Describe.villagerLower(
            villager, getProfessionLabel(professionId));
        var result = EnchantedItemAnalyzer.analyzeItem(held, professionLevel, label);

        boolean fullLearn = EnchantedItemAnalyzer.isFullLearn(held, result.knowledge());
        String learnDesc = result.learnedDescription();

        // A full learn produces the same thing the player handed over, so naming it twice
        // reads as a mistake — say "copies of your X" instead. A partial learn genuinely
        // has two different items to name, so both are spelled out with enchantments.
        return fullLearn
            ? TradeSchoolMessages.of(sp, TradeSchoolMessages.PREVIEW_FULL_LEARN,
                who, tradeschool$describe(held))
            : TradeSchoolMessages.of(sp, TradeSchoolMessages.PREVIEW_PARTIAL_LEARN,
                who, learnDesc, tradeschool$shortNoun(held));
    }

    /**
     * An item named the way a player would say it: "Diamond Sword (Sharpness V, Mending)",
     * or just the item name when it carries no enchantments.
     */
    @Unique
    private String tradeschool$describe(ItemStack stack) {
        return cx.gid.minecraft.tradeschool.trade.Describe.item(stack);
    }

    /**
     * Marks which villager is talking.
     *
     * Chat gives no indication of who spoke, and in a village there may be several
     * candidates within a few blocks. A puff of particles and a mumble from the right
     * villager answers "which one?" without needing a name tag.
     */
    @Unique
    private void tradeschool$indicateSpeaker(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
            villager.getX(), villager.getY() + villager.getBbHeight() + 0.4, villager.getZ(),
            6, 0.25, 0.15, 0.25, 0.0);
        serverLevel.playSound(null, villager.blockPosition(),
            net.minecraft.sounds.SoundEvents.VILLAGER_AMBIENT,
            net.minecraft.sounds.SoundSource.NEUTRAL, 0.7f, 1.0f);
    }

    /**
     * How to address a villager: "Dave the Librarian" when they have been named, "The
     * librarian" otherwise.
     *
     * A named villager is one someone cared enough to name, and calling them "this
     * villager" after that reads as though the mod has not noticed.
     */
    @Unique
    private String tradeschool$villagerName(Villager villager, String professionId) {
        return cx.gid.minecraft.tradeschool.trade.Describe.villager(
            villager, getProfessionLabel(professionId));
    }

    /**
     * How to refer to the item the player would hand over: "book", "one", or the item's
     * own name. "Trade your Enchanted Book with 'Mending'" reads as though the
     * enchantment matters twice; "trade your book" is what a person would say.
     */
    @Unique
    private String tradeschool$shortNoun(ItemStack stack) {
        return cx.gid.minecraft.tradeschool.trade.Describe.shortNoun(stack);
    }

    /** "Sharpness V", or bare "Mending" for enchantments that only have one level. */
    @Unique
    private String tradeschool$enchantmentName(Holder<Enchantment> ench, int level) {
        return cx.gid.minecraft.tradeschool.trade.Describe.enchantment(ench, level);
    }

    // ── messaging ─────────────────────────────────────────────────────────────

    @Unique
    private void sendProximityHintIfNeeded(Villager villager, String professionId) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        String professionType = getProfessionType(professionId);
        String hintKey = getProfessionHint(professionType);
        if (hintKey == null) return;
        serverLevel.getPlayers(player -> {
            double hintR = tradeschool$config().hintRadius;
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= hintR * hintR) {
                // Only volunteer the hint to a player carrying something this villager could
                // actually learn. Walking past a village otherwise burns the one-shot hint on
                // players with nothing to teach, who then never see it when it would help.
                ItemStack teachable = tradeschool$findFirstTeachableItem(sp, professionId, villager);
                if (teachable == null || teachable.isEmpty()) return false;
                if (ModEnchantments.isCurseProtected(teachable)) return false;

                PlayerHintAccessor hints = (PlayerHintAccessor) sp;
                if (!hints.tradeschool$hasSeenHint(professionType)) {
                    hints.tradeschool$markHintSeen(professionType);
                    spawnAttentionParticles(villager, serverLevel, true);
                    var hint = TradeSchoolMessages.of(sp, hintKey,
                        tradeschool$villagerName(villager, professionId));
                    Constants.debug(hint.getString());
                    if (tradeschool$feedback().chat) sp.sendSystemMessage(hint);
                    tradeschool$indicateSpeaker(villager);
                }
            }
            return false;
        });
    }

    @Unique
    private void spawnAttentionParticles(Villager villager, ServerLevel level, boolean positive) {
        var particle = positive ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.ANGRY_VILLAGER;
        double cx = villager.getX(), cy = villager.getY() + villager.getBbHeight() + 0.3, cz = villager.getZ();
        for (int i = 0; i < 12; i++) {
            double ox = (villager.getRandom().nextDouble() - 0.5) * 0.6;
            double oy = villager.getRandom().nextDouble() * 0.8;
            double oz = (villager.getRandom().nextDouble() - 0.5) * 0.6;
            level.sendParticles(particle, cx + ox, cy + oy, cz + oz, 1, 0, 0.05, 0, 0);
        }
    }

    // ── string helpers ────────────────────────────────────────────────────────

    @Unique
    private String getProfessionLabel(String professionId) {
        if (professionId.contains("librarian"))   return "librarian";
        if (professionId.contains("weaponsmith")) return "weaponsmith";
        if (professionId.contains("toolsmith"))   return "toolsmith";
        if (professionId.contains("armorer"))     return "armorer";
        if (professionId.contains("fletcher"))    return "fletcher";
        return "villager";
    }

    @Unique
    private String getProfessionType(String professionId) {
        if (professionId.contains("librarian"))   return "librarian";
        if (professionId.contains("weaponsmith")) return "weaponsmith";
        if (professionId.contains("toolsmith"))   return "toolsmith";
        if (professionId.contains("armorer"))     return "armorer";
        if (professionId.contains("fletcher"))    return "fletcher";
        return null;
    }

    @Unique
    private String getProfessionHint(String professionType) {
        if (professionType == null) return null;
        return switch (professionType) {
            case "librarian"   -> TradeSchoolMessages.HINT_LIBRARIAN;
            case "weaponsmith" -> TradeSchoolMessages.HINT_WEAPONSMITH;
            case "toolsmith"   -> TradeSchoolMessages.HINT_TOOLSMITH;
            case "armorer"     -> TradeSchoolMessages.HINT_ARMORER;
            case "fletcher"    -> TradeSchoolMessages.HINT_FLETCHER;
            default -> null;
        };
    }

    @Unique
    private boolean isTradeSchoolProfession(String profession) {
        return profession.contains("librarian") ||
               profession.contains("weaponsmith") ||
               profession.contains("toolsmith") ||
               profession.contains("armorer") ||
               profession.contains("fletcher");
    }
}
