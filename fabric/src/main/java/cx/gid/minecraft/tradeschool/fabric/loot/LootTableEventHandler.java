package cx.gid.minecraft.tradeschool.fabric.loot;

import cx.gid.minecraft.tradeschool.Constants;
import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import cx.gid.minecraft.tradeschool.loot.function.ApplyCurseOfCopyrightFunction;
import cx.gid.minecraft.tradeschool.loot.tier.StructureTier;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

/**
 * Fabric-specific event handler for loot table modifications.
 * Registers with Fabric API to intercept loot table loading and
 * delegates to the common LootDistributionManager.
 */
public class LootTableEventHandler {
    private final LootDistributionManager manager;

    public LootTableEventHandler() {
        this.manager = LootDistributionManager.getInstance();
    }

    /**
     * Registers the loot table modification event with Fabric API.
     */
    public void register() {
        // Initialize manager when loot tables start loading (before first modification)
        LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
            // Initialize on first loot table load (if not already initialized)
            if (!manager.isInitialized()) {
                manager.initializeEarly();
            }

            // Only modify built-in (vanilla) loot tables
            if (!source.isBuiltin()) {
                return;
            }

            // Extract identifier from ResourceKey (format: ResourceKey[minecraft:loot_table / minecraft:chests/ancient_city])
            String keyStr = key.toString();
            int slashIndex = keyStr.lastIndexOf(" / ");
            int endBracket = keyStr.lastIndexOf("]");
            String lootTableId;
            if (slashIndex != -1 && endBracket != -1) {
                lootTableId = keyStr.substring(slashIndex + 3, endBracket);
            } else {
                lootTableId = keyStr; // Fallback
            }

            try {
                // Check if this loot table should have curse applied to existing pools
                StructureTier tier = manager.getTierForLootTable(lootTableId);
                if (tier != null) {
                    // Apply Curse of Copyright to ALL existing pools (vanilla enchanted items)
                    // This uses Fabric's pool modification API
                    tableBuilder.modifyPools(poolBuilder -> {
                        poolBuilder.apply(ApplyCurseOfCopyrightFunction.applyCurse(tier));
                    });
                    Constants.LOGGER.debug("Applied curse function to existing pools in {} (tier: {})", lootTableId, tier);
                }

                // Now add our custom enchanted books
                manager.modifyLootTable(lootTableId, tableBuilder, registries);
            } catch (Exception e) {
                Constants.LOGGER.error("Failed to modify loot table {}", lootTableId, e);
            }
        });

        Constants.LOGGER.info("Registered loot table modification handler");
    }
}
