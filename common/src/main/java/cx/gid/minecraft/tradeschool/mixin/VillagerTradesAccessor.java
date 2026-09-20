package cx.gid.minecraft.tradeschool.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/// Exposes the trade-regeneration and promotion internals of [Villager].
@Mixin(Villager.class)
public interface VillagerTradesAccessor {
  /// Invoker for protected updateTrades method.
  @Invoker("updateTrades")
  void tradeschool$updateTrades(ServerLevel level);

  /// Promotes the villager a level and rebuilds its trades, immediately.
  ///
  /// Until 26.2 this was deferred through a pair of fields -- `updateMerchantTimer`
  /// counted down only while the villager was *not* trading, so a promotion could never
  /// rebuild the offer list underneath an open menu. 26.3 deleted both fields and
  /// `rewardTradeXp` now calls this directly, because `updateTrades` resends the offers
  /// to whoever has the screen open. Mirroring vanilla is therefore the correct move:
  /// there is no longer a deferral to imitate.
  ///
  /// Note these are **accessors, not compiled calls** -- a field or method that vanishes
  /// fails at mixin-apply time, not at compile time, and takes the server down with it.
  /// The build is no guard here; only running it is.
  @Invoker("increaseMerchantCareer")
  void tradeschool$increaseMerchantCareer(ServerLevel level);
}
