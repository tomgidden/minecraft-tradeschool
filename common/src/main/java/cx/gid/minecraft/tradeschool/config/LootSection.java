package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

import java.util.List;

///  Which loot tables the mod touches.
public class LootSection {

    @Comment({"Loot table prefixes treated as *found* loot.",
              "Block drops, mob drops and spawn equipment are excluded by omission: those",
              "are not discovered treasure, and cursing them would let players farm the",
              "curse rather than find it."})
    public List<String> foundLootPrefixes = List.of();

    public void applyDefaults() {
        // An explicitly empty list means "touch nothing", which is a real choice; only a
        // missing list needs replacing.
        if (foundLootPrefixes == null) foundLootPrefixes = List.of();
    }
}
