package cx.gid.minecraft.tradeschool;

public interface PlayerHintAccessor {
    boolean tradeschool$hasSeenHint(String professionType);
    void tradeschool$markHintSeen(String professionType);
}
