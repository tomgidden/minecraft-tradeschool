package cx.gid.minecraft.tradeschool.neoforge;

import com.mojang.serialization.MapCodec;
import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.TradeSchool;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import cx.gid.minecraft.tradeschool.loot.function.ModLootFunctions;
import cx.gid.minecraft.tradeschool.neoforge.loot.LootTableEventHandler;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Constants.MOD_ID)
public class TradeSchoolNeoForge {

    public TradeSchoolNeoForge(IEventBus modEventBus) {
        registerLootFunctions(modEventBus);
        TradeSchool.initWithoutRegistry();

        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onEntityJoin);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);

        LootTableEventHandler.register(modEventBus);

        Constants.LOGGER.info("Trade School (NeoForge) initialized");
    }

    private void registerLootFunctions(IEventBus modEventBus) {
        DeferredRegister<MapCodec<? extends LootItemFunction>> lootFunctions =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Constants.MOD_ID);
        lootFunctions.register("apply_curse_of_copyright",
            () -> ApplyCurseOfCopyrightFunction.CODEC);
        lootFunctions.register(modEventBus);
        ModLootFunctions.APPLY_CURSE_OF_COPYRIGHT = ApplyCurseOfCopyrightFunction.CODEC;
    }

    private void onServerStarting(ServerStartingEvent event) {
        TradeSchool.onServerStarting(event.getServer());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        Constants.LOGGER.info("Server stopping - cleaning up Trade School data");
        VillagerKnowledgeManager.getInstance().clearCache();
    }

    private void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof Villager villager && !event.getLevel().isClientSide()) {
            VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
        }
    }

    private void onEntityLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof Villager villager && !event.getLevel().isClientSide()) {
            VillagerKnowledgeManager manager = VillagerKnowledgeManager.getInstance();
            var data = manager.getOrCreateData(villager);
            manager.saveData(villager, data);
            manager.onVillagerUnloaded(villager.getUUID());
        }
    }
}
