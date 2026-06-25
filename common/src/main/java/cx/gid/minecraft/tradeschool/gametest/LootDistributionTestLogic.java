package cx.gid.minecraft.tradeschool.gametest;

import cx.gid.minecraft.tradeschool.loot.LootDistributionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class LootDistributionTestLogic {

    public static final String STRUCTURE = "minecraft:empty";
    public static final int MAX_TICKS = 6000;
    private static final int ROLLS = 10_000;

    public static void run(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = server.overworld();

        boolean enabled = LootDistributionManager.getInstance().getConfig().global.enabled;
        String mode = enabled ? "TRADESCHOOL" : "VANILLA";

        List<String> structureIds = new ArrayList<>(
            LootDistributionManager.getInstance().getConfig().structures.keySet()
        );
        Collections.sort(structureIds);

        log(helper, "");
        log(helper, "╔══════════════════════════════════════════════════════════════════╗");
        log(helper, String.format("║  LOOT DISTRIBUTION REPORT — %-36s║", mode));
        log(helper, String.format("║  %d rolls per structure loot table%-31s║", ROLLS, ""));
        log(helper, "╚══════════════════════════════════════════════════════════════════╝");

        for (String structureId : structureIds) {
            Map<String, Integer> counts = rollLootTable(server, level, structureId, ROLLS);

            if (counts == null) {
                log(helper, "");
                log(helper, "[SKIP] " + structureId + " — table not found");
                continue;
            }

            log(helper, "");
            log(helper, "── " + structureId);

            if (counts.isEmpty()) {
                log(helper, "    (no enchanted books in " + ROLLS + " rolls)");
                continue;
            }

            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());

            int totalBooks = counts.values().stream().mapToInt(Integer::intValue).sum();
            log(helper, String.format("    Total enchanted books: %d / %d rolls (%.1f%%)",
                totalBooks, ROLLS, 100.0 * totalBooks / ROLLS));
            log(helper, String.format("    %-52s %6s  %6s", "Enchantment", "Count", "Rate"));
            log(helper, String.format("    %-52s %6s  %6s", "-".repeat(52), "------", "------"));

            for (Map.Entry<String, Integer> entry : sorted) {
                log(helper, String.format("    %-52s %6d  %5.2f%%",
                    entry.getKey(), entry.getValue(), 100.0 * entry.getValue() / ROLLS));
            }
        }

        log(helper, "");
        log(helper, "── END OF REPORT");

        log(helper, "");
        log(helper, "[CSV] structure,enchantment,count,rate_pct");
        for (String structureId : structureIds) {
            Map<String, Integer> counts = rollLootTable(server, level, structureId, ROLLS);
            if (counts == null || counts.isEmpty()) continue;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                log(helper, String.format("[CSV] %s,%s,%d,%.4f",
                    structureId, entry.getKey(),
                    entry.getValue(), 100.0 * entry.getValue() / ROLLS));
            }
        }

        helper.succeed();
    }

    private static Map<String, Integer> rollLootTable(
        MinecraftServer server,
        ServerLevel level,
        String structureId,
        int rolls
    ) {
        ResourceKey<LootTable> tableKey = ResourceKey.create(
            net.minecraft.core.registries.Registries.LOOT_TABLE,
            Identifier.parse(structureId)
        );

        LootTable table = server.reloadableRegistries().getLootTable(tableKey);
        if (table == LootTable.EMPTY) return null;

        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(BlockPos.ZERO))
            .create(LootContextParamSets.CHEST);

        Map<String, Integer> counts = new TreeMap<>();

        for (int i = 0; i < rolls; i++) {
            List<ItemStack> items = new ArrayList<>();
            table.getRandomItems(params, stack -> items.add(stack));

            for (ItemStack stack : items) {
                if (stack.getItem() != Items.ENCHANTED_BOOK) continue;

                ItemEnchantments stored = stack.getOrDefault(
                    DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);

                stored.keySet().forEach(enchHolder -> {
                    int enchLevel = stored.getLevel(enchHolder);
                    String enchId = enchHolder.unwrapKey()
                        .map(k -> k.identifier().toString())
                        .orElse("unknown");
                    counts.merge(enchId + " lvl " + enchLevel, 1, Integer::sum);
                });
            }
        }

        return counts;
    }

    private static void log(GameTestHelper helper, String message) {
        helper.getLevel().getServer().sendSystemMessage(
            net.minecraft.network.chat.Component.literal("[LootReport] " + message)
        );
    }
}
