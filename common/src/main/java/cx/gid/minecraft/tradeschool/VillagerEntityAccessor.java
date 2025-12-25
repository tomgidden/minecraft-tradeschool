package cx.gid.minecraft.tradeschool;

import net.minecraft.nbt.CompoundTag;

/**
 * Interface to add custom data storage to Villager entities.
 * Provides access to persistent NBT data that travels with the entity.
 * Implemented by VillagerEntityMixin.
 */
public interface VillagerEntityAccessor {

    /**
     * Gets the custom persistent data for this villager.
     */
    CompoundTag tradeschool$getPersistentData();

    /**
     * Sets the custom persistent data for this villager.
     */
    void tradeschool$setPersistentData(CompoundTag data);
}
