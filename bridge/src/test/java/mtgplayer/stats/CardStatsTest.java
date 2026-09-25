package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Auswertung je Deck (Task-3-Brief) ueber Partien ({@link MatchRecord}) und Kartendateien ({@link
 * CardStore}/{@link CardLog}), von Hand gebaut - kein echtes Forge-Spiel noetig, {@link ForgeBoot#init()}
 * nur fuer die Kartendatenbank ({@code imageKey}/{@code manaCost}/{@code cmc}).
 */
class CardStatsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} schreiben. */
    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    // --- Bauhelfer: nur die fuer CardStats relevanten Felder tragen echte Werte, der Rest bleibt 0/null/leer. ---

    private static MatchRecord.Seat seat(String name, String deck, boolean human) {
        return new MatchRecord.Seat(name, deck, human, null, false, null,
                null, 0, 0, List.of(), 0, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(),
                0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static MatchRecord match(String id, boolean counted, MatchRecord.Seat... seats) {
        return new MatchRecord(id, "2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z", 600_000, "live",
                5, 10, "AllOpponentsLost", false, counted, counted ? null : "Aufgegeben", List.of(seats));
    }

    private static CardLog.Card row(String name, Integer hand, Integer cast, Integer castTurn,
            Integer countered, Integer lost) {
        return new CardLog.Card(name, 1, hand, cast, castTurn, countered, lost, "battlefield");
    }

    private static CardLog.SeatCards seatCards(int seat, String deck, CardLog.Card... cards) {
        return new CardLog.SeatCards(seat, deck, List.of(cards));
    }

    private static CardLog cardLog(String id, CardLog.SeatCards... seats) {
        return new CardLog(id, List.of(seats));
    }

    private static CardLog cardLog(String id, int seatIndex, String deck, CardLog.Card... cards) {
        return cardLog(id, seatCards(seatIndex, deck, cards));
    }

    private static CardStats.Card cardNamed(CardStats stats, String name) {
        return stats.cards().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseGet(() -> fail("keine Zeile fuer " + name + ": " + stats.cards()));
    }

    @Test
    void zaehltHandUndWirkungUeberMehrerePartien(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        MatchRecord m2 = match("m2", true, seat("Du", "Mein Deck", true));
        // m1: Cultivate auf der Hand, nicht gewirkt. m2: Cultivate gezogen und in eigenem Zug 3 gewirkt.
        store.write(cardLog("m1", 0, "Mein Deck", row("Cultivate", 1, null, null, null, null)));
        store.write(cardLog("m2", 0, "Mein Deck", row("Cultivate", 1, 1, 3, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1, m2), store);

        assertEquals(2, stats.games());
        assertEquals(2, stats.withCardData());
        CardStats.Card cultivate = cardNamed(stats, "Cultivate");
        assertEquals(2, cultivate.handGames(), "in beiden Partien auf der Hand");
        assertEquals(1, cultivate.castGames(), "nur in m2 gewirkt");
        assertEquals(3.0, cultivate.avgCastTurn(), "einziger Wirk-Zug ist 3");
    }

    @Test
    void ungewertetePartieZaehltNichtInGamesNochInDieKarten(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord counted = match("m1", true, seat("Du", "Mein Deck", true));
        MatchRecord notCounted = match("m2", false, seat("Du", "Mein Deck", true));
        store.write(cardLog("m1", 0, "Mein Deck", row("Cultivate", 1, 1, 2, null, null)));
        store.write(cardLog("m2", 0, "Mein Deck", row("Sol Ring", 1, 1, 1, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(counted, notCounted), store);

        assertEquals(1, stats.games());
        assertEquals(1, stats.withCardData());
        assertTrue(stats.cards().stream().noneMatch(c -> c.name().equals("Sol Ring")),
                "die Karte der ungewerteten Partie darf nicht auftauchen");
    }

    @Test
    void fehlendeKartendateiSenktNurWithCardData(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        MatchRecord m2 = match("m2", true, seat("Du", "Mein Deck", true)); // keine Kartendatei geschrieben
        store.write(cardLog("m1", 0, "Mein Deck", row("Cultivate", 1, 1, 2, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1, m2), store);

        assertEquals(2, stats.games(), "beide gewerteten Partien zaehlen");
        assertEquals(1, stats.withCardData(), "nur m1 hat eine lesbare Kartendatei");
    }

    @Test
    void stuckZaehltHandOhneWirkungNieGezogenZaehltFehlendeZeile(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        MatchRecord m2 = match("m2", true, seat("Du", "Mein Deck", true));
        // m1: Sol Ring auf der Hand, nie gewirkt. m2: Sol Ring gar nicht gezogen (keine Zeile).
        store.write(cardLog("m1", 0, "Mein Deck", row("Sol Ring", 1, null, null, null, null)));
        store.write(cardLog("m2", 0, "Mein Deck", row("Cultivate", 1, 1, 3, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1, m2), store);

        CardStats.Card solRing = cardNamed(stats, "Sol Ring");
        assertEquals(1, solRing.handGames());
        assertEquals(0, solRing.castGames());
        assertEquals(1, solRing.stuckGames(), "in m1 auf der Hand, nie gewirkt");
        assertEquals(1, solRing.neverDrawnGames(), "in m2 keine Zeile - nie gezogen");
    }

    @Test
    void grundlaenderTauchenNichtInDenKartenAuf(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        store.write(cardLog("m1", 0, "Mein Deck",
                row("Forest", 1, null, null, null, null),
                row("Sol Ring", 1, 1, 1, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1), store);

        assertTrue(stats.cards().stream().noneMatch(c -> c.name().equals("Forest")),
                "Grundland faellt raus");
        assertTrue(stats.cards().stream().anyMatch(c -> c.name().equals("Sol Ring")));
    }

    @Test
    void unterMinGamesIstEnoughFalschKartenBleibenTrotzdem(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        List<MatchRecord> matches = new ArrayList<>();
        for (int i = 0; i < CardStats.MIN_GAMES - 1; i++) {
            String id = "m" + i;
            matches.add(match(id, true, seat("Du", "Mein Deck", true)));
            store.write(cardLog(id, 0, "Mein Deck", row("Sol Ring", 1, 1, 1, null, null)));
        }

        CardStats stats = CardStats.of("Mein Deck", matches, store);

        assertEquals(CardStats.MIN_GAMES - 1, stats.withCardData());
        assertFalse(stats.enough());
        assertFalse(stats.cards().isEmpty(), "Karten kommen trotz zu weniger Partien mit");
    }

    @Test
    void abMinGamesIstEnoughWahr(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        List<MatchRecord> matches = new ArrayList<>();
        for (int i = 0; i < CardStats.MIN_GAMES; i++) {
            String id = "m" + i;
            matches.add(match(id, true, seat("Du", "Mein Deck", true)));
            store.write(cardLog(id, 0, "Mein Deck", row("Sol Ring", 1, 1, 1, null, null)));
        }

        CardStats stats = CardStats.of("Mein Deck", matches, store);

        assertEquals(CardStats.MIN_GAMES, stats.withCardData());
        assertTrue(stats.enough());
    }

    @Test
    void findetAltenDecknamenMitDoppelUnterstrichUeberDenSchraegstrich(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        // Forge ersetzt beim Speichern jedes "/" durch "_" - ein Deckname mit "//" (Partner-Kommandeure)
        // steht in aelteren Datensaetzen deshalb mit "__" (siehe web/src/matchStats.ts, deckKey).
        MatchRecord m1 = match("m1", true, seat("Du", "Fblthp __ Toothy", true));
        store.write(cardLog("m1", 0, "Fblthp __ Toothy", row("Sol Ring", 1, 1, 1, null, null)));

        CardStats stats = CardStats.of("Fblthp // Toothy", List.of(m1), store);

        assertEquals(1, stats.games());
        assertEquals(1, stats.withCardData());
        assertFalse(stats.cards().isEmpty());
    }

    @Test
    void spiegelpartieZaehltEinmalUndNimmtDenMenschlichenSitz(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        // Sparring-Spiegel: beide Sitze spielen dasselbe Deck, Sitz 0 ist die KI, Sitz 1 der Mensch -
        // dieselbe Regel wie pickSeat im Client (web/src/matchStats.ts): der menschliche Sitz gewinnt.
        MatchRecord m1 = match("m1", true,
                seat("KI", "Mirror Deck", false),
                seat("Du", "Mirror Deck", true));
        store.write(cardLog("m1",
                seatCards(0, "Mirror Deck", row("AI-Karte", 1, null, null, null, null)),
                seatCards(1, "Mirror Deck", row("Human-Karte", 1, 1, 2, null, null))));

        CardStats stats = CardStats.of("Mirror Deck", List.of(m1), store);

        assertEquals(1, stats.games(), "eine Partie, kein Doppelzaehler");
        assertTrue(stats.cards().stream().anyMatch(c -> c.name().equals("Human-Karte")));
        assertTrue(stats.cards().stream().noneMatch(c -> c.name().equals("AI-Karte")),
                "der KI-Sitz zaehlt nicht mit, der menschliche gewinnt");
    }

    @Test
    void unbekannteKarteBleibtAlsZeileMitLeerenFeldern(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        store.write(cardLog("m1", 0, "Mein Deck",
                row("Ganz Unbekannte Karte Xyzzy 42", 1, 1, 1, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1), store);

        CardStats.Card unknown = cardNamed(stats, "Ganz Unbekannte Karte Xyzzy 42");
        assertNull(unknown.imageKey());
        assertNull(unknown.manaCost());
        assertNull(unknown.cmc());
    }

    @Test
    void sortiertNachStuckGamesAbsteigendDannHandGamesDannName(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        MatchRecord m1 = match("m1", true, seat("Du", "Mein Deck", true));
        MatchRecord m2 = match("m2", true, seat("Du", "Mein Deck", true));
        MatchRecord m3 = match("m3", true, seat("Du", "Mein Deck", true));
        // B-Karte: zweimal auf der Hand, nie gewirkt -> stuckGames 2.
        store.write(cardLog("m1", 0, "Mein Deck",
                row("B-Karte", 1, null, null, null, null),
                row("A-Karte", 1, 1, 1, null, null)));
        // C-Karte: einmal auf der Hand, nie gewirkt -> stuckGames 1.
        store.write(cardLog("m2", 0, "Mein Deck",
                row("B-Karte", 1, null, null, null, null),
                row("C-Karte", 1, null, null, null, null)));
        // A-Karte: immer gewirkt -> stuckGames 0.
        store.write(cardLog("m3", 0, "Mein Deck", row("A-Karte", 1, 1, 1, null, null)));

        CardStats stats = CardStats.of("Mein Deck", List.of(m1, m2, m3), store);

        List<String> names = stats.cards().stream().map(CardStats.Card::name).toList();
        assertEquals(List.of("B-Karte", "C-Karte", "A-Karte"), names);
    }
}
