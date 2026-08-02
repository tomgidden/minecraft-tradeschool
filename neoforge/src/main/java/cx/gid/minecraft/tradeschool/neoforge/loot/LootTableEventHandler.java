package cx.gid.minecraft.tradeschool.neoforge.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
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

        // Shared gate — keep this identical to the Fabric handler.
        if (!manager.shouldModifyLootTable(lootTableId)) {
            return;
        }

        try {
            List<LootPool.Builder> poolsToAdd = new ArrayList<>();
            manager.modifyLootTableWithConsumer(lootTableId, poolsToAdd::add, registries);

            // Curse the table's existing vanilla pools, matching what the Fabric handler
            // does via modifyPools. Without this, vanilla gear and books found on NeoForge
            // would never carry the curse.
            boolean curseExisting = manager.shouldCurseLootTable(lootTableId);

            if (poolsToAdd.isEmpty() && !curseExisting) {
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

            // Attach the curse function to every pre-existing pool. Done before the mod's
            // own pools are appended, since those already carry the function themselves.
            if (curseExisting) {
                var curseEncoded = ApplyCurseOfCopyrightFunction.applyCurse().build();
                var curseJson = LootItemFunctions.ROOT_CODEC.encodeStart(ops, curseEncoded);
                if (curseJson.isError()) {
                    Constants.LOGGER.error("Failed to encode curse function for {}: {}",
                        lootTableId, curseJson.error());
                } else {
                    for (var poolElement : pools) {
                        JsonObject poolJson = poolElement.getAsJsonObject();
                        JsonArray functions = poolJson.has("functions")
                            ? poolJson.getAsJsonArray("functions")
                            : new JsonArray();
                        functions.add(curseJson.getOrThrow());
                        poolJson.add("functions", functions);
                    }
                    Constants.LOGGER.debug("Applied curse function to existing pools in {}", lootTableId);
                }
            }

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
            // Info only when pools were actually injected; the curse-only case applies to
            // every found-loot table and would otherwise flood the log at startup.
            if (poolsToAdd.isEmpty()) {
                Constants.LOGGER.debug("Applied curse function to loot table {}", lootTableId);
            } else {
                Constants.debug("Modified loot table {} with {} custom pool(s)",
                    lootTableId, poolsToAdd.size());
            }

        } catch (Exception e) {
            Constants.LOGGER.error("Failed to modify loot table {}", lootTableId, e);
        }
    }
}
