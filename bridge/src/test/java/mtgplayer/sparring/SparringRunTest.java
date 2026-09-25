package mtgplayer.sparring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Messages;
import mtgplayer.stats.CardLog;
import mtgplayer.stats.CardStore;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Hintergrundlauf mit einem Attrappen-{@link GameRunner} - kein echtes Spiel, kein Kindprozess:
 * gelieferte Datensaetze landen im {@link MatchStore}, der Fortschritt kommt in der richtigen
 * Reihenfolge, ein scheiternder Runner beendet den Lauf nicht, {@code cancel()} beendet die LAUFENDE
 * Partie (ueber {@link GameRunner#cancel()}) und ein zweiter {@code start()} wirft.
 */
class SparringRunTest {

    private static final int AWAIT_SECONDS = 20;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Wie SparringOpponentsTest: gespeicherte Decks, hier alle im selben Bracket. */
    private static DeckStore storeWith(Path dir, String... names) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        d.getTags().clear();
        d.getTags().add(DeckStore.BRACKET_TAG + "3");
        for (String name : names) {
            store.save(name, d);
        }
        return store;
    }

    private static final AtomicInteger IDS = new AtomicInteger();

    /** Ein magerer, aber gueltiger Datensatz mit Quelle "sparring" - mehr braucht der Lauf nicht. */
    private static MatchRecord record(String opponent) {
        return new MatchRecord("m" + IDS.incrementAndGet(), "2026-09-24T10:00:00Z", "2026-09-24T10:12:00Z",
                720_000, "sparring", 5, 12, "AllOpponentsLost", false, true, null,
                List.of(seat("Mein Deck", true), seat(opponent, false)));
    }

    private static MatchRecord.Seat seat(String name, boolean winner) {
        return new MatchRecord.Seat(name, name, false, new MatchRecord.Ai("standard", "Default"), winner, null,
                null, 0, 0, List.of(), 0, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(),
                0, 0, null, 0, 0, 0, 40, 0, 0, 0, 0);
    }

    private static SparringArgs args(int games) {
        return new SparringArgs("Mein Deck", games, AiConfig.DEFAULT, 5, 60);
    }

    private static void awaitDone(SparringRun run) throws InterruptedException {
        long end = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
        while (run.running() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertFalse(run.running(), "Lauf ist nach " + AWAIT_SECONDS + " s immer noch aktiv");
    }

    private static List<Messages.SparringProgress> progress(List<Object> out) {
        synchronized (out) {
            return out.stream().filter(Messages.SparringProgress.class::isInstance)
                    .map(Messages.SparringProgress.class::cast).toList();
        }
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zweiPartienLandenImSpeicherUndDerFortschrittKommtDerReiheNach(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        SparringRun run = new SparringRun(decks, matches, (a, opponent, seed) -> record(opponent), out::add);

        run.start(args(2));
        awaitDone(run);

        assertEquals(2, matches.all().size(), "beide Datensaetze im Speicher");
        assertTrue(matches.all().stream().allMatch(m -> "sparring".equals(m.source())), matches.all().toString());
        List<Messages.SparringProgress> ps = progress(out);
        assertEquals(List.of(0, 1, 2), ps.stream().map(Messages.SparringProgress::done).toList(), ps.toString());
        assertTrue(ps.stream().allMatch(p -> p.total() == 2), ps.toString());
        assertEquals(List.of(true, true, false), ps.stream().map(Messages.SparringProgress::running).toList(), ps.toString());
        assertNotNull(ps.get(0).current(), "der erste Fortschritt nennt den Gegner der ersten Partie");
        assertNull(ps.get(2).current(), "der letzte Fortschritt nennt keinen laufenden Gegner mehr");
        assertTrue(ps.stream().allMatch(p -> p.errors().isEmpty()), ps.toString());
        assertEquals(2, out.stream().filter(MatchRecord.class::isInstance).count(),
                "jeder gespeicherte Datensatz wird gemeldet");
    }

    /** Befund 4: {@link MatchStore#add} kappt bei {@link MatchStore#MAX} - die Kartendatei der dabei
     *  herausfallenden, aeltesten Partie muss SparringRun (genau wie {@code Bridge.startGame}) selbst
     *  mitloeschen, sonst bleibt sie fuer immer verwaist liegen. Der 5-Parameter-Konstruktor gibt dafuer
     *  einen eigenen {@link CardStore} mit, statt {@link CardStore#standard()} zu treffen. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void gekapptePartieLoeschtIhreKartendateiMit(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        CardStore cards = new CardStore(dir.resolve("cards"));
        for (int i = 0; i < MatchStore.MAX; i++) {
            matches.add(record("Gegner A"));
        }
        String oldestId = matches.all().get(0).id();
        cards.write(new CardLog(oldestId, List.of()));
        assertTrue(cards.read(oldestId).isPresent(), "Vorbedingung: die Kartendatei der aeltesten Partie existiert");

        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        SparringRun run = new SparringRun(decks, matches, (a, opponent, seed) -> record(opponent), cards, out::add);

        run.start(args(1));
        awaitDone(run);

        assertEquals(MatchStore.MAX, matches.all().size(), "der Deckel bleibt bei MAX");
        assertTrue(cards.read(oldestId).isEmpty(), "die Kartendatei der gekappten, aeltesten Partie ist mitgeloescht");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void eineGescheitertePartieWirdVermerktUndDerLaufGehtWeiter(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        SparringRun run = new SparringRun(decks, matches, (a, opponent, seed) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("Kindprozess: exit=1");
            }
            return record(opponent);
        }, out::add);

        run.start(args(2));
        awaitDone(run);

        assertEquals(2, calls.get(), "die zweite Partie wurde trotz des Fehlers gespielt");
        assertEquals(1, matches.all().size(), "nur die gelungene Partie ist gespeichert");
        List<Messages.SparringProgress> ps = progress(out);
        Messages.SparringProgress last = ps.get(ps.size() - 1);
        assertEquals(2, last.done(), "die gescheiterte Partie zaehlt mit");
        assertFalse(last.running());
        assertEquals(1, last.errors().size(), last.errors().toString());
        assertTrue(last.errors().get(0).contains("Kindprozess: exit=1"), last.errors().toString());
    }

    /** Eine Partie, die trotz {@code cancel()} noch zu Ende kommt, behaelt ihren Datensatz - danach
     *  tritt keine weitere mehr an. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void cancelVerhindertWeiterePartien(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        SparringRun[] holder = new SparringRun[1];
        AtomicInteger calls = new AtomicInteger();
        holder[0] = new SparringRun(decks, matches, (a, opponent, seed) -> {
            calls.incrementAndGet();
            holder[0].cancel();          // Abbruch mitten in der ersten Partie
            return record(opponent);
        }, out::add);

        holder[0].start(args(3));
        awaitDone(holder[0]);

        assertEquals(1, calls.get(), "nach dem Abbruch wurde keine weitere Partie gestartet");
        assertEquals(1, matches.all().size(), "die laufende Partie wird noch gespeichert");
        List<Messages.SparringProgress> ps = progress(out);
        Messages.SparringProgress last = ps.get(ps.size() - 1);
        assertEquals(1, last.done());
        assertEquals(3, last.total());
        assertFalse(last.running());
        assertNull(last.current());
    }

    /**
     * Attrappe eines Kindprozess-Runners: bleibt in der Partie haengen, bis {@link #cancel()} kommt,
     * und scheitert dann so, wie ein getoetetes Kind scheitert (Exit 143 statt Ergebnis).
     */
    private static final class BlockingRunner implements GameRunner {

        private final CountDownLatch inGame = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean cancelled;

        @Override
        public MatchRecord play(SparringArgs args, String opponent, long seed) {
            calls.incrementAndGet();
            inGame.countDown();
            try {
                if (!released.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Attrappe: Partie wurde nie freigegeben");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Attrappe: unterbrochen", e);
            }
            if (cancelled) {
                throw new IllegalStateException("Kindprozess: exit=143, stderr: (leer)");
            }
            return record(opponent);
        }

        @Override
        public void cancel() {
            cancelled = true;
            released.countDown();
        }

        void awaitInGame() throws InterruptedException {
            assertTrue(inGame.await(AWAIT_SECONDS, TimeUnit.SECONDS), "erste Partie laeuft");
        }
    }

    /** Spec, Abschnitt 3: "Abbruch: laufendes Kind wird beendet" - der Knopf darf nicht erst die naechste
     *  Partie verhindern, eine laufende dauert Minuten. Die abgebrochene Partie zaehlt als Fehler. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void cancelBeendetDieLaufendePartie(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        List<Object> out = Collections.synchronizedList(new ArrayList<>());
        BlockingRunner runner = new BlockingRunner();
        SparringRun run = new SparringRun(decks, matches, runner, out::add);

        run.start(args(3));
        runner.awaitInGame();
        run.cancel();
        awaitDone(run);

        assertEquals(1, runner.calls.get(), "keine weitere Partie nach dem Abbruch");
        assertEquals(0, matches.all().size(), "die abgebrochene Partie liefert keinen Datensatz");
        List<Messages.SparringProgress> ps = progress(out);
        Messages.SparringProgress last = ps.get(ps.size() - 1);
        assertEquals(1, last.done(), "die abgebrochene Partie zaehlt mit");
        assertEquals(3, last.total());
        assertFalse(last.running());
        assertNull(last.current());
        assertEquals(1, last.errors().size(), last.errors().toString());
        assertTrue(last.errors().get(0).endsWith(": abgebrochen"),
                "Fehlertext nennt den Gegner und 'abgebrochen': " + last.errors());
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zweiterStartWirftSolangeEinLaufAktivIst(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        CountDownLatch inGame = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SparringRun run = new SparringRun(decks, matches, (a, opponent, seed) -> {
            inGame.countDown();
            // Kein Datensatz, wenn die Wartezeit anders endet als durch die Freigabe - sonst waere der
            // Lauf still fertig und der zweite start() truege zu Unrecht durch.
            try {
                if (!release.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Attrappe: Freigabe blieb aus");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Attrappe: unterbrochen", e);
            }
            return record(opponent);
        }, o -> { });

        run.start(args(1));
        assertTrue(inGame.await(AWAIT_SECONDS, TimeUnit.SECONDS), "erste Partie laeuft");
        assertTrue(run.running());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> run.start(args(1)));
        assertTrue(e.getMessage().contains("Sparring läuft noch"), e.getMessage());

        release.countDown();
        awaitDone(run);
        assertEquals(1, matches.all().size());
    }
}
