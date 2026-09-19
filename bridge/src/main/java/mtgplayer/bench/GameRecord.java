package mtgplayer.bench;

/** Ergebnis eines einzelnen Bench-Spiels (Sitz A gegen Sitz B). {@code turnCapped}: das Spiel endete am
 *  Zugdeckel (siehe AiMatch.Result#turnCapped) - unterscheidet das von einem Forge-eigenen Unentschieden
 *  mit demselben {@code reason} ("Draw"). */
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */,
                          String reason, int turns, long millis, boolean turnCapped, int simErrors) {
    /** Ohne simErrors (in-process-Laeufe und Tests: dort wird Forges stdout nicht mitgelesen). */
    public GameRecord(int index, long seed, String firstSeat, String winner, String reason, int turns, long millis, boolean turnCapped) {
        this(index, seed, firstSeat, winner, reason, turns, millis, turnCapped, 0);
    }

    /** Kopie mit gezaehlten Simulationsfehlern (Forge-Zeilen "Couldn't map"/"Game copy error" auf stdout des
     *  Kindprozesses): die Sim-KI hat dann still versagt - Forge faengt die Ausnahme und die KI spielt nichts. */
    public GameRecord withSimErrors(int n) {
        return new GameRecord(index, seed, firstSeat, winner, reason, turns, millis, turnCapped, n);
    }

    /** Spiel lief durch, aber die Simulation war defekt - Ergebnis nicht aussagekraeftig. */
    public boolean simBroken() {
        return !crashed() && simErrors > 0;
    }
    public static final String CRASH_PREFIX = "Crash: ";

    /** Forge hat das Spiel mit einer Ausnahme abgebrochen (reason beginnt mit {@link #CRASH_PREFIX}). */
    public boolean crashed() {
        return reason != null && reason.startsWith(CRASH_PREFIX);
    }

    public static GameRecord crash(int index, long seed, String firstSeat, Throwable e, long millis) {
        return crash(index, seed, firstSeat, e, millis, 0);
    }

    /** @param lastTurn letzter Zug, der vor dem Absturz im Log stand (0 = unbekannt) */
    public static GameRecord crash(int index, long seed, String firstSeat, Throwable e, long millis, int lastTurn) {
        String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        if (msg.length() > 120) {
            msg = msg.substring(0, 120) + "…";
        }
        return new GameRecord(index, seed, firstSeat, null, CRASH_PREFIX + msg, lastTurn, millis, false);
    }
}
