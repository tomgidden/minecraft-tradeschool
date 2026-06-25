package cx.gid.minecraft.tradeschool.neoforge.gametest;

import com.mojang.serialization.MapCodec;
import cx.gid.minecraft.tradeschool.gametest.LootDistributionTestLogic;
import net.minecraft.gametest.framework.GameTestEnvironments;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

public class LootDistributionTest {

    @SubscribeEvent
    public static void registerTests(RegisterGameTestsEvent event) {
        var envHolder = event.registerEnvironment(
            Identifier.fromNamespaceAndPath("tradeschool", "default"),
            new TestEnvironmentDefinition.AllOf()
        );

        TestData<net.minecraft.core.Holder<TestEnvironmentDefinition<?>>> testData = new TestData<>(
            envHolder,
            Identifier.parse(LootDistributionTestLogic.STRUCTURE),
            LootDistributionTestLogic.MAX_TICKS,
            0,
            true
        );

        event.registerTest(
            Identifier.fromNamespaceAndPath("tradeschool", "loot_distribution_report"),
            new GameTestInstance(testData) {
                @Override
                public void run(GameTestHelper helper) {
                    LootDistributionTestLogic.run(helper);
                }

                @Override
                public MapCodec<? extends GameTestInstance> codec() {
                    throw new UnsupportedOperationException("inline test instance has no codec");
                }

                @Override
                protected MutableComponent typeDescription() {
                    return Component.literal("tradeschool:loot_distribution_report");
                }
            }
        );
    }
}
