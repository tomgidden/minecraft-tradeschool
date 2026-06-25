package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.VillagerEntityAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        if (!this.tradeschool$persistentData.isEmpty()) {
            output.store("TradeSchool", CompoundTag.CODEC, this.tradeschool$persistentData);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        input.read("TradeSchool", CompoundTag.CODEC).ifPresent(data -> {
            if (!data.isEmpty()) {
                this.tradeschool$persistentData = data;
            }
        });
    }
}
