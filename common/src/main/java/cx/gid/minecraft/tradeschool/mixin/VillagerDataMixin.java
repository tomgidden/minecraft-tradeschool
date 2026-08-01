package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Villager.class)
public abstract class VillagerDataMixin {

    @Shadow
    public abstract VillagerData getVillagerData();

    @Inject(method = "setVillagerData", at = @At("HEAD"))
    private void onSetVillagerData(VillagerData newData, CallbackInfo ci) {
        VillagerData currentData = this.getVillagerData();
        Villager self = (Villager) (Object) this;

        // Log all profession changes for debugging
        String currentProf = currentData.profession().toString();
        String newProf = newData.profession().toString();

        if (!currentProf.equals(newProf)) {
            Constants.LOGGER.debug("Villager {} profession changed: {} -> {}", self.getUUID(), currentProf, newProf);
            if (currentProf.contains("librarian") && newProf.contains("none")) {
                Constants.LOGGER.debug("Villager {} profession reverting from LIBRARIAN to NONE!", self.getUUID());
                Constants.LOGGER.debug("Level: {} -> {}", currentData.level(), newData.level());
                Constants.LOGGER.debug("Stack trace for profession revert:", new Throwable());
            }
        }
    }
}
