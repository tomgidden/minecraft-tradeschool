package cx.gid.minecraft.tradeschool.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin to expose protected updateTrades method.
 */
@Mixin(Villager.class)
public interface VillagerTradesAccessor {

    /**
     * Invoker for protected updateTrades method.
     */
    @Invoker("updateTrades")
    void tradeschool$updateTrades(ServerLevel level);
}
