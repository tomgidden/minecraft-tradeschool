package cx.gid.minecraft.tradeschool;

import cx.gid.minecraft.common.text.Messages;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/// This mod's message keys, and the [Messages] instance that resolves them.
///
/// The resolution machinery — server-side translation with a per-player fallback — lives in
/// cx.gid.minecraft.common so the other mods in this family can share it. Only the keys are
/// mod-specific, so only they live here.
public final class TradeSchoolMessages {

    // Refusals — why a villager will not learn from an offered item.
    public static final String REFUSAL_NETHERITE          = "tradeschool.refusal.netherite";
    public static final String REFUSAL_SHIELD_TOO_JUNIOR  = "tradeschool.refusal.shield_too_junior";
    public static final String REFUSAL_COPYRIGHTED        = "tradeschool.refusal.copyrighted";
    public static final String REFUSAL_ALREADY_SELLS      = "tradeschool.refusal.already_sells";
    public static final String REFUSAL_NEVER_LEARNABLE    = "tradeschool.refusal.never_learnable";
    public static final String REFUSAL_TOO_JUNIOR         = "tradeschool.refusal.too_junior";
    public static final String REFUSAL_LESSON_USED        = "tradeschool.refusal.lesson_used";
    public static final String REFUSAL_WORN_ARMOR         = "tradeschool.refusal.worn_armor";

    // Profession hints — the one-off "this villager can be taught" nudge.
    public static final String HINT_LIBRARIAN   = "tradeschool.hint.librarian";
    public static final String HINT_WEAPONSMITH = "tradeschool.hint.weaponsmith";
    public static final String HINT_TOOLSMITH   = "tradeschool.hint.toolsmith";
    public static final String HINT_ARMORER     = "tradeschool.hint.armorer";
    public static final String HINT_FLETCHER    = "tradeschool.hint.fletcher";

    // Previews — what would happen if the held item were traded.
    public static final String PREVIEW_NETHERITE     = "tradeschool.preview.netherite";
    public static final String PREVIEW_FULL_LEARN    = "tradeschool.preview.full_learn";
    public static final String PREVIEW_PARTIAL_LEARN = "tradeschool.preview.partial_learn";

    // Outcomes.
    // Shown when a teach offer is selected in the trade UI, where a tooltip cannot be.
    public static final String TRADE_FULL_LEARN    = "tradeschool.trade.full_learn";
    public static final String TRADE_PARTIAL_LEARN = "tradeschool.trade.partial_learn";

    public static final String TAUGHT_LEARNED  = "tradeschool.taught.learned";
    public static final String TAUGHT_LESSON_USED = "tradeschool.taught.lesson_used";
    public static final String TAUGHT_COPYRIGHTED = "tradeschool.taught.copyrighted";

    public static final String TAUGHT_TITLE     = "tradeschool.taught.title";
    public static final String TAUGHT_SUBTITLE  = "tradeschool.taught.subtitle";
    public static final String REFUND           = "tradeschool.refund";

    private static final Messages MESSAGES =
        new Messages(Constants.MOD_ID).withLogger(Constants::debug);

    private TradeSchoolMessages() {}

        ///  Resolves for a specific player, in their own language.
    public static MutableComponent of(ServerPlayer player, String key, Object... args) {
        return MESSAGES.of(player, key, args);
    }

        ///  Resolves in English, for the console or an unknown recipient.
    public static MutableComponent ofDefault(String key, Object... args) {
        return MESSAGES.ofDefault(key, args);
    }
}
