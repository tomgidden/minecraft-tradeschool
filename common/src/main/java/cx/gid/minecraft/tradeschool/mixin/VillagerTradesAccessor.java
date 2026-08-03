package cx.gid.minecraft.tradeschool.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/// Exposes the trade-regeneration and promotion internals of [Villager].
@Mixin(Villager.class)
public interface VillagerTradesAccessor {

        /// Invoker for protected updateTrades method.
    @Invoker("updateTrades")
    void tradeschool$updateTrades(ServerLevel level);

        /// Ticks down while the villager is not trading; on reaching zero it applies a pending
    /// promotion. Vanilla sets it to 40 after a trade that earns a level, which is the
    /// delay a player sees as "close the screen, wait, then the villager levels up".
    @Accessor("updateMerchantTimer")
    void tradeschool$setUpdateMerchantTimer(int ticks);

        /// Whether a promotion is waiting for that timer to expire.
    @Accessor("increaseProfessionLevelOnUpdate")
    void tradeschool$setIncreaseProfessionLevelOnUpdate(boolean pending);
}
