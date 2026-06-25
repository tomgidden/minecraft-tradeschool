package cx.gid.minecraft.tradeschool.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class LootDistributionTest {

    @GameTest(structure = LootDistributionTestLogic.STRUCTURE, maxTicks = LootDistributionTestLogic.MAX_TICKS)
    public void lootDistributionReport(GameTestHelper helper) {
        LootDistributionTestLogic.run(helper);
    }
}
