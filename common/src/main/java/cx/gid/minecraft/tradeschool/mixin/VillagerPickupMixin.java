package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
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
 * When a player right-clicks a tradeschool villager, all teachable items across
 * mainhand, offhand, armor slots, and hotbar (up to 6, excluding netherite) get
 * ephemeral teach offers inserted.
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

    @Unique private final Map<UUID, Integer> tradeschool$notifiedHeldItems = new HashMap<>();
    @Unique private int tradeschool$lastKnownLevel = 0;

    // Maximum number of simultaneous teach offers.
    private static final int MAX_TEACH_OFFERS = 6;

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
        if (knowledge.getItemKnowledgeAtLevel(professionLevel) != null) return;

        // Scan all qualifying slots, grouped by dedup signature (enchantments only, not damage).
        // Returns: Map from sig → (firstStack, allSlots, allStacks)
        ScanResult scan = tradeschool$scanAndGroup(sp, professionId, villager);
        if (scan.groups.isEmpty()) return;

        // Debug: log all found groups and their slot copies
        for (OfferGroup g : scan.groups) {
            for (int gi = 0; gi < g.slots.size(); gi++) {
                ItemStack s = g.stacks.get(gi);
                ItemEnchantments ge = s.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
                if (ge.isEmpty()) ge = s.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                Constants.LOGGER.info("[TradeSchool] Teachable slot {}: item={} enchants={} damage={} repair_cost={}",
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
    }

    /**
     * Scans player slots in order: mainhand(36+hotbarSelected), offhand(40),
     * armor (36=head, 37=chest, 38=legs, 39=feet), hotbar 0-8.
     *
     * Groups by enchantment-only signature (dedup identical-enchant swords with different damage).
     * Records all slot indices per group so notifyTrade can identify which slot was consumed.
     *
     * Slot numbers follow Inventory internal layout:
     *   0-8 = hotbar, 9-35 = main inventory, 36-39 = armor (head→feet), 40 = offhand.
     */
    @Unique
    private ScanResult tradeschool$scanAndGroup(ServerPlayer sp, String professionId, Villager villager) {
        int villagerLevel = villager.getVillagerData().level();
        Inventory inv = sp.getInventory();

        // Build ordered (slotIndex, stack) pairs.
        // Inventory slot layout: 0-8 = hotbar, 36-39 = armor (head→feet), 40 = offhand.
        // We get selected hotbar slot via reflection on the Inventory field if needed,
        // but can derive it as the hotbar slot whose item == sp.getMainHandItem().
        int selectedHotbar = 0;
        for (int i = 0; i <= 8; i++) {
            if (inv.getItem(i) == sp.getMainHandItem()) { selectedHotbar = i; break; }
        }

        List<int[]> slotOrder = new ArrayList<>();
        slotOrder.add(new int[]{selectedHotbar}); // mainhand
        slotOrder.add(new int[]{40});              // offhand
        slotOrder.add(new int[]{36});              // helmet
        slotOrder.add(new int[]{37});              // chestplate
        slotOrder.add(new int[]{38});              // leggings
        slotOrder.add(new int[]{39});              // boots
        for (int i = 0; i <= 8; i++) {
            if (i != selectedHotbar) slotOrder.add(new int[]{i});
        }

        ScanResult result = new ScanResult();
        Map<String, OfferGroup> seenSigs = new HashMap<>();
        int totalItems = 0;

        for (int[] si : slotOrder) {
            if (totalItems >= MAX_TEACH_OFFERS) break;
            int slotIdx = si[0];
            ItemStack stack = inv.getItem(slotIdx);
            if (stack.isEmpty()) continue;
            if (EnchantedItemAnalyzer.isNetheriteForProfession(stack.getItem(), professionId)) continue;
            if (!EnchantedItemAnalyzer.isTeachableForProfession(stack.getItem(), professionId)) continue;
            if (stack.getItem() == Items.SHIELD && villagerLevel < 3) continue;

            // Curse of Copyright check
            ItemEnchantments enchs = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            boolean cursed = false;
            for (Holder<Enchantment> e : enchs.keySet()) {
                if (ModEnchantments.isCurseOfCopyright(e)) { cursed = true; break; }
            }
            if (cursed) continue;

            String sig = tradeschool$enchantSignature(stack);
            OfferGroup group = seenSigs.get(sig);
            if (group == null) {
                // New offer group
                group = new OfferGroup(sig, slotIdx, stack);
                seenSigs.put(sig, group);
                result.groups.add(group);
                totalItems++;
            } else {
                // Same enchantments, different damage — add to existing group
                group.slots.add(slotIdx);
                group.stacks.add(stack.copy());
            }
        }
        return result;
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

        // Build the result stack — what the player receives after the teach trade
        ItemStack resultStack;
        boolean fullLearn = EnchantedItemAnalyzer.isFullLearn(held, knowledge);
        if (fullLearn) {
            int sellPrice = ItemPricingCalculator.calculateSellingPrice(knowledge);
            int payment = Math.max(1, (sellPrice + 1) / 2);
            resultStack = new ItemStack(Items.EMERALD, payment);
        } else {
            // Result built from groupFirst's knowledge. MerchantResultSlotMixin will overwrite
            // damage/repair_cost at trade time to match whichever copy was actually submitted.
            resultStack = knowledge.createItemStack();
        }

        // Build ItemCost with enchantment predicate so the UI shows the enchanted item.
        // The predicate matches based on enchantments — it will accept any damage value of the item.
        ItemCost cost;
        if (professionId.contains("librarian")) {
            ItemEnchantments storedEnchs = held.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            net.minecraft.core.component.DataComponentExactPredicate pred = storedEnchs.isEmpty()
                ? net.minecraft.core.component.DataComponentExactPredicate.EMPTY
                : net.minecraft.core.component.DataComponentExactPredicate.expect(DataComponents.STORED_ENCHANTMENTS, storedEnchs);
            cost = new ItemCost(held.typeHolder(), 1, pred);
        } else {
            ItemEnchantments enchs = held.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            net.minecraft.core.component.DataComponentExactPredicate pred = enchs.isEmpty()
                ? net.minecraft.core.component.DataComponentExactPredicate.EMPTY
                : net.minecraft.core.component.DataComponentExactPredicate.expect(DataComponents.ENCHANTMENTS, enchs);
            cost = new ItemCost(held.typeHolder(), 1, pred);
        }

        Constants.LOGGER.info("[TradeSchool] Teach offer: {} -> {} (fullLearn={})",
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

        AABB box = villager.getBoundingBox().inflate(5.0);
        List<ServerPlayer> nearby = level.getEntitiesOfClass(ServerPlayer.class, box, p -> true);

        Set<UUID> stillInterested = new HashSet<>();
        for (ServerPlayer player : nearby) {
            ItemStack previewItem = tradeschool$findFirstTeachableItem(player, professionId, villager);
            if (previewItem == null || previewItem.isEmpty()) continue;

            ItemEnchantments heldEnchs = previewItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            boolean cursed = false;
            for (Holder<Enchantment> e : heldEnchs.keySet()) {
                if (ModEnchantments.isCurseOfCopyright(e)) { cursed = true; break; }
            }
            if (cursed) {
                int curseHash = System.identityHashCode(previewItem.getItem()) ^ heldEnchs.hashCode() ^ "curse".hashCode();
                if (tradeschool$notifiedHeldItems.getOrDefault(player.getUUID(), 0) != curseHash) {
                    tradeschool$notifiedHeldItems.put(player.getUUID(), curseHash);
                    sendCurseRejectionMessage(villager);
                }
                continue;
            }

            if (alreadyLearned) continue;

            stillInterested.add(player.getUUID());

            villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
            villager.getLookControl().setLookAt(player, 30f, 30f);
            String label = getProfessionLabel(professionId);
            ItemStack showItem = tradeschool$buildPreviewItem(previewItem, professionId, professionLevel, label);
            villager.setItemSlot(EquipmentSlot.MAINHAND, showItem);
            villager.setDropChance(EquipmentSlot.MAINHAND, 0.0f);

            int heldHash = System.identityHashCode(previewItem.getItem()) ^ heldEnchs.hashCode();
            if (tradeschool$notifiedHeldItems.getOrDefault(player.getUUID(), 0) == heldHash) continue;
            tradeschool$notifiedHeldItems.put(player.getUUID(), heldHash);

            String msg = tradeschool$buildPreviewMessage(villager, previewItem, professionId, label, kd, professionLevel);
            if (msg != null) {
                Constants.LOGGER.info(msg);
                player.sendSystemMessage(Component.literal(msg));
            }
        }

        tradeschool$notifiedHeldItems.keySet().retainAll(stillInterested);
        if (stillInterested.isEmpty()) {
            if (!villager.getMainHandItem().isEmpty()) {
                villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }
    }

    @Unique
    private ItemStack tradeschool$findFirstTeachableItem(ServerPlayer sp, String professionId, Villager villager) {
        int level = villager.getVillagerData().level();
        Inventory inv = sp.getInventory();
        List<ItemStack> scanOrder = new ArrayList<>();
        scanOrder.add(sp.getMainHandItem());
        scanOrder.add(sp.getOffhandItem());
        scanOrder.add(sp.getItemBySlot(EquipmentSlot.HEAD));
        scanOrder.add(sp.getItemBySlot(EquipmentSlot.CHEST));
        scanOrder.add(sp.getItemBySlot(EquipmentSlot.LEGS));
        scanOrder.add(sp.getItemBySlot(EquipmentSlot.FEET));
        for (int i = 1; i <= 8; i++) scanOrder.add(inv.getItem(i));

        for (ItemStack stack : scanOrder) {
            if (stack.isEmpty()) continue;
            if (EnchantedItemAnalyzer.isNetheriteForProfession(stack.getItem(), professionId)) continue;
            if (!EnchantedItemAnalyzer.isTeachableForProfession(stack.getItem(), professionId)) continue;
            if (stack.getItem() == Items.SHIELD && level < 3) continue;
            return stack;
        }
        return ItemStack.EMPTY;
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
    private String tradeschool$buildPreviewMessage(Villager villager, ItemStack held, String professionId,
                                                    String label, VillagerKnowledgeData knowledge, int professionLevel) {
        if (EnchantedItemAnalyzer.isNetheriteForProfession(held.getItem(), professionId)) {
            return "The " + label + " cannot learn how to craft Netherite as it's too rare.";
        }
        var result = EnchantedItemAnalyzer.analyzeItem(held, professionLevel, label);
        int sellPrice = ItemPricingCalculator.calculateSellingPrice(result.knowledge());
        int payment = Math.max(1, (sellPrice + 1) / 2);

        boolean fullLearn = EnchantedItemAnalyzer.isFullLearn(held, result.knowledge());
        String learnDesc = result.learnMessage()
            .replaceFirst("^The " + label + " has learned how to make a?n? ", "")
            .replaceFirst(";.*$", "");
        String heldName = EnchantedItemAnalyzer.itemDisplayName(held.getItem());

        if (fullLearn) {
            return "The " + label + " could learn how to make " + learnDesc
                + " — right-click to trade your " + heldName + " for "
                + payment + " emerald" + (payment == 1 ? "" : "s") + ".";
        } else {
            return "The " + label + " could learn how to make " + learnDesc
                + " — right-click to trade your " + heldName
                + " (the item will be returned downgraded).";
        }
    }

    // ── messaging ─────────────────────────────────────────────────────────────

    @Unique
    private void sendCurseRejectionMessage(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        spawnAttentionParticles(villager, serverLevel, false);
        String text = "The villager refuses to study copyrighted material!";
        Constants.LOGGER.info(text);
        Component msg = Component.literal(text);
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= 16 * 16)
                sp.sendSystemMessage(msg);
            return false;
        });
    }

    @Unique
    private void sendProximityHintIfNeeded(Villager villager, String professionId) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        String professionType = getProfessionType(professionId);
        String hintText = getProfessionHint(professionType);
        if (hintText == null) return;
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= 8 * 8) {
                PlayerHintAccessor hints = (PlayerHintAccessor) sp;
                if (!hints.tradeschool$hasSeenHint(professionType)) {
                    hints.tradeschool$markHintSeen(professionType);
                    spawnAttentionParticles(villager, serverLevel, true);
                    Constants.LOGGER.info(hintText);
                    sp.sendSystemMessage(Component.literal(hintText));
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
            case "librarian"   -> "This villager is eager to learn. Right-click holding an enchanted book and they will study it and begin selling that enchantment.";
            case "weaponsmith" -> "This villager is eager to learn. Right-click holding an enchanted sword or axe and they will learn to craft and sell it.";
            case "toolsmith"   -> "This villager is eager to learn. Right-click holding an enchanted pickaxe, shovel, or hoe and they will learn to craft and sell it.";
            case "armorer"     -> "This villager is eager to learn. Right-click holding a piece of armor or shield and they will learn to craft and sell it.";
            case "fletcher"    -> "This villager is eager to learn. Right-click holding an enchanted bow, crossbow, or tipped arrow and they will learn to sell it.";
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
