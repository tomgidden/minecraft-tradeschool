package cx.gid.minecraft.tradeschool.fabric;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.TradeSchool;
import cx.gid.minecraft.tradeschool.data.VillagerKnowledgeManager;
import cx.gid.minecraft.tradeschool.fabric.loot.LootTableEventHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.entity.npc.villager.Villager;

public class TradeSchoolFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Call common initialization
        TradeSchool.init();

        // Register Fabric-specific event handlers
        registerEventHandlers();

        // Register loot table event handler
        registerLootTableEvents();

        Constants.LOGGER.info("Trade School (Fabric) initialized");
    }

    /**
     * Registers Fabric event handlers for server lifecycle and entity events.
     */
    private void registerEventHandlers() {
        // Server starting - initialize enchantment lists
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            TradeSchool.onServerStarting(server);
        });

        // Server stopping - cleanup if needed
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Constants.LOGGER.info("Server stopping - cleaning up Trade School data");
            VillagerKnowledgeManager.getInstance().clearCache();
        });

        // Entity load - cache villager data
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof Villager villager) {
                VillagerKnowledgeManager.getInstance().getOrCreateData(villager);
            }
        });

        // Entity unload - save and uncache villager data
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof Villager villager) {
                VillagerKnowledgeManager manager = VillagerKnowledgeManager.getInstance();
                var data = manager.getOrCreateData(villager);
                manager.saveData(villager, data);
                manager.onVillagerUnloaded(villager.getUUID());
            }
        });
    }

    /**
     * Registers loot table modification events.
     */
    private void registerLootTableEvents() {
        LootTableEventHandler lootHandler = new LootTableEventHandler();
        lootHandler.register();
        Constants.LOGGER.info("Loot table event handlers registered");
    }
}
