package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Json;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sparring ueber das echte Protokoll (Spec §3): {@code sparringStart} ohne Gegner antwortet mit
 * {@code error}, ein zweiter Start waehrend eines Laufs mit "Sparring läuft noch", und ein Lauf
 * schickt Fortschritt sowie nach jeder Partie eine aktualisierte {@code matches}-Liste.
 *
 * <p>Eigener Port (18086) und ein eigenes Deck-Verzeichnis, damit dieser Test nicht mit den anderen
 * Bridge*Test-Klassen kollidiert. Der {@link mtgplayer.sparring.GameRunner} ist eine Attrappe: die
 * Unit-Tests starten ausdruecklich keine echten Spiel-Kindprozesse.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BridgeSparringTest {

    private static final int PORT = 18086;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private static final List<JsonNode> seenMatches = Collections.synchronizedList(new ArrayList<>());

    /** Haelt die Attrappe in der ersten Partie fest, bis der Test sie freigibt. */
    private static final CountDownLatch inGame = new CountDownLatch(1);
    private static final CountDownLatch release = new CountDownLatch(1);
    private static final AtomicInteger games = new AtomicInteger();

    @TempDir
    static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        DeckStore decks = new DeckStore(tmp.resolve("decks"));
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        for (String spec : new String[] { "Mein Deck=4", "Gegner=4", "Einsam=1" }) {
            String[] p = spec.split("=", 2);
            d.getTags().clear();
            d.getTags().add(DeckStore.BRACKET_TAG + p[1]);
            decks.save(p[0], d);
        }
        bridge = new Bridge(PORT, decks, Archidekt.standard(), new MatchStore(tmp.resolve("matches.json")),
                (args, opponent, seed) -> {
                    games.incrementAndGet();
                    inGame.countDown();
                    // Endet die Wartezeit anders als durch die Freigabe des Tests (Frist abgelaufen
                    // oder Unterbrechung), darf die Attrappe KEINEN Datensatz liefern: der Lauf waere
                    // dann still fertig, ein zweiter Start ginge durch, und der Test scheiterte
                    // zehn Sekunden spaeter an der falschen Stelle ("keine Nachricht 'error'").
                    try {
                        if (!release.await(60, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Attrappe: Freigabe blieb aus");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Attrappe: unterbrochen", e);
                    }
                    return record(opponent);
                });
        bridge.start();
        client = new WebSocketClient(new URI("ws://127.0.0.1:" + PORT)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { inbox.add(Json.parse(m)); }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { ex.printStackTrace(); }
        };
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "WebSocket-Verbindung");
    }

    @AfterAll
    static void stop() throws Exception {
        release.countDown();
        client.closeBlocking();
        bridge.stop();
    }

    private static MatchRecord record(String opponent) {
        MatchRecord.Seat seat = new MatchRecord.Seat("Mein Deck", "Mein Deck", false,
                new MatchRecord.Ai("standard", "Default"), true, null,
                null, 0, 0, List.of(), 0, null, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of(),
                0, 0, null, 0, 0, 0, 40, 0, 0, 0, 0);
        return new MatchRecord("sp-" + opponent, "2026-09-24T10:00:00Z", "2026-09-24T10:12:00Z", 720_000,
                "sparring", 5, 12, "AllOpponentsLost", false, true, null, List.of(seat));
    }

    private static JsonNode await(String type, Predicate<JsonNode> cond, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            // Mitschnitt fuer die Nachschau, wenn dieser Test einmal ueber die Zeit laeuft.
            String seen = n.toString();
            System.out.println("[inbox] " + (seen.length() > 200 ? seen.substring(0, 200) + "…" : seen));
            if ("matches".equals(n.path("type").asText())) {
                seenMatches.add(n);
            }
            if (type.equals(n.path("type").asText()) && cond.test(n)) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    private static void send(String json) {
        client.send(json);
    }

    /** Spec §2: ein Deck ohne Gegner im (erweiterten) Bracket startet keinen Lauf, sondern meldet den Grund. */
    @Test
    @Order(1)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void sparringOhneGegnerLiefertError() throws Exception {
        send("{\"type\":\"sparringStart\",\"deck\":\"Einsam\",\"games\":2}");
        JsonNode err = await("error", n -> n.path("text").asText().contains("keine Gegner"), 10);
        assertTrue(err.path("text").asText().contains("Bracket 1"), err.toString());
        assertEquals(0, games.get(), "es wurde keine Partie gestartet");
    }

    /** Spec §3: ein Lauf zur Zeit, Fortschritt, matches nach jeder Partie, Abbruch nach der laufenden Partie. */
    @Test
    @Order(2)
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void zweiterStartMeldetLaeuftNochUndCancelBeendetDenLauf() throws Exception {
        send("{\"type\":\"sparringStart\",\"deck\":\"Mein Deck\",\"games\":3,"
                + "\"ai\":{\"mode\":\"standard\",\"profile\":\"Default\"},\"timeout\":5,\"maxTurns\":60}");
        JsonNode first = await("sparringProgress", n -> true, 10);
        assertEquals(0, first.get("done").asInt(), first.toString());
        assertEquals(3, first.get("total").asInt(), first.toString());
        assertTrue(first.get("running").asBoolean(), first.toString());
        assertEquals("Gegner", first.get("current").asText(), first.toString());

        assertTrue(inGame.await(30, TimeUnit.SECONDS), "die erste Partie laeuft");
        send("{\"type\":\"sparringStart\",\"deck\":\"Mein Deck\",\"games\":3}");
        JsonNode err = await("error", n -> true, 10);
        assertTrue(err.path("text").asText().contains("Sparring läuft noch"),
                err + " (games=" + games.get() + ")");

        send("{\"type\":\"sparringCancel\"}");
        release.countDown();

        JsonNode last = await("sparringProgress", n -> !n.get("running").asBoolean(), 30);
        assertEquals(1, last.get("done").asInt(), last.toString());
        assertEquals(3, last.get("total").asInt(), last.toString());
        assertEquals(0, last.get("errors").size(), last.toString());
        assertEquals(1, games.get(), "nach dem Abbruch keine weitere Partie");

        boolean sawMatch;
        synchronized (seenMatches) {
            sawMatch = seenMatches.stream().anyMatch(n -> n.get("matches").size() == 1
                    && "sparring".equals(n.get("matches").get(0).path("source").asText()));
        }
        assertTrue(sawMatch, "nach der Partie kam eine matches-Liste mit dem Sparring-Datensatz: " + seenMatches);
        assertFalse(last.path("current").isTextual(), last.toString());
    }
}
