package cx.gid.minecraft.tradeschool.neoforge.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("tradeschool_gametest")
public class TradeSchoolGameTestMod {

    public TradeSchoolGameTestMod(IEventBus modEventBus) {
        modEventBus.addListener(LootDistributionTest::registerTests);
    }
}
