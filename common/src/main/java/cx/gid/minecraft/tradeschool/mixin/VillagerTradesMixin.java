package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeData;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
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
        VillagerData data = villager.getVillagerData();
        String professionId = data.profession().toString();

        Constants.LOGGER.info("Intercepting potential trade update. Profession: {}, Level: {}",
                data.profession(), data.level());

        // Check if this is a profession we're modifying
        boolean isLibrarian = professionId.contains("librarian");
        boolean isItemProfession = isItemTeachingProfession(professionId);

        if (!isLibrarian && !isItemProfession) {
            return; // Not a profession we're handling
        }

        Constants.LOGGER.info("Intercepting trade update (Overwrite) for {} villager at level {}",
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
        offers.clear(); // Remove any existing trades

        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        Constants.LOGGER.debug("Generating trades for librarian (level {})", professionLevel);

        // Vanilla non-book trades first (paper, bookshelf, etc.)
        addVanillaNonBookTrades(villager, offers, professionLevel);

        // Learned enchantment trades at the bottom
        java.util.List<cx.gid.minecraft.tradeschool.data.EnchantmentKnowledge> learnedEnchants = knowledge
                .getKnowledgeUpToLevel(professionLevel);
        for (cx.gid.minecraft.tradeschool.data.EnchantmentKnowledge enchantKnowledge : learnedEnchants) {
            ServerLevel level = (ServerLevel) villager.level();
            cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory learnedTrade = new cx.gid.minecraft.tradeschool.trade.LearnedTradeFactory(
                    enchantKnowledge);
            MerchantOffer offer = learnedTrade.getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                offers.add(offer);
                Constants.LOGGER.debug("Added learned trade for enchantment learned at level {}",
                        enchantKnowledge.getLearnedAtLevel());
            }
        }

        Constants.LOGGER.debug("Generated {} total trades for librarian", offers.size());
    }

    /**
     * Adds back vanilla librarian trades that aren't enchanted books.
     * These include: paper, bookshelf, lantern, glass, compass, clock, name tag.
     */
    private void addVanillaNonBookTrades(Villager villager, MerchantOffers offers, int level) {
        if (level >= 1) {
            // Novice: Buy paper, sell bookshelf
            // Villager buys 24 paper for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.PAPER, 24),
                    new ItemStack(Items.EMERALD, 1),
                    16, // Max uses
                    2, // Villager XP
                    0.05F));

            // Villager sells 1 bookshelf for 9 emeralds
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 9),
                    new ItemStack(Items.BOOKSHELF, 1),
                    12, // Max uses
                    1, // Villager XP
                    0.05F));
        }

        if (level >= 2) {
            // Apprentice: Sell lantern, buy book
            // Villager buys 4 books for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.BOOK, 4),
                    new ItemStack(Items.EMERALD, 1),
                    12,
                    10,
                    0.05F));

            // Villager sells 1 lantern for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 1),
                    new ItemStack(Items.LANTERN, 1),
                    12,
                    5,
                    0.05F));
        }

        if (level >= 3) {
            // Journeyman: Buy ink sac, sell glass
            // Villager buys 5 ink sacs for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.INK_SAC, 5),
                    new ItemStack(Items.EMERALD, 1),
                    12,
                    20,
                    0.05F));

            // Villager sells 4 glass for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 1),
                    new ItemStack(Items.GLASS, 4),
                    12,
                    10,
                    0.05F));
        }

        if (level >= 4) {
            // Expert: Buy book and quill, sell compass/clock
            // Villager buys 2 books and quills for 1 emerald
            offers.add(new MerchantOffer(
                    new ItemCost(Items.WRITABLE_BOOK, 2),
                    new ItemStack(Items.EMERALD, 1),
                    12,
                    30,
                    0.05F));

            // Villager sells 1 compass for 4 emeralds
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 4),
                    new ItemStack(Items.COMPASS, 1),
                    12,
                    15,
                    0.05F));

            // Villager sells 1 clock for 5 emeralds
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 5),
                    new ItemStack(Items.CLOCK, 1),
                    12,
                    15,
                    0.05F));
        }

        if (level >= 5) {
            // Master: Sell name tag
            // Villager sells 1 name tag for 20 emeralds
            offers.add(new MerchantOffer(
                    new ItemCost(Items.EMERALD, 20),
                    new ItemStack(Items.NAME_TAG, 1),
                    12,
                    30,
                    0.05F));
        }

        Constants.LOGGER.debug("Added {} vanilla non-book trades for level {}",
                (level >= 1 ? 2 : 0) + (level >= 2 ? 2 : 0) + (level >= 3 ? 2 : 0) +
                        (level >= 4 ? 3 : 0) + (level >= 5 ? 1 : 0),
                level);
    }

    /**
     * Generates custom trades for item-teaching professions
     * (Weaponsmith, Toolsmith, Armourer, Fletcher).
     * Includes: teaching trades, learned item trades, and vanilla basic trades.
     */
    private void generateCustomItemProfessionTrades(Villager villager, VillagerData data, String professionId) {
        MerchantOffers offers = villager.getOffers();
        offers.clear(); // Remove any existing trades

        int professionLevel = data.level();
        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance()
                .getOrCreateData(villager);

        Constants.LOGGER.debug("Generating trades for {} (level {})", professionId, professionLevel);

        // Vanilla basic trades first (iron, coal, etc.)
        addVanillaBasicTrades(villager, offers, professionLevel, professionId);

        // Learned item trades at the bottom — one per learned level, up to current level
        java.util.List<cx.gid.minecraft.tradeschool.data.ItemKnowledge> learnedItems =
            knowledge.getItemKnowledgeUpToLevel(professionLevel);
        for (cx.gid.minecraft.tradeschool.data.ItemKnowledge itemKnowledge : learnedItems) {
            ServerLevel level = (ServerLevel) villager.level();
            cx.gid.minecraft.tradeschool.trade.LearnedItemTradeFactory learnedTrade =
                new cx.gid.minecraft.tradeschool.trade.LearnedItemTradeFactory(itemKnowledge);
            MerchantOffer offer = learnedTrade.getOffer(level, villager, villager.getRandom());
            if (offer != null) {
                offers.add(offer);
                Constants.LOGGER.debug("Added learned item trade for item learned at level {}",
                        itemKnowledge.getLearnedAtLevel());
            }
        }

        Constants.LOGGER.debug("Generated {} total trades for {}", offers.size(), professionId);
    }

    /**
     * Adds basic vanilla trades for item professions (excluding enchanted items).
     * These include: iron, coal, diamonds, etc.
     */
    private void addVanillaBasicTrades(Villager villager, MerchantOffers offers, int level, String professionId) {
        if (professionId.contains("weaponsmith")) {
            if (level >= 1) {
                // Novice: Buy coal, sell iron axe (unenchanted)
                offers.add(new MerchantOffer(
                        new ItemCost(Items.COAL, 15),
                        new ItemStack(Items.EMERALD, 1),
                        16, 2, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 3),
                        new ItemStack(Items.IRON_AXE, 1),
                        12, 1, 0.2F));
            }
            if (level >= 2) {
                // Apprentice: Buy iron
                offers.add(new MerchantOffer(
                        new ItemCost(Items.IRON_INGOT, 4),
                        new ItemStack(Items.EMERALD, 1),
                        12, 10, 0.05F));
            }
            if (level >= 3) {
                // Journeyman: Buy flint
                offers.add(new MerchantOffer(
                        new ItemCost(Items.FLINT, 24),
                        new ItemStack(Items.EMERALD, 1),
                        12, 20, 0.05F));
            }
        } else if (professionId.contains("toolsmith")) {
            if (level >= 1) {
                // Novice: Buy coal
                offers.add(new MerchantOffer(
                        new ItemCost(Items.COAL, 15),
                        new ItemStack(Items.EMERALD, 1),
                        16, 2, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 1),
                        new ItemStack(Items.STONE_AXE, 1),
                        12, 1, 0.05F));
            }
            if (level >= 2) {
                // Apprentice: Buy iron
                offers.add(new MerchantOffer(
                        new ItemCost(Items.IRON_INGOT, 4),
                        new ItemStack(Items.EMERALD, 1),
                        12, 10, 0.05F));
            }
            if (level >= 3) {
                // Journeyman: Buy flint
                offers.add(new MerchantOffer(
                        new ItemCost(Items.FLINT, 30),
                        new ItemStack(Items.EMERALD, 1),
                        12, 20, 0.05F));
            }
        } else if (professionId.contains("armorer")) {
            if (level >= 1) {
                // Novice: Buy coal
                offers.add(new MerchantOffer(
                        new ItemCost(Items.COAL, 15),
                        new ItemStack(Items.EMERALD, 1),
                        16, 2, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 5),
                        new ItemStack(Items.IRON_HELMET, 1),
                        12, 1, 0.2F));
            }
            if (level >= 2) {
                // Apprentice: Buy iron
                offers.add(new MerchantOffer(
                        new ItemCost(Items.IRON_INGOT, 4),
                        new ItemStack(Items.EMERALD, 1),
                        12, 10, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 9),
                        new ItemStack(Items.IRON_CHESTPLATE, 1),
                        12, 5, 0.2F));
            }
            if (level >= 3) {
                // Journeyman: Buy lava bucket
                offers.add(new MerchantOffer(
                        new ItemCost(Items.LAVA_BUCKET, 1),
                        new ItemStack(Items.EMERALD, 1),
                        12, 20, 0.05F));
            }
        } else if (professionId.contains("fletcher")) {
            if (level >= 1) {
                // Novice: Buy sticks, sell arrows
                offers.add(new MerchantOffer(
                        new ItemCost(Items.STICK, 32),
                        new ItemStack(Items.EMERALD, 1),
                        16, 2, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 1),
                        new ItemStack(Items.ARROW, 16),
                        12, 1, 0.05F));
            }
            if (level >= 2) {
                // Apprentice: Buy flint
                offers.add(new MerchantOffer(
                        new ItemCost(Items.FLINT, 26),
                        new ItemStack(Items.EMERALD, 1),
                        12, 10, 0.05F));
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 2),
                        new ItemStack(Items.BOW, 1),
                        12, 5, 0.05F));
            }
            if (level >= 3) {
                // Journeyman: Buy string
                offers.add(new MerchantOffer(
                        new ItemCost(Items.STRING, 14),
                        new ItemStack(Items.EMERALD, 1),
                        16, 20, 0.05F));
            }
            if (level >= 4) {
                // Expert: Sell crossbow (unenchanted)
                offers.add(new MerchantOffer(
                        new ItemCost(Items.EMERALD, 3),
                        new ItemStack(Items.CROSSBOW, 1),
                        12, 15, 0.05F));
            }
        }

        Constants.LOGGER.debug("Added vanilla basic trades for {}", professionId);
    }
}
