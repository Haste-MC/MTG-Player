package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Der archidektImport-Lauf ueber das echte Protokoll, mit einem Archidekt-Fetcher ohne Netz
 * (Deck 1 = Fixture archidekt-1.json, Deck 2 = HTTP 500) und einem temporaeren Deck-Verzeichnis -
 * Kevins echtes ~/.mtg-player/decks wird nie angefasst. Eigener Port (18087), damit dieser Test nicht mit
 * {@link BridgeEndToEndTest} (18081), {@link BridgeSpectatorTest} (18084) oder {@link WsServerTest}
 * (18082/18083) kollidiert.
 */
class BridgeArchidektImportTest {

    private static final int PORT = 18087;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @TempDir
    static Path deckDir;

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        String deck1 = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        String list1 = Files.readString(Path.of("src/test/resources/archidekt-list-1.json"));
        Archidekt archidekt = new Archidekt(url -> {
            if (url.contains("/decks/1/")) return deck1;
            if (url.contains("/decks/2/")) throw new IOException("HTTP 500");
            if (url.contains("/decks/v3/")) return list1;
            throw new IOException("unerwartete URL im Test: " + url);
        });
        bridge = new Bridge(PORT, new DeckStore(deckDir), archidekt, new MatchStore(deckDir.resolve("matches.json")));
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

    /** Naechste Nachricht, deren Typ in {@code types} liegt; alle anderen werden in {@code others} gesammelt. */
    private static JsonNode next(List<String> types, List<JsonNode> others, int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if (types.contains(n.path("type").asText())) return n;
            others.add(n);
        }
        throw new AssertionError("keine Nachricht " + types + " innerhalb " + seconds + " s; andere: " + others);
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

    private static void assertProgress(JsonNode n, int done, int total, String current) {
        assertEquals("archidektProgress", n.path("type").asText(), n.toString());
        assertEquals(done, n.get("done").asInt(), n.toString());
        assertEquals(total, n.get("total").asInt(), n.toString());
        if (current == null) {
            // Json.mapper() laesst null-Felder weg (NON_NULL): current fehlt dann im Draht-JSON
            assertTrue(n.path("current").isMissingNode() || n.path("current").isNull(), n.toString());
        } else {
            assertEquals(current, n.path("current").asText(), n.toString());
        }
    }

    private static JsonNode deckNamed(JsonNode lobby, String name) {
        for (JsonNode d : lobby.get("decks")) {
            if (name.equals(d.path("name").asText())) return d;
        }
        return null;
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void importLaufMeldetFortschrittLobbyUndFehlerJeDeck() throws Exception {
        JsonNode connectLobby = await("lobby", n -> true, 10);
        assertEquals(0, connectLobby.get("decks").size(), "leeres Deck-Verzeichnis zu Beginn");

        List<String> types = List.of("archidektProgress", "lobby");
        List<JsonNode> others = new ArrayList<>();
        client.send("{\"type\":\"archidektImport\",\"ids\":[1,2]}");

        JsonNode p0 = next(types, others, 10);
        assertProgress(p0, 0, 2, null);
        // waehrend der Lauf laeuft: ein zweiter Import wird abgewiesen
        client.send("{\"type\":\"archidektImport\",\"ids\":[1]}");

        assertProgress(next(types, others, 10), 0, 2, "Deck 1");
        JsonNode lobby1 = next(types, others, 30);
        assertEquals("lobby", lobby1.path("type").asText(), lobby1.toString());
        JsonNode fungus = deckNamed(lobby1, "Fun With Fungus");
        assertNotNull(fungus, "Deck 1 nach dem ersten Schritt gespeichert: " + lobby1.get("decks"));
        assertEquals("1", fungus.path("archidekt").asText());
        assertEquals("2018-03-12T05:12:58Z", fungus.path("archidektUpdated").asText());

        assertProgress(next(types, others, 10), 1, 2, "Deck 2");
        JsonNode lobby2 = next(types, others, 30);
        assertEquals("lobby", lobby2.path("type").asText(), lobby2.toString());
        assertEquals(1, lobby2.get("decks").size(), "Deck 2 (HTTP 500) wurde nicht gespeichert");

        JsonNode fin = next(types, others, 10);
        assertProgress(fin, 2, 2, null);
        assertEquals(List.of("Deck 2: Archidekt: HTTP 500"),
                Json.mapper().convertValue(fin.get("errors"), List.class));

        // der abgewiesene zweite Import (kam irgendwann waehrend des Laufs an)
        List<String> errors = others.stream()
                .filter(n -> "error".equals(n.path("type").asText()))
                .map(n -> n.path("text").asText()).toList();
        assertEquals(List.of("Archidekt: Import läuft noch"), errors, "andere Nachrichten: " + others);

        // Platte: nur das eine Deck im Temp-Verzeichnis, mit beiden Tags
        DeckStore store = new DeckStore(deckDir);
        assertEquals(List.of("Fun With Fungus"), store.names());
        assertEquals("1", store.archidektId("Fun With Fungus"));

        // nach dem Lauf ist das Flag frei: ein neuer Lauf (Resync von Deck 1) laeuft wieder durch
        client.send("{\"type\":\"archidektImport\",\"ids\":[1]}");
        others.clear();
        assertProgress(next(types, others, 10), 0, 1, null);
        assertProgress(next(types, others, 10), 0, 1, "Fun With Fungus");
        assertEquals("lobby", next(types, others, 30).path("type").asText());
        JsonNode fin2 = next(types, others, 10);
        assertProgress(fin2, 1, 1, null);
        assertEquals(0, fin2.get("errors").size(), fin2.toString());
        assertEquals(List.of("Fun With Fungus"), store.names());
    }
}
