package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Task 3: die "matches"-Liste beim Connect ist auf die neuesten 300 Partien gedeckelt und traegt
 * zusaetzlich "total" (die tatsaechliche Gesamtzahl, auch wenn mehr als 300 gespeichert sind). Die
 * MatchStore-Datei wird hier direkt mit 301 Datensaetzen befuellt (statt 301 echte Partien zu spielen -
 * das waere fuer diesen Test viel zu langsam). Eigener Port (18085), damit dieser Test nicht mit den
 * anderen Bridge*Test-Klassen kollidiert.
 */
class BridgeMatchesCapTest {

    private static final int PORT = 18085;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @TempDir
    static Path matchDir;

    /** Wie MatchStoreTest.record: ein vollstaendiger, plausibler Datensatz mit nicht-leerer Zeitachse. */
    private static MatchRecord record(String id) {
        MatchRecord.Seat seat = new MatchRecord.Seat("Du", "Titania, Gaea Incarnate", true, null, true, null,
                null, 1, 7, List.of(0, 1, 2, 2, 3), 2, 4, 12, 34,
                1, 0, 2, 9, 3, 6, 5, 2, 3, 4, 1, 2,
                8, 5, 6, 3, 4, 2, 6, 6, 15, 6, 4, 7,
                List.of(new MatchRecord.TurnPoint(1, 1, 0, 40, 6, 2),
                        new MatchRecord.TurnPoint(3, 2, 1, 38, 5, 1)),
                2, 2, 4, 21, 18, 12, 22, 0, 5, 2, 3);
        return new MatchRecord(id, "2026-09-22T19:00:00Z", "2026-09-22T19:13:32Z", 812345, "live",
                5, 14, "AllOpponentsLost", false, true, null, List.of(seat));
    }

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        // eigener MatchStore (wie die anderen Bridge*Test-Klassen): Kevins echte ~/.mtg-player bleibt
        // unberuehrt.
        MatchStore store = new MatchStore(matchDir.resolve("matches.json"));
        // 301 Partien - eine mehr als der Deckel (300), damit "m0" als aelteste rausfaellt.
        for (int i = 0; i <= 300; i++) {
            store.add(record("m" + i));
        }
        bridge = new Bridge(PORT, DeckStore.standard(), Archidekt.standard(), store);
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
        client.closeBlocking();
        bridge.stop();
    }

    private static JsonNode await(String type, Predicate<JsonNode> cond, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if (type.equals(n.path("type").asText()) && cond.test(n)) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void connectSchicktNurDieNeuesten300PlusTotal() throws Exception {
        JsonNode matches = await("matches", n -> true, 10);
        assertEquals(301, matches.get("total").asInt(), matches.toString());
        assertEquals(300, matches.get("matches").size(), matches.toString());
        // aelteste zuerst wie MatchStore.all(), aber "m0" ist als aelteste rausgefallen.
        assertEquals("m1", matches.get("matches").get(0).get("id").asText());
        assertEquals("m300", matches.get("matches").get(matches.get("matches").size() - 1).get("id").asText());
        for (JsonNode m : matches.get("matches")) {
            assertFalse(m.get("seats").get(0).has("timeline"), "ohne Zeitachse-Feld je Sitz: " + m);
        }
    }
}
