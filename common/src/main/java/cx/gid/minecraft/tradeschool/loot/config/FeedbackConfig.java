package cx.gid.minecraft.tradeschool.loot.config;

import cx.gid.minecraft.common.config.Comment;

/**
 * Which channels the mod uses to tell the player what happened, and for how long.
 *
 * A server-side-only mod cannot add tooltips or custom UI, so everything it wants to say
 * has to go through chat, the action bar, or a title. Each suits a different kind of
 * message and each irritates a different player, so all three are switchable.
 */
public class FeedbackConfig {

    @Comment("Chat messages: previews, refusals, refunds. Persistent and scrollable.")
    public boolean chat = true;

    @Comment({"Action bar, above the hotbar. Unobtrusive but brief — it cannot be held on",
              "screen longer, so anything worth reading twice belongs in chat instead."})
    public boolean actionBar = false;

    @Comment("Centre-screen title on a successful lesson.")
    public boolean title = true;

    // Title timings, in ticks (20 per second). Sent with each title, so changes take
    // effect immediately without a restart.

    @Comment("Ticks the title spends fading in. 20 ticks = 1 second.")
    public int titleFadeInTicks = 5;

    @Comment("Ticks the title stays fully visible. Long enough to read two lines.")
    public int titleStayTicks = 80;

    @Comment("Ticks the title spends fading out.")
    public int titleFadeOutTicks = 20;

    public FeedbackConfig() {
        // Required for Gson deserialization
    }
}
