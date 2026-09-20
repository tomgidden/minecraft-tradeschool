package cx.gid.minecraft.tradeschool.neoforge.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.LootTableLoadEvent;

public class LootTableEventHandler
{
  public static void register(IEventBus modEventBus)
  {
    NeoForge.EVENT_BUS.addListener(LootTableEventHandler::onLootTableLoad);
    Constants.LOGGER.info("Registered loot table modification handler");
  }

  /// Adapts the event's registry provider into what [RegistryOps] needs.
  ///
  /// 26.3 narrowed `LootTableLoadEvent.getRegistries()` to [HolderGetter.Provider], and
  /// `createSerializationContext` exists only on [HolderLookup.Provider]. It is tempting
  /// to assume the object is really the wider type and cast -- it is not. At loot-load
  /// time it is a lambda from `RegistryLoadTask$PendingRegistration`, so the cast fails
  /// for *every* table, and because this handler catches its own failures the only
  /// symptom is that no loot table is ever modified. That is a silent feature outage, not
  /// a crash, which is why this adapts rather than casts.
  ///
  /// [RegistryOps.RegistryInfoLookup] is a one-method interface whose signature matches
  /// `HolderGetter.Provider#lookup` apart from a wildcard on the return type, so the
  /// bridge is just that `map`. The cast inside is safe: `Optional<? extends
  /// HolderGetter<T>>` and `Optional<HolderGetter<T>>` differ only in variance.
  @SuppressWarnings("unchecked")
  private static RegistryOps.RegistryInfoLookup tradeschool$infoLookup(HolderGetter.Provider registries)
  {
    return new RegistryOps.RegistryInfoLookup() {
            @Override
            public <T> java.util.Optional<HolderGetter<T>> lookup(
                    net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<? extends T>> registryKey)
            {
              return registries.lookup(registryKey).map(getter -> (HolderGetter<T>) getter);
            }
    };
  }

