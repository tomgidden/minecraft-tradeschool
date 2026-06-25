package cx.gid.minecraft.tradeschool.mixin;

import cx.gid.minecraft.tradeschool.PlayerHintAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin implements PlayerHintAccessor {

    @Unique
    private final Set<String> tradeschool$seenHints = new HashSet<>();

    @Override
    public boolean tradeschool$hasSeenHint(String professionType) {
        return tradeschool$seenHints.contains(professionType);
    }

    @Override
    public void tradeschool$markHintSeen(String professionType) {
        tradeschool$seenHints.add(professionType);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void onSave(ValueOutput output, CallbackInfo ci) {
        if (!tradeschool$seenHints.isEmpty()) {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (String hint : tradeschool$seenHints) {
                list.add(StringTag.valueOf(hint));
            }
            tag.put("SeenHints", list);
            output.store("TradeSchool", CompoundTag.CODEC, tag);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void onLoad(ValueInput input, CallbackInfo ci) {
        tradeschool$seenHints.clear();
        input.read("TradeSchool", CompoundTag.CODEC).ifPresent(tag -> {
            ListTag list = tag.getList("SeenHints").orElse(new ListTag());
            for (Tag entry : list) {
                if (entry instanceof StringTag stringTag) {
                    tradeschool$seenHints.add(stringTag.value());
                }
            }
        });
    }
}
