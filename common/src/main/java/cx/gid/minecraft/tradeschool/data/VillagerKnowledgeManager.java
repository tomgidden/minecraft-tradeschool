package cx.gid.minecraft.tradeschool.data;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages persistent storage of villager knowledge data.
 * Uses a combination of:
 * 1. Entity NBT data (primary storage, travels with villager)
 * 2. In-memory cache for performance
 */
public class VillagerKnowledgeManager {
    private static final String DATA_KEY = "tradeschool_data";

    // Cache of villager knowledge, keyed by entity UUID
    private final Map<UUID, VillagerKnowledgeData> knowledgeCache = new ConcurrentHashMap<>();

    private static VillagerKnowledgeManager instance;

    private VillagerKnowledgeManager() {
    }

    public static VillagerKnowledgeManager getInstance() {
        if (instance == null) {
            instance = new VillagerKnowledgeManager();
        }
        return instance;
    }

    /**
     * Retrieves or creates knowledge data for a villager.
     * First checks cache, then entity NBT, then creates new.
     *
     * @param villager The villager entity
     * @return The knowledge data for this villager
     */
    public VillagerKnowledgeData getOrCreateData(Villager villager) {
        UUID uuid = villager.getUUID();

        // Check cache first
        VillagerKnowledgeData cached = knowledgeCache.get(uuid);
        if (cached != null) {
            return cached;
        }

        // Try to load from entity NBT
        CompoundTag persistentData = getPersistentData(villager);
        CompoundTag dataTag = persistentData.getCompound(DATA_KEY).orElse(null);
        if (dataTag != null && !dataTag.isEmpty()) {
            try {
                VillagerKnowledgeData data = VillagerKnowledgeData.fromNbt(
                        dataTag,
                        villager.registryAccess()
                );
                knowledgeCache.put(uuid, data);
                return data;
            } catch (Exception e) {
                Constants.LOGGER.error("Failed to load villager knowledge data for UUID {}", uuid, e);
                // Fall through to create new data
            }
        }

        // Create new data
        VillagerKnowledgeData newData = new VillagerKnowledgeData(uuid);
        knowledgeCache.put(uuid, newData);
        saveData(villager, newData);
        return newData;
    }

    /**
     * Saves knowledge data to entity NBT.
     *
     * @param villager The villager entity
     * @param data The data to save
     */
    public void saveData(Villager villager, VillagerKnowledgeData data) {
        try {
            CompoundTag persistentData = getPersistentData(villager);
            persistentData.put(DATA_KEY, data.toNbt(villager.registryAccess()));
            knowledgeCache.put(villager.getUUID(), data);
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to save villager knowledge data for UUID {}", villager.getUUID(), e);
        }
    }

    /**
     * Gets the persistent data container for an entity.
     * This accesses the custom NBT data stored on the entity via our mixin.
     */
    private CompoundTag getPersistentData(Villager villager) {
        // Cast to the interface mixin to access custom data
        if (villager instanceof cx.gid.minecraft.tradeschool.VillagerEntityAccessor accessor) {
            return accessor.tradeschool$getPersistentData();
        }
        // Fallback if somehow the mixin isn't applied
        return new CompoundTag();
    }

    /**
     * Called when a villager entity is loaded from disk.
     * Preloads the data into cache.
     */
    public void onVillagerLoaded(Villager villager) {
        getOrCreateData(villager);
    }

    /**
     * Removes data from cache when villager is removed/unloaded.
     * The data persists in NBT, but we clear the cache.
     */
    public void onVillagerUnloaded(UUID uuid) {
        knowledgeCache.remove(uuid);
    }

    /**
     * Clears the entire cache. Useful for server stop/reload.
     */
    public void clearCache() {
        knowledgeCache.clear();
    }

    /**
     * Gets the number of villagers currently cached.
     */
    public int getCacheSize() {
        return knowledgeCache.size();
    }
}
