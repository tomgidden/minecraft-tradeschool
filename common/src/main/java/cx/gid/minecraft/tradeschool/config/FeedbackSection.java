package cx.gid.minecraft.tradeschool.config;

import cx.gid.minecraft.common.config.Comment;

import java.util.List;

/// Which channels the mod uses to tell the player things.
///
/// A server-side mod cannot add tooltips or custom UI, so everything it has to say goes
/// through chat, the action bar, or a title. Each suits a different kind of message and
/// each annoys a different player, so all three are switchable.
public class FeedbackSection {

    @Comment("Chat messages: previews, refusals, refunds. Persistent and scrollable.")
    public boolean chat = false;

    @Comment({"Action bar, above the hotbar. Unobtrusive, but it cannot be held on screen",
              "long enough to read a sentence."})
    public boolean actionBar = false;

    @Comment("Centre-screen title on a successful lesson.")
    public boolean title = false;

    @Comment({"Title timing in ticks: fade in, hold, fade out. 20 ticks to the second.",
              "Stated as a whole because the three only make sense together."})
    public List<Integer> titleTiming = List.of(5, 80, 20);

        ///  Fade-in ticks, or a sensible value if the list is malformed.
    public int fadeIn()  { return timing(0, 5); }
        ///  Hold ticks.
    public int stay()    { return timing(1, 80); }
        ///  Fade-out ticks.
    public int fadeOut() { return timing(2, 20); }

    private int timing(int index, int fallback) {
        return titleTiming != null && titleTiming.size() > index
            ? titleTiming.get(index) : fallback;
    }
}
