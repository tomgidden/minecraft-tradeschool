package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.enchantment.ModEnchantments;
import cx.gid.minecraft.tradeschool.mixin.VillagerTradesAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/**
 * Creates "teaching" trades where players sell enchanted books to librarians.
 * The librarian learns the enchantment type from the book.
 * Book is consumed and villager pays emeralds.
 *
 * Hardcoded English for server-side-only proof-of-concept only.
 * A complete implementation would use Component.translatable(...) with proper keys
 * but would require the mod client-side for i18n.
 */
public class TeachingTradeFactory {

    private final int professionLevel; // Current villager profession level

    public TeachingTradeFactory(int professionLevel) {
        this.professionLevel = professionLevel;
    }

    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Entity trader, RandomSource random) {
        if (!(trader instanceof Villager villager)) {
            return null;
        }

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Check if villager already learned an enchantment at this level
        if (knowledge.getKnowledgeAtLevel(professionLevel) != null) {
            return null; // Already learned one at this level
        }

        // Create a trade that accepts ANY enchanted book
        // We'll validate and learn the enchantment when the trade is executed
        ItemStack acceptedBook = new ItemStack(Items.ENCHANTED_BOOK);

        // Villager pays player emeralds for teaching them
        int emeraldPayment = calculateTeachingPayment();
        ItemStack payment = new ItemStack(Items.EMERALD, emeraldPayment);

        // Create the trade offer
        // Format: Player sells enchanted book → Gets emeralds
        return new MerchantOffer(
                new ItemCost(Items.ENCHANTED_BOOK), // What player sells
                payment, // What player gets (emeralds)
                1, // Max uses (can only teach once per level)
                0, // Villager XP gain
                0.0F // Price multiplier
        );
    }

    /**
     * Calculates how many emeralds the villager pays for being taught.
     * As per Phase 8 requirements, teaching only earns 1-2 emeralds regardless of book value.
     */
    private int calculateTeachingPayment() {
        // Simple formula: 1 emerald for levels 1-3, 2 emeralds for levels 4-5
        return professionLevel >= 4 ? 2 : 1;
    }

    /**
     * Called when a teaching trade is executed.
     * Extracts the enchantment from the book and teaches it to the villager.
     *
     * @param villager   The villager being taught
     * @param tradedBook The enchanted book that was traded
     * @return true if the villager learned successfully, false otherwise
     */
    public static boolean onTeachingTradeExecuted(Villager villager, ItemStack tradedBook) {
        if (tradedBook.getItem() != Items.ENCHANTED_BOOK) {
            Constants.LOGGER.warn("Teaching trade executed with non-enchanted book!");
            return false;
        }

        // Extract enchantments from the book
        ItemEnchantments enchantments = tradedBook.getOrDefault(DataComponents.STORED_ENCHANTMENTS,
                ItemEnchantments.EMPTY);
        if (enchantments.isEmpty()) {
            enchantments = tradedBook.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        }

        if (enchantments.isEmpty()) {
            Constants.LOGGER.warn("Teaching trade executed with book that has no enchantments! Components present: {}",
                    tradedBook.getComponents().stream().map(c -> c.type().toString())
                            .collect(java.util.stream.Collectors.joining(", ")));
            return false;
        }

        // Check for Curse of Copyright before processing
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (ModEnchantments.isCurseOfCopyright(enchantment)) {
                Constants.LOGGER.info("Villager {} refused to learn copyrighted enchantment", villager.getUUID());
                // Send message to nearby players
                sendCurseRejectionMessage(villager);
                return false;
            }
        }

        // Get the first enchantment (books should only have one, but we'll take first
        // if multiple)
        Holder<Enchantment> learnedEnchantment = null;
        int bookEnchantmentLevel = 1;
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            learnedEnchantment = enchantment;
            bookEnchantmentLevel = enchantments.getLevel(enchantment);
            break;
        }

        if (learnedEnchantment == null) {
            Constants.LOGGER.warn("Could not extract enchantment from book!");
            return false;
        }

        int professionLevel = villager.getVillagerData().level();

        // Cap the enchantment level by the villager's current profession level
        // If you sell Power V to a Level 3 villager, they only learn Power III
        int cappedLevel = Math.min(bookEnchantmentLevel, professionLevel);

        // Teach the villager
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        if (knowledge.teachEnchantment(professionLevel, learnedEnchantment, cappedLevel)) {
            VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);

            Constants.LOGGER.info("Villager {} learned enchantment {} level {} (book was level {}, villager is level {})",
                    villager.getUUID(),
                    learnedEnchantment.unwrapKey().map(key -> key.toString()).orElse("unknown"),
                    cappedLevel,
                    bookEnchantmentLevel,
                    professionLevel);

            // Refresh trades to add the new learned enchantment trade
            if (villager.level() instanceof ServerLevel serverLevel) {
                ((VillagerTradesAccessor) villager).tradeschool$updateTrades(serverLevel);
            }

            return true;
        } else {
            Constants.LOGGER.warn("Failed to teach villager - already knows enchantment at level {}",
                    professionLevel);
            return false;
        }
    }

    /**
     * Sends a rejection message to nearby players when a villager refuses cursed material.
     *
     * @param villager The villager that refused
     */
    private static void sendCurseRejectionMessage(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        // Should be Component.translatable("tradeschool.teaching.curse_rejected") with a lang file client-side.
        Component message = Component.literal("The villager refuses to study copyrighted material!");

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
}