  private static void onLootTableLoad(LootTableLoadEvent event)
  {
    LootDistributionManager manager = LootDistributionManager.getInstance();

    if(!manager.isInitialized()) {
      manager.initializeEarly();
    }

    String lootTableId = event.getName().toString();
    // 26.3 narrowed this to HolderGetter.Provider, which is all the manager needs.
    // Encoding below needs more; see tradeschool$infoLookup.
    HolderGetter.Provider registries = event.getRegistries();

    // Shared gate -- keep this identical to the Fabric handler.
    if(!manager.shouldModifyLootTable(lootTableId)) {
      return;
    }

    try {
      List<LootPool.Builder> poolsToAdd = new ArrayList<>();
      manager.modifyLootTableWithConsumer(lootTableId, poolsToAdd::add, registries);

      // Curse the table's existing vanilla pools, matching what the Fabric handler
      // does via modifyPools. Without this, vanilla gear and books found on NeoForge
      // would never carry the curse.
      boolean curseExisting = manager.shouldCurseLootTable(lootTableId);

      if(poolsToAdd.isEmpty() && !curseExisting) {
        return;
      }

      var ops = RegistryOps.create(JsonOps.INSTANCE, tradeschool$infoLookup(registries));

      // Encode the existing table to JSON
      var tableEncoded = LootTable.DIRECT_CODEC.encodeStart(ops, event.getTable());
      if(tableEncoded.isError()) {
        Constants.LOGGER.error("Failed to encode loot table {}: {}", lootTableId, tableEncoded.error());
        return;
      }

      JsonObject tableJson = tableEncoded.getOrThrow().getAsJsonObject();
      JsonArray pools      = tableJson.has("pools")
               ? tableJson.getAsJsonArray("pools")
               : new JsonArray();

      // Attach the curse function to every pre-existing pool. Done before the mod's
      // own pools are appended, since those already carry the function themselves.
      if(curseExisting) {
        var curseEncoded = ApplyCurseOfCopyrightFunction.applyCurse().build();
        var curseJson    = LootItemFunctions.DIRECT_CODEC.encodeStart(ops, curseEncoded);
        if(curseJson.isError()) {
          Constants.LOGGER.error("Failed to encode curse function for {}: {}", lootTableId, curseJson.error());
        }
        else {
          // 26.3 replaced a pool's "functions" array with a single "modifier".
          // Several modifiers are expressed by giving it an array instead, which
          // DIRECT_CODEC reads back as an inline sequence -- so a pool that already
          // has one becomes [existing, curse], preserving order, and a pool with
          // none simply gets the curse. Appending to "functions" as before would
          // now write a key nothing reads, silently losing the curse.
          for(var poolElement: pools) {
            JsonObject poolJson = poolElement.getAsJsonObject();
            var existing        = poolJson.get("modifier");
            if(existing == null || existing.isJsonNull()) {
              poolJson.add("modifier", curseJson.getOrThrow());
            }
            else if(existing.isJsonArray()) {
              existing.getAsJsonArray().add(curseJson.getOrThrow());
            }
            else {
              JsonArray sequence = new JsonArray();
              sequence.add(existing);
              sequence.add(curseJson.getOrThrow());
              poolJson.add("modifier", sequence);
            }
          }
          Constants.LOGGER.debug("Applied curse function to existing pools in {}", lootTableId);
        }
      }

      // Archaeology cannot simply gain a pool: a brushed block keeps only
      // loot.getFirst() and discards the rest, so an added pool is thrown away and
      // the server logs "Expected max 1 loot ... but got 3". Instead the two are made
      // mutually exclusive -- ours carries a random-chance condition and vanilla's
      // carries the inverse, so exactly one yields and ours gets first refusal.
      // Keep this identical to the Fabric handler.
      if(LootDistributionManager.isArchaeology(lootTableId)) {
        LootPool.Builder ourPool = manager.buildArchaeologyOverridePool(lootTableId, registries);
        if(ourPool != null) {
          float chance = manager.archaeologyChance(lootTableId);
          var inverted = net.minecraft.world.level.storage.loot.predicates.InvertedLootItemCondition
                             .invert(net.minecraft.world.level.storage.loot.predicates
                                         .LootItemRandomChanceCondition.randomChance(chance))
                             .build();
          var invEncoded = net.minecraft.world.level.storage.loot.predicates
                               .LootItemCondition.DIRECT_CODEC.encodeStart(ops, inverted);
          var poolEncoded = LootPool.CODEC.encodeStart(ops, ourPool.build());
          if(invEncoded.isError() || poolEncoded.isError()) {
            Constants.LOGGER.error("Failed to encode archaeology override for {}", lootTableId);
          }
          else {
            // Vanilla's existing pools only yield when ours declines. A pool
            // may already have a condition, so combine rather than overwrite.
            for(var poolElement: pools) {
              JsonObject poolJson = poolElement.getAsJsonObject();
              var existing        = poolJson.get("condition");
              if(existing == null || existing.isJsonNull()) {
                poolJson.add("condition", invEncoded.getOrThrow());
              }
              else {
                JsonObject allOf = new JsonObject();
                allOf.addProperty("type", "minecraft:all_of");
                JsonArray terms = new JsonArray();
                terms.add(existing);
                terms.add(invEncoded.getOrThrow());
                allOf.add("terms", terms);
                poolJson.add("condition", allOf);
              }
            }
            pools.add(poolEncoded.getOrThrow());
            tableJson.add("pools", pools);
            Constants.debug("Archaeology override for {} at chance {}", lootTableId, chance);
          }
        }
        poolsToAdd.clear();
      }

      // Encode and append each custom pool
      for(LootPool.Builder poolBuilder: poolsToAdd) {
        LootPool pool   = poolBuilder.build();
        var poolEncoded = LootPool.CODEC.encodeStart(ops, pool);
        if(poolEncoded.isError()) {
          Constants.LOGGER.error("Failed to encode custom pool for {}: {}", lootTableId, poolEncoded.error());
          continue;
        }
        pools.add(poolEncoded.getOrThrow());
        Constants.LOGGER.debug("Appended custom enchanted book pool to {}", lootTableId);
      }

      tableJson.add("pools", pools);

      // Decode the modified JSON back to a LootTable and set it
      var rebuilt = LootTable.DIRECT_CODEC.parse(ops, tableJson);
      if(rebuilt.isError()) {
        Constants.LOGGER.error("Failed to rebuild loot table {}: {}", lootTableId, rebuilt.error());
        return;
      }

      event.setTable(rebuilt.getOrThrow());
      // Info only when pools were actually injected; the curse-only case applies to
      // every found-loot table and would otherwise flood the log at startup.
      if(poolsToAdd.isEmpty()) {
        Constants.LOGGER.debug("Applied curse function to loot table {}", lootTableId);
      }
      else {
        Constants.debug("Modified loot table {} with {} custom pool(s)", lootTableId, poolsToAdd.size());
      }
    }
    catch(Exception e) {
      Constants.LOGGER.error("Failed to modify loot table {}", lootTableId, e);
    }
  }
}
