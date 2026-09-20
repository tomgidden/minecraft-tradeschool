package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

///  How close a player must be before a villager reacts to them.
public class InteractionSection
{
  @Comment({"Radius in blocks within which a villager previews a held item and comments", "on it. 0 disables previews."})
  public double previewRadius = 0.0;

  @Comment("Radius within which the one-off \"this villager can be taught\" hint is offered.")
  public double hintRadius = 0.0;
}
