package cx.gid.minecraft.tradeschool.neoforge.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.LootTableLoadEvent;

import java.util.ArrayList;
import java.util.List;

public class LootTableEventHandler {

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(LootTableEventHandler::onLootTableLoad);
        Constants.LOGGER.info("Registered loot table modification handler");
    }

    private static void onLootTableLoad(LootTableLoadEvent event) {
        LootDistributionManager manager = LootDistributionManager.getInstance();

        if (!manager.isInitialized()) {
            manager.initializeEarly();
        }

        String lootTableId = event.getName().toString();
        HolderLookup.Provider registries = event.getRegistries();

        try {
            List<LootPool.Builder> poolsToAdd = new ArrayList<>();
            manager.modifyLootTableWithConsumer(lootTableId, poolsToAdd::add, registries);

            if (poolsToAdd.isEmpty()) {
                return;
            }

            var ops = registries.createSerializationContext(JsonOps.INSTANCE);

            // Encode the existing table to JSON
            var tableEncoded = LootTable.DIRECT_CODEC.encodeStart(ops, event.getTable());
            if (tableEncoded.isError()) {
                Constants.LOGGER.error("Failed to encode loot table {}: {}", lootTableId, tableEncoded.error());
                return;
            }

            JsonObject tableJson = tableEncoded.getOrThrow().getAsJsonObject();
            JsonArray pools = tableJson.has("pools")
                ? tableJson.getAsJsonArray("pools")
                : new JsonArray();

            // Encode and append each custom pool
            for (LootPool.Builder poolBuilder : poolsToAdd) {
                LootPool pool = poolBuilder.build();
                var poolEncoded = LootPool.CODEC.encodeStart(ops, pool);
                if (poolEncoded.isError()) {
                    Constants.LOGGER.error("Failed to encode custom pool for {}: {}", lootTableId, poolEncoded.error());
                    continue;
                }
                pools.add(poolEncoded.getOrThrow());
                Constants.LOGGER.debug("Appended custom enchanted book pool to {}", lootTableId);
            }

            tableJson.add("pools", pools);

            // Decode the modified JSON back to a LootTable and set it
            var rebuilt = LootTable.DIRECT_CODEC.parse(ops, tableJson);
            if (rebuilt.isError()) {
                Constants.LOGGER.error("Failed to rebuild loot table {}: {}", lootTableId, rebuilt.error());
                return;
            }

            event.setTable(rebuilt.getOrThrow());
            Constants.LOGGER.info("Modified loot table {} with {} custom pool(s)", lootTableId, poolsToAdd.size());

        } catch (Exception e) {
            Constants.LOGGER.error("Failed to modify loot table {}", lootTableId, e);
        }
    }
}
