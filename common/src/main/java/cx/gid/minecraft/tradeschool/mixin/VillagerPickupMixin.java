package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import cx.gid.minecraft.tradeschool.data.PendingPickup;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import cx.gid.minecraft.tradeschool.trade.EnchantedItemAnalyzer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Mixin to enable villagers to pick up enchanted items and learn from them.
 * Used for all TradeSchool professions: Librarian, Weaponsmith, Toolsmith, Armourer, Fletcher.
 */
@Mixin(Villager.class)
public abstract class VillagerPickupMixin {

    @Unique
    private int tradeschool$pickupCheckCooldown = 0;

    @Unique
    private final Set<UUID> tradeschool$rejectedItems = new HashSet<>();

    @Unique
    private int tradeschool$lastKnownLevel = 0;

    @Unique
    private final java.util.Map<UUID, PendingPickup> tradeschool$pendingPickups = new java.util.HashMap<>();

    /**
     * Check for nearby teachable items every 20 ticks (1 second).
     * Using intermediary name for tick method: method_5773
     */
    @Inject(method = "method_5773", at = @At("HEAD"), remap = false)
    private void onTick(CallbackInfo ci) {
        Villager villager = (Villager) (Object) this;

        // Only run on server
        if (villager.level().isClientSide()) {
            return;
        }

        // Check if this is a Trade School profession
        String professionId = villager.getVillagerData().profession().toString();
        if (!isTradeSchoolProfession(professionId)) {
            return;
        }

        // Check if villager leveled up - clear rejected items when they do
        int professionLevel = villager.getVillagerData().level();
        if (professionLevel != tradeschool$lastKnownLevel) {
            tradeschool$rejectedItems.clear();
            tradeschool$pendingPickups.clear();
            tradeschool$lastKnownLevel = professionLevel;
            Constants.LOGGER.debug("Villager leveled up to {}, cleared rejected items cache", professionLevel);
        }

        // Process pending pickups every tick
        processPendingPickups(villager);

        // Only check every 20 ticks (1 second) to reduce performance impact
        if (tradeschool$pickupCheckCooldown > 0) {
            tradeschool$pickupCheckCooldown--;
            return;
        }
        tradeschool$pickupCheckCooldown = 20;

        // Check if villager already learned at current level
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        boolean alreadyLearned = professionId.contains("librarian")
                ? knowledge.getKnowledgeAtLevel(professionLevel) != null
                : knowledge.getItemKnowledgeAtLevel(professionLevel) != null;

        if (alreadyLearned) {
            return; // Already learned at this level
        }

        // Search for nearby dropped items within 2 blocks
        AABB searchBox = villager.getBoundingBox().inflate(2.0);
        List<ItemEntity> nearbyItems = villager.level().getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                item -> !item.isRemoved() && item.isAlive()
        );

