package cx.gid.minecraft.tradeschool.loot.function;

import cx.gid.minecraft.tradeschool.Constants;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;

/**
 * Registry for custom loot item functions.
 */
public class ModLootFunctions {
    public static final LootItemFunctionType<ApplyCurseOfCopyrightFunction> APPLY_CURSE_OF_COPYRIGHT =
        register("apply_curse_of_copyright", ApplyCurseOfCopyrightFunction.CODEC);

    private static <T extends LootItemFunction> LootItemFunctionType<T> register(String name, com.mojang.serialization.MapCodec<T> codec) {
        Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, name);
        return Registry.register(BuiltInRegistries.LOOT_FUNCTION_TYPE, id, new LootItemFunctionType<>(codec));
    }

    /**
     * Called during mod initialization to register loot functions.
     */
    public static void register() {
        Constants.LOGGER.info("Registering loot functions for {}", Constants.MOD_ID);
        Constants.LOGGER.info("APPLY_CURSE_OF_COPYRIGHT function registered: {}", APPLY_CURSE_OF_COPYRIGHT);
    }
}
