package cx.gid.minecraft.tradeschool.loot.function;

import cx.gid.minecraft.tradeschool.Constants;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;

/**
 * Registry for custom loot item functions.
 */
public class ModLootFunctions {
    public static MapCodec<ApplyCurseOfCopyrightFunction> APPLY_CURSE_OF_COPYRIGHT;

    public static void register() {
        Constants.LOGGER.info("Registering loot functions for {}", Constants.MOD_ID);
        APPLY_CURSE_OF_COPYRIGHT = doRegister("apply_curse_of_copyright", ApplyCurseOfCopyrightFunction.CODEC);
    }

    private static <T extends LootItemFunction> MapCodec<T> doRegister(String name, MapCodec<T> codec) {
        Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, name);
        Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, id, codec);
        return codec;
    }
}
