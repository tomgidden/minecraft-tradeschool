package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;
import java.util.List;

///  Which loot tables the mod touches.
public class LootSection
{
  @Comment({"Loot table prefixes treated as *found* loot.", "Block drops, mob drops and spawn equipment are excluded by omission: those", "are not discovered treasure, and cursing them would let players farm the", "curse rather than find it."})
  public List<String> foundLootPrefixes = List.of();

  @Comment({"How likely a brushed block yields enchanted gear, as a multiple of that", "structure's book_chance in loot_distribution.json.", "Brushing can only ever produce ONE item, so this mod's gear, then its books,", "then vanilla's own loot are tried in turn and the first to answer wins.", "0.5 means gear is offered at half the rate books are. 0 disables gear from", "brushing; 1 offers it as often as books."})
  public float archaeologyGearChanceMultiplier = 0.0f;

  public void applyDefaults()
  {
    // An explicitly empty list means "touch nothing", which is a real choice; only a
    // missing list needs replacing.
    if(foundLootPrefixes == null) foundLootPrefixes = List.of();
  }
}
