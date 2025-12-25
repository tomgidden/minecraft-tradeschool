package cx.gid.minecraft.tradeschool.trade;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.ItemKnowledge;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.mixin.VillagerTradesAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import org.jetbrains.annotations.Nullable;

/**
 * Creates "teaching" trades where players sell enchanted items to craftsmen villagers.
 * The villager learns the item template and can then produce that item.
 * Item is consumed and villager pays 1-2 emeralds (regardless of item value).
 *
 * For Weaponsmiths, Toolsmiths, Armourers, and Fletchers.
 */
public class ItemTeachingTradeFactory implements VillagerTrades.ItemListing {

    private final int professionLevel; // Current villager profession level
    private final String professionId; // e.g., "minecraft:weaponsmith"

    public ItemTeachingTradeFactory(int professionLevel, String professionId) {
        this.professionLevel = professionLevel;
        this.professionId = professionId;
    }

    @Override
    @Nullable
    public MerchantOffer getOffer(ServerLevel level, Entity trader, RandomSource random) {
        if (!(trader instanceof Villager villager)) {
            return null;
        }

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        // Check if villager already learned an item at this level
        if (knowledge.getItemKnowledgeAtLevel(professionLevel) != null) {
            return null; // Already learned one at this level
        }

        // Create a trade that accepts ANY appropriate item for this profession
        // We'll validate and learn the item when the trade is executed

        // Use a generic item as placeholder (the actual item will be validated on trade)
        ItemStack acceptedItem = new ItemStack(Items.IRON_SWORD); // Placeholder

        // Villager pays player 1-2 emeralds for teaching them
        int emeraldPayment = ItemPricingCalculator.calculateTeachingPayment(professionLevel);
        ItemStack payment = new ItemStack(Items.EMERALD, emeraldPayment);

        // Create the trade offer
        // Format: Player sells item → Gets emeralds
        return new MerchantOffer(
                new ItemCost(Items.IRON_SWORD, 1), // Placeholder - actual validation in onItemTeachingTradeExecuted
                payment,
                1, // Max uses (can only teach once per level)
                0, // Villager XP gain
                0.0F // Price multiplier
        );
    }

    /**
     * Called when an item teaching trade is executed.
     * Validates the item, extracts the template, and teaches it to the villager.
     *
     * @param villager   The villager being taught
     * @param tradedItem The item that was traded
     * @return true if the villager learned successfully, false otherwise
     */
    public static boolean onItemTeachingTradeExecuted(Villager villager, ItemStack tradedItem) {
        int professionLevel = villager.getVillagerData().level();
        String professionId = villager.getVillagerData().profession().toString();

        // Validate item is appropriate for profession
        if (!EnchantedItemAnalyzer.isItemForProfession(tradedItem.getItem(), professionId)) {
            Constants.LOGGER.warn("Item teaching trade executed with inappropriate item {} for profession {}",
                    tradedItem.getItem(), professionId);
            return false;
        }

        // Validate material tier and enchantment requirements
        // Diamond requires Expert+, Netherite requires Master
        // Max-level-1 enchantments (Silk Touch, Mending) require at least Journeyman (level 3)
        if (!EnchantedItemAnalyzer.canTeachAtLevel(tradedItem, professionLevel)) {
            Constants.LOGGER.warn("Item teaching trade rejected - {} requires higher level than {}",
                    tradedItem.getItem(), professionLevel);
            return false;
        }

        // Analyze item and cap enchantment levels by villager level
        ItemKnowledge itemKnowledge = EnchantedItemAnalyzer.analyzeItem(tradedItem, professionLevel);

        // Teach the villager
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        if (knowledge.teachItem(professionLevel, itemKnowledge)) {
            VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);

            Constants.LOGGER.info("Villager {} learned item {} at level {} with {} enchantments",
                    villager.getUUID(),
                    tradedItem.getItem(),
                    professionLevel,
                    itemKnowledge.getEnchantments().size());

            // Refresh trades to add the new learned item trade
            if (villager.level() instanceof ServerLevel serverLevel) {
                ((VillagerTradesAccessor) villager).tradeschool$updateTrades(serverLevel);
            }

            return true;
        } else {
            Constants.LOGGER.warn("Failed to teach villager - already knows item at level {}",
                    professionLevel);
            return false;
        }
    }
}
