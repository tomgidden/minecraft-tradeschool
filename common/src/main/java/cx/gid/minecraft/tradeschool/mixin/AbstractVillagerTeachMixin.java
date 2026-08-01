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

        ItemStack taught;
        if (!submitted.isEmpty() && !groupFirst.isEmpty() && submitted.getItem() == groupFirst.getItem()) {
            taught = submitted;
        } else {
            taught = groupFirst;
            Constants.LOGGER.warn("[TradeSchool] notifyTrade: submitted item mismatch or empty, falling back to stored copy");
        }

        // Debug logging
        {
            ItemEnchantments tEnchs = taught.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (tEnchs.isEmpty()) tEnchs = taught.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            Constants.LOGGER.info("[TradeSchool] notifyTrade: teaching item={} enchants={} damage={} repair_cost={}",
                taught.getItem(),
                tEnchs,
                taught.getOrDefault(DataComponents.DAMAGE, 0),
                taught.getOrDefault(DataComponents.REPAIR_COST, 0));
        }

        String professionId = villager.getVillagerData().profession().toString();

        if (professionId.contains("librarian")) {
            tradeschool$teachEnchantedBookNow(villager, taught);
        } else {
            tradeschool$teachEnchantedItemNow(villager, taught);
        }

        tradeschool$playTeachingEffects(villager);
        tradeschool$grantTeachingAdvancement(villager);

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
        Constants.LOGGER.info("[TradeSchool] Villager {} learned: item={} enchants={} damage={} repair_cost={} at level {}",
            villager.getUUID(), k.getBaseItem().value(), k.getEnchantments(),
            learned.getOrDefault(DataComponents.DAMAGE, 0),
            learned.getOrDefault(DataComponents.REPAIR_COST, 0),
            professionLevel);
        tradeschool$sendNearbyMessage(villager, result.learnMessage());
    }

    @Unique
    private void tradeschool$teachEnchantedBookNow(Villager villager, ItemStack book) {
        if (book.getItem() != Items.ENCHANTED_BOOK) return;
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.isEmpty()) return;

        Holder<Enchantment> ench = stored.keySet().iterator().next();
        int professionLevel = villager.getVillagerData().level();
        int lvl = Math.min(stored.getLevel(ench), professionLevel);

        VillagerKnowledgeData knowledge = VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        knowledge.teachEnchantment(professionLevel, ench, lvl);
        VillagerKnowledgeManager.getInstance().saveData(villager, knowledge);
        if (villager.getVillagerXp() <= 0) villager.setVillagerXp(1);

        String enchName = tradeschool$capitaliseWords(ench.unwrapKey()
            .map(k -> k.identifier().getPath().replace('_', ' ')).orElse("unknown"));
        String levelStr = ench.value().getMaxLevel() == 1 ? "" : " " + tradeschool$toRoman(lvl);
        tradeschool$sendNearbyMessage(villager, "The librarian has learned how to enchant a book with '"
            + enchName + levelStr + "'");
        Constants.LOGGER.info("Villager {} learned enchantment {} level {}", villager.getUUID(),
            ench.unwrapKey().map(k -> k.toString()).orElse("unknown"), lvl);
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

    @Unique
    private void tradeschool$playTeachingEffects(Villager villager) {
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        for (int i = 0; i < 20; i++) {
            double ox = (villager.getRandom().nextDouble() - 0.5) * 0.5;
            double oy = villager.getRandom().nextDouble() * 1.5;
            double oz = (villager.getRandom().nextDouble() - 0.5) * 0.5;
            serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                villager.getX() + ox, villager.getY() + oy, villager.getZ() + oz,
                1, 0.0, 0.1, 0.0, 0.0);
        }
        serverLevel.playSound(null, villager.blockPosition(),
            SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1.0f, 1.0f);
        serverLevel.playSound(null, villager.blockPosition(),
            SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.5f, 1.5f);
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

    @Unique
    private void tradeschool$sendNearbyMessage(Villager villager, String text) {
        Constants.LOGGER.info(text);
        if (!(villager.level() instanceof ServerLevel serverLevel)) return;
        net.minecraft.network.chat.Component msg = net.minecraft.network.chat.Component.literal(text);
        serverLevel.getPlayers(player -> {
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(villager) <= 16 * 16)
                sp.sendSystemMessage(msg);
            return false;
        });
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
