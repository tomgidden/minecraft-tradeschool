package cx.gid.minecraft.tradeschool;

import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import cx.gid.minecraft.tradeschool.loot.function.ModLootFunctions;
import net.minecraft.server.MinecraftServer;

public class TradeSchool {

    public static void init() {
        ModLootFunctions.register();
        Constants.LOGGER.info("Trade School Mod initialized successfully");
    }

    public static void initWithoutRegistry() {
        Constants.LOGGER.info("Trade School Mod initialized successfully");
    }

    public static void onServerStarting(MinecraftServer server) {
        LootDistributionManager manager = LootDistributionManager.getInstance();
        manager.initialize(server);

        Constants.LOGGER.info("Loot distribution system initialized");
    }
}
