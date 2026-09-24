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
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Hintergrundlauf mit einem Attrappen-{@link GameRunner} - kein echtes Spiel, kein Kindprozess:
 * gelieferte Datensaetze landen im {@link MatchStore}, der Fortschritt kommt in der richtigen
 * Reihenfolge, ein scheiternder Runner beendet den Lauf nicht, {@code cancel()} stoppt nach der
 * laufenden Partie und ein zweiter {@code start()} wirft.
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

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void cancelBeendetNachDerLaufendenPartie(@TempDir Path dir) throws Exception {
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

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void zweiterStartWirftSolangeEinLaufAktivIst(@TempDir Path dir) throws Exception {
        DeckStore decks = storeWith(dir.resolve("decks"), "Mein Deck", "Gegner A", "Gegner B");
        MatchStore matches = new MatchStore(dir.resolve("matches.json"));
        CountDownLatch inGame = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SparringRun run = new SparringRun(decks, matches, (a, opponent, seed) -> {
            inGame.countDown();
            try {
                assertTrue(release.await(AWAIT_SECONDS, TimeUnit.SECONDS), "Freigabe");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
