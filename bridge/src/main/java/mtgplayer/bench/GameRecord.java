package mtgplayer.bench;

/** Ergebnis eines einzelnen Bench-Spiels (Sitz A gegen Sitz B). {@code turnCapped}: das Spiel endete am
 *  Zugdeckel (siehe AiMatch.Result#turnCapped) - unterscheidet das von einem Forge-eigenen Unentschieden
 *  mit demselben {@code reason} ("Draw"). */
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */,
                          String reason, int turns, long millis, boolean turnCapped) {
}