        // Process the first valid teachable item found
        for (ItemEntity itemEntity : nearbyItems) {
            // Skip items we've already rejected
            if (tradeschool$rejectedItems.contains(itemEntity.getUUID())) {
                continue;
            }

            // Skip items that are already pending pickup
            if (tradeschool$pendingPickups.containsKey(itemEntity.getUUID())) {
                continue;
            }

            if (tryValidateAndQueueItem(villager, itemEntity)) {
                break; // Successfully validated and queued, stop checking
            }
        }
    }

    /**
     * Processes pending pickups, ticking down timers and executing pickups when ready.
     */
    @Unique
    private void processPendingPickups(Villager villager) {
        if (tradeschool$pendingPickups.isEmpty()) {
            return;
        }

        // Use iterator to safely remove items while iterating
        var iterator = tradeschool$pendingPickups.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            PendingPickup pending = entry.getValue();

            // Check if item entity is still valid
            if (pending.itemEntity.isRemoved() || !pending.itemEntity.isAlive()) {
                iterator.remove();
                continue;
            }

            // Tick down
            pending.ticksRemaining--;

            if (pending.ticksRemaining <= 0) {
                // Time to pick up
                executePickup(villager, pending);
                iterator.remove();
            }
        }
    }

    /**
     * Validates an item and queues it for delayed pickup if valid.
     * Returns true if the item was queued, false otherwise.
     */
    @Unique
    private boolean tryValidateAndQueueItem(Villager villager, ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        String professionId = villager.getVillagerData().profession().toString();

        // Check if item is appropriate for teaching
        boolean valid = false;

        if (professionId.contains("librarian") && stack.getItem() == Items.ENCHANTED_BOOK) {
            valid = validateEnchantedBook(villager, itemEntity, stack);
        } else if (EnchantedItemAnalyzer.isItemForProfession(stack.getItem(), professionId)) {
            if (EnchantedItemAnalyzer.canTeachAtLevel(stack, villager.getVillagerData().level())) {
                valid = validateEnchantedItem(villager, itemEntity, stack);
            } else {
                // Can't teach at this level - add to rejected list
                tradeschool$rejectedItems.add(itemEntity.getUUID());
            }
        } else {
            // Not appropriate for this profession - add to rejected list
            tradeschool$rejectedItems.add(itemEntity.getUUID());
        }

        return valid;
    }

    /**
     * Executes the actual pickup after the delay has elapsed.
     */
    @Unique
    private void executePickup(Villager villager, PendingPickup pending) {
        // Consume the item
        pending.itemEntity.discard();

        // Teach the villager
        if (pending.isBook) {
            teachEnchantedBookNow(villager, pending.enchantment, pending.enchantmentLevel);
        } else {
            teachEnchantedItemNow(villager, pending.itemStack);
        }

        // Drop XP orbs
        if (villager.level() instanceof ServerLevel serverLevel) {
            int xpAmount = calculateXPReward(villager.getVillagerData().level());
            ExperienceOrb.award(serverLevel, villager.position(), xpAmount);
        }

        // Visual and audio feedback
        playTeachingEffects(villager);

        // Grant advancement to nearby players
        grantTeachingAdvancement(villager);

        // Refresh villager trades
        if (villager.level() instanceof ServerLevel serverLevel) {
            ((VillagerTradesAccessor) villager).tradeschool$updateTrades(serverLevel);
        }
    }

    /**
     * Validates an enchanted book and queues it for pickup if valid.
     */
    @Unique
    private boolean validateEnchantedBook(Villager villager, ItemEntity itemEntity, ItemStack book) {
        // Extract enchantments from the book
        ItemEnchantments enchantments = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS,
                ItemEnchantments.EMPTY);
        if (enchantments.isEmpty()) {
            enchantments = book.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        }

        if (enchantments.isEmpty()) {
            return false;
        }

        // Check for Curse of Copyright
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (ModEnchantments.isCurseOfCopyright(enchantment)) {
                Constants.LOGGER.info("Villager {} refused to learn copyrighted enchantment", villager.getUUID());
                sendCurseRejectionMessage(villager);
                return false;
            }
        }

        // Get the first enchantment
        Holder<Enchantment> learnedEnchantment = null;
        int bookEnchantmentLevel = 1;
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            learnedEnchantment = enchantment;
            bookEnchantmentLevel = enchantments.getLevel(enchantment);
            break;
        }

        if (learnedEnchantment == null) {
            return false;
        }

        // Check if villager already has a trade for this exact enchanted book
        if (villagerAlreadyTradesIdenticalBook(villager, learnedEnchantment, bookEnchantmentLevel)) {
            Constants.LOGGER.info("Villager {} already trades identical book for {}, skipping",
                    villager.getUUID(),
                    learnedEnchantment.unwrapKey().map(key -> key.toString()).orElse("unknown"));
            return false;
        }

        // Queue the item for delayed pickup
        int professionLevel = villager.getVillagerData().level();
        int cappedLevel = Math.min(bookEnchantmentLevel, professionLevel);
        tradeschool$pendingPickups.put(itemEntity.getUUID(),
                new PendingPickup(itemEntity, book, learnedEnchantment, cappedLevel, 30));

        Constants.LOGGER.debug("Villager {} queued enchanted book for pickup: {} level {}",
                villager.getUUID(),
                learnedEnchantment.unwrapKey().map(key -> key.toString()).orElse("unknown"),
                cappedLevel);

        return true;
    }

    /**
     * Actually teaches a villager from an enchanted book after validation.
     */
    @Unique
    private void teachEnchantedBookNow(Villager villager, Holder<Enchantment> enchantment, int enchantmentLevel) {
        int professionLevel = villager.getVillagerData().level();

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        knowledge.teachEnchantment(professionLevel, enchantment, enchantmentLevel);
        VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);

        Constants.LOGGER.info("Villager {} learned enchantment {} level {} via pickup",
                villager.getUUID(),
                enchantment.unwrapKey().map(key -> key.toString()).orElse("unknown"),
                enchantmentLevel);
    }

    /**
     * Validates an enchanted item and queues it for pickup if valid.
     */
    @Unique
    private boolean validateEnchantedItem(Villager villager, ItemEntity itemEntity, ItemStack item) {
        int professionLevel = villager.getVillagerData().level();
        String professionId = villager.getVillagerData().profession().toString();

        // Validate item is appropriate for profession
        if (!EnchantedItemAnalyzer.isItemForProfession(item.getItem(), professionId)) {
            return false;
        }

        // Validate material tier and enchantment requirements
        if (!EnchantedItemAnalyzer.canTeachAtLevel(item, professionLevel)) {
            return false;
        }

        // Check for Curse of Copyright on the item
        ItemEnchantments enchantments = item.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (ModEnchantments.isCurseOfCopyright(enchantment)) {
                Constants.LOGGER.info("Villager {} refused to learn copyrighted item {}", villager.getUUID(), item.getItem());
                sendCurseRejectionMessage(villager);
                return false;
            }
        }

        // Check if villager already has a trade for this exact item (with same enchantments/properties)
        if (villagerAlreadyTradesIdenticalItem(villager, item)) {
            Constants.LOGGER.info("Villager {} already trades identical item {}, skipping",
                    villager.getUUID(),
                    item.getItem());
            return false;
        }

        // If villager has a trade for a basic version of this item, check if this one is enhanced
        if (villagerTradesBasicVersionOfItem(villager, item)) {
            // This is fine - the enhanced version will replace the basic trade
            Constants.LOGGER.info("Villager {} has basic trade for {}, will replace with enhanced version",
                    villager.getUUID(),
                    item.getItem());
        }

        // Queue the item for delayed pickup
        tradeschool$pendingPickups.put(itemEntity.getUUID(),
                new PendingPickup(itemEntity, item, 30));

        Constants.LOGGER.debug("Villager {} queued item for pickup: {}",
                villager.getUUID(),
                item.getItem());

        return true;
    }

    /**
     * Actually teaches a villager from an enchanted item after validation.
     */
    @Unique
    private void teachEnchantedItemNow(Villager villager, ItemStack item) {
        int professionLevel = villager.getVillagerData().level();

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Analyze item and cap enchantment levels by villager level
        ItemKnowledge itemKnowledge = EnchantedItemAnalyzer.analyzeItem(item, professionLevel);

        knowledge.teachItem(professionLevel, itemKnowledge);
        VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);

        Constants.LOGGER.info("Villager {} learned item {} at level {} with {} enchantments via pickup",
                villager.getUUID(),
                item.getItem(),
                professionLevel,
                itemKnowledge.getEnchantments().size());
    }

    /**
     * Checks if the villager already has a trade for an identical enchanted book.
     */
    @Unique
    private boolean villagerAlreadyTradesIdenticalBook(Villager villager, Holder<Enchantment> enchantment, int level) {
        for (net.minecraft.world.item.trading.MerchantOffer offer : villager.getOffers()) {
            ItemStack offered = offer.getResult();

            // Check if it's an enchanted book
            if (offered.getItem() != Items.ENCHANTED_BOOK) {
                continue;
            }

            // Check stored enchantments
            ItemEnchantments offeredEnch = offered.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);

            // Check if this book has the same enchantment at the same level
            if (offeredEnch.getLevel(enchantment) == level) {
                return true; // Villager already trades this exact book
            }
        }

        return false;
    }

    /**
     * Checks if the villager already has a trade for an identical item (same enchantments, trim, etc.).
     */
    @Unique
    private boolean villagerAlreadyTradesIdenticalItem(Villager villager, ItemStack newItem) {
        for (net.minecraft.world.item.trading.MerchantOffer offer : villager.getOffers()) {
            ItemStack offered = offer.getResult();

            // Check if base item matches
            if (offered.getItem() != newItem.getItem()) {
                continue;
            }

            // Check if enchantments match
            ItemEnchantments offeredEnch = offered.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            ItemEnchantments newEnch = newItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

            if (!enchantsMatch(offeredEnch, newEnch)) {
                continue;
            }

            // Check if trim matches (if applicable)
            boolean offeredHasTrim = offered.has(DataComponents.TRIM);
            boolean newHasTrim = newItem.has(DataComponents.TRIM);

            if (offeredHasTrim != newHasTrim) {
                continue;
            }

            if (offeredHasTrim && !offered.get(DataComponents.TRIM).equals(newItem.get(DataComponents.TRIM))) {
                continue;
            }

            // Items are identical
            return true;
        }

        return false;
    }

    /**
     * Checks if the villager trades a basic (unenchanted, no trim) version of this item.
     */
    @Unique
    private boolean villagerTradesBasicVersionOfItem(Villager villager, ItemStack newItem) {
        // Only consider this if the new item is enhanced (has enchantments or trim)
        ItemEnchantments newEnch = newItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        boolean hasEnchantments = !newEnch.isEmpty();
        boolean hasTrim = newItem.has(DataComponents.TRIM);

        if (!hasEnchantments && !hasTrim) {
            return false; // New item is also basic, not an upgrade
        }

        // Check if villager has a basic version of this item
        for (net.minecraft.world.item.trading.MerchantOffer offer : villager.getOffers()) {
            ItemStack offered = offer.getResult();

            // Check if base item matches
            if (offered.getItem() != newItem.getItem()) {
                continue;
            }

            // Check if offered item is basic (no enchantments, no trim)
            ItemEnchantments offeredEnch = offered.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            boolean offeredBasic = offeredEnch.isEmpty() && !offered.has(DataComponents.TRIM);

            if (offeredBasic) {
                return true; // Villager has a basic version, new item can replace it
            }
        }

        return false;
    }

    /**
     * Helper to check if two enchantment sets match.
     */
    @Unique
    private boolean enchantsMatch(ItemEnchantments e1, ItemEnchantments e2) {
        if (e1.isEmpty() && e2.isEmpty()) {
            return true;
        }
        if (e1.size() != e2.size()) {
            return false;
        }

        for (Holder<Enchantment> ench : e1.keySet()) {
            if (e1.getLevel(ench) != e2.getLevel(ench)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Calculates XP reward based on villager profession level.
     */
    @Unique
    private int calculateXPReward(int professionLevel) {
        return switch (professionLevel) {
            case 1 -> 3;  // Novice: 3 XP
            case 2 -> 5;  // Apprentice: 5 XP
            case 3 -> 10; // Journeyman: 10 XP
            case 4 -> 15; // Expert: 15 XP
            case 5 -> 20; // Master: 20 XP
            default -> 5;
        };
    }

    /**
     * Plays visual and audio effects when a villager learns.
     */
    @Unique
    private void playTeachingEffects(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // Green particle effects (happy villager particles)
        for (int i = 0; i < 20; i++) {
            double offsetX = (villager.getRandom().nextDouble() - 0.5) * 0.5;
            double offsetY = villager.getRandom().nextDouble() * 1.5;
            double offsetZ = (villager.getRandom().nextDouble() - 0.5) * 0.5;

            serverLevel.sendParticles(
                    ParticleTypes.HAPPY_VILLAGER,
                    villager.getX() + offsetX,
                    villager.getY() + offsetY,
                    villager.getZ() + offsetZ,
                    1,
                    0.0, 0.1, 0.0,
                    0.0
            );
        }

        // Play villager "yes" sound
        serverLevel.playSound(
                null,
                villager.blockPosition(),
                SoundEvents.VILLAGER_YES,
                SoundSource.NEUTRAL,
                1.0F,
                1.0F
        );

        // Play level up sound
        serverLevel.playSound(
                null,
                villager.blockPosition(),
                SoundEvents.PLAYER_LEVELUP,
                SoundSource.NEUTRAL,
                0.5F,
                1.5F
        );
    }

    /**
     * Grants the "Teach a Villager" advancement to nearby players.
     */
    @Unique
    private void grantTeachingAdvancement(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            Constants.LOGGER.debug("Not server level, skipping advancement grant");
            return;
        }

        Constants.LOGGER.info("Attempting to grant teaching advancement to nearby players");

        // Find nearby players within 16 blocks
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer serverPlayer) {
                double distance = serverPlayer.distanceToSqr(villager);
                Constants.LOGGER.debug("Found player {} at distance {}", serverPlayer.getName().getString(), Math.sqrt(distance));

                if (distance <= 16 * 16) {
                    // Grant advancement
                    try {
                        net.minecraft.resources.Identifier advancementId =
                            net.minecraft.resources.Identifier.fromNamespaceAndPath(Constants.MOD_ID, "teach_villager");

                        Constants.LOGGER.info("Looking for advancement: {}", advancementId);

                        // Debug: List all advancements to see what's available
                        var allAdvancements = serverLevel.getServer().getAdvancements().getAllAdvancements();
                        Constants.LOGGER.info("Total advancements in registry: {}", allAdvancements.size());

                        // Check if any tradeschool advancements exist
                        long tradeschoolCount = allAdvancements.stream()
                            .filter(a -> a.id().getNamespace().equals(Constants.MOD_ID))
                            .count();
                        Constants.LOGGER.info("Tradeschool advancements found: {}", tradeschoolCount);

                        if (tradeschoolCount > 0) {
                            Constants.LOGGER.info("Available tradeschool advancements:");
                            allAdvancements.stream()
                                .filter(a -> a.id().getNamespace().equals(Constants.MOD_ID))
                                .forEach(a -> Constants.LOGGER.info("  - {}", a.id()));
                        }

                        net.minecraft.advancements.AdvancementHolder advancement =
                            serverLevel.getServer().getAdvancements().get(advancementId);

                        if (advancement != null) {
                            Constants.LOGGER.info("Granting advancement {} to player {}", advancementId, serverPlayer.getName().getString());
                            serverPlayer.getAdvancements().award(advancement, "taught_villager");
                            Constants.LOGGER.info("Advancement granted successfully");
                        } else {
                            Constants.LOGGER.warn("Advancement {} not found in registry!", advancementId);
                        }
                    } catch (Exception e) {
                        Constants.LOGGER.error("Failed to grant teaching advancement", e);
                    }
                }
            }
            return false; // Don't collect, just iterate
        });
    }

    /**
     * Sends a rejection message to nearby players when a villager refuses cursed material.
     */
    @Unique
    private void sendCurseRejectionMessage(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Component message = Component.translatable("tradeschool.teaching.curse_rejected");

        // Send to all players within 16 blocks
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer serverPlayer) {
                double distance = serverPlayer.distanceToSqr(villager);
                if (distance <= 16 * 16) {
                    serverPlayer.sendSystemMessage(message);
                }
            }
            return false; // Don't collect, just iterate
        });
    }

    /**
     * Checks if a profession is supported by Trade School.
     */
    @Unique
    private boolean isTradeSchoolProfession(String profession) {
        return profession.contains("librarian") ||
               profession.contains("weaponsmith") ||
               profession.contains("toolsmith") ||
               profession.contains("armorer") ||
               profession.contains("fletcher");
    }
}
