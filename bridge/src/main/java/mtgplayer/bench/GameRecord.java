package mtgplayer.bench;

/** Ergebnis eines einzelnen Bench-Spiels (Sitz A gegen Sitz B). {@code turnCapped}: das Spiel endete am
 *  Zugdeckel (siehe AiMatch.Result#turnCapped) - unterscheidet das von einem Forge-eigenen Unentschieden
 *  mit demselben {@code reason} ("Draw"). */
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */,
                          String reason, int turns, long millis, boolean turnCapped) {
    public static final String CRASH_PREFIX = "Crash: ";

    /** Forge hat das Spiel mit einer Ausnahme abgebrochen (reason beginnt mit {@link #CRASH_PREFIX}). */
    public boolean crashed() {
        return reason != null && reason.startsWith(CRASH_PREFIX);
    }

    public static GameRecord crash(int index, long seed, String firstSeat, Throwable e, long millis) {
        String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        return new GameRecord(index, seed, firstSeat, null, CRASH_PREFIX + msg, 0, millis, false);
    }
}
