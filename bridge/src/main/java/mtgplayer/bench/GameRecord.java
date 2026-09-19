package mtgplayer.bench;

/** Ergebnis eines einzelnen Bench-Spiels (Sitz A gegen Sitz B). */
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */,
                          String reason, int turns, long millis) {
}
