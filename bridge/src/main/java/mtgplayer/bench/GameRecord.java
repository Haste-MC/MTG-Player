package mtgplayer.bench;

/** Ergebnis eines einzelnen Bench-Spiels (Sitz A gegen Sitz B). {@code turnCapped}: das Spiel endete am
 *  Zugdeckel (siehe AiMatch.Result#turnCapped) - unterscheidet das von einem Forge-eigenen Unentschieden
 *  mit demselben {@code reason} ("Draw"). {@code fewSpells}: Sitze ("", "A", "B", "AB"; mit {@code --teams}
 *  "A1", "A2", "B1", "B2" aneinandergehaengt, etwa "A1B2"), die "nichts getan" haben - mindestens 5 Laender gespielt, hoechstens 2 Zauber gewirkt (siehe {@link ActivityCounter}). */
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */,
                          String reason, int turns, long millis, boolean turnCapped, int simErrors, String fewSpells) {
    /** Ohne simErrors und fewSpells (Tests). */
    public GameRecord(int index, long seed, String firstSeat, String winner, String reason, int turns, long millis, boolean turnCapped) {
        this(index, seed, firstSeat, winner, reason, turns, millis, turnCapped, 0, "");
    }

    /** Kopie mit gezaehlten Simulationsfehlern (Forge-Zeilen "Couldn't map"/"Game copy error" auf stdout des
     *  Kindprozesses): die Sim-KI hat dann still versagt - Forge faengt die Ausnahme und die KI spielt nichts. */
    public GameRecord withSimErrors(int n) {
        return new GameRecord(index, seed, firstSeat, winner, reason, turns, millis, turnCapped, n, fewSpells);
    }

    /** Kopie mit den Nichtstun-Sitzen aus {@link ActivityCounter#fewSpells()}. */
    public GameRecord withFewSpells(String seats) {
        return new GameRecord(index, seed, firstSeat, winner, reason, turns, millis, turnCapped, simErrors, seats);
    }

    /** Wie viele Sitze des Teams (oder Sitzes) {@code team} ("A"/"B") haben in diesem Spiel "nichts getan"?
     *  Gezaehlt werden Sitze, nicht Treffer: "A1A2" ergibt fuer "A" zwei, "AB" ergibt fuer "A" eins. Die
     *  Sitznamen beginnen mit dem Teambuchstaben, Ziffern und das andere Team stoeren nicht.
     *  (null-sicher: aeltere JSON-Berichte ohne das Feld) */
    public int fewSpellsSitze(String team) {
        if (fewSpells == null || team.isEmpty()) {
            return 0;
        }
        return (int) fewSpells.chars().filter(c -> c == team.charAt(0)).count();
    }

    /** Hat dieser Sitz (bzw. mindestens ein Sitz dieses Teams) in diesem Spiel "nichts getan"? */
    public boolean fewSpells(String seat) {
        return fewSpellsSitze(seat) > 0;
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
