package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.VillagerEntityAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to persist custom villager data during save/load.
 */
@Mixin(Villager.class)
public abstract class VillagerEntityMixin implements VillagerEntityAccessor {

    @Unique
    private CompoundTag tradeschool$persistentData = new CompoundTag();

    @Override
    public CompoundTag tradeschool$getPersistentData() {
        return this.tradeschool$persistentData;
    }

    @Override
    public void tradeschool$setPersistentData(CompoundTag data) {
        this.tradeschool$persistentData = data;
    }

    // TODO: Fix persistence in 1.21.11 - ValueOutput/ValueInput API changed
    // For now, data won't persist across restarts but will work within a session

    /*
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        CompoundTag persistentData = this.tradeschool$persistentData;
        if (persistentData != null && !persistentData.isEmpty()) {
            output.put("TradeSchool", persistentData);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        CompoundTag loadedData = input.getCompound("TradeSchool").orElse(new CompoundTag());
        if (!loadedData.isEmpty()) {
            this.tradeschool$persistentData = loadedData;
        }
    }
    */
}
