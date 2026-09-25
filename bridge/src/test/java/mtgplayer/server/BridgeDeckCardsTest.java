package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.decks.Edhrec;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.CardLog;
import mtgplayer.stats.CardStore;
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
 * deckCards ueber das echte Protokoll (Task-4-Brief), nach dem Muster von BridgeSuggestCardsTest: eigene
 * (temporaere) DeckStore/MatchStore/CardStore statt der Kevin-Standardpfade unter {@code ~/.mtg-player} -
 * ueber den 7-Parameter-Testkonstruktor (siehe BridgeEndToEndTest), der den CardStore einsetzbar macht.
 * Die eigentliche Auswertung (Zaehlung, Sortierung, ...) prueft CardStatsTest; hier nur, dass die Bridge
 * die Anfrage entgegennimmt, die Nachricht richtig aufbaut und das Fehlschreiben bei unbekanntem Deck.
 */
class BridgeDeckCardsTest {

    private static final int PORT = 18089;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private static MatchStore matches;
    private static CardStore cards;

    @TempDir
    static Path tmp;

    private static PaperCard card(String name) {
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

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

    private static CardLog.Card row(String name, Integer hand, Integer cast, Integer castTurn) {
        return new CardLog.Card(name, 1, hand, cast, castTurn, null, null, "battlefield");
    }

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        DeckStore decks = new DeckStore(tmp.resolve("decks"));
        // Kleines Commander-Deck, nur damit forAnalysis(name) das Deck als bekannt erkennt (siehe
        // Bridge.analyzeDeck/forAnalysis) - der Inhalt selbst spielt fuer CardStats.of() keine Rolle,
        // das gleicht nur ueber den Namen der gespeicherten Partien ab.
        Deck deck = new Deck("Card Stats Test");
        deck.getOrCreate(DeckSection.Commander).add(card("Titania, Protector of Argoth"));
        deck.getMain().add(card("Sol Ring"));
        deck.getMain().add(card("Forest"), 30);
        decks.save("Card Stats Test", deck);

        matches = new MatchStore(tmp.resolve("matches.json"));
        cards = new CardStore(tmp.resolve("cards"));
        // Zwei gewertete Partien mit Kartendatei: Sol Ring in beiden auf der Hand, nur in einer gewirkt.
        for (int i = 0; i < 2; i++) {
            String id = "m" + i;
            matches.add(match(id, true, seat("Du", "Card Stats Test", true)));
        }
        cards.write(new CardLog("m0", List.of(new CardLog.SeatCards(0, "Card Stats Test",
                List.of(row("Sol Ring", 1, 1, 1))))));
        cards.write(new CardLog("m1", List.of(new CardLog.SeatCards(0, "Card Stats Test",
                List.of(row("Sol Ring", 1, null, null))))));

        bridge = new Bridge(PORT, decks, Archidekt.standard(), matches, new SubprocessGameRunner(),
                new Edhrec(), cards);
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

    /** Erfolgspfad: bekanntes Deck mit zwei gewerteten Partien - die Antwort traegt Typ, Deckname,
     *  games/withCardData und die erwartete Karte (Sol Ring) mit ihren Kennzahlen. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void deckCardsLiefertDieAuswertungDesAngefragtenDecks() throws Exception {
        client.send("{\"type\":\"deckCards\",\"deck\":\"Card Stats Test\"}");
        JsonNode msg = await("cardStats", n -> true, 20);

        assertEquals("cardStats", msg.get("type").asText());
        assertEquals("Card Stats Test", msg.get("deck").asText());
        assertEquals(2, msg.get("games").asInt());
        assertEquals(2, msg.get("withCardData").asInt());
        assertFalse(msg.get("enough").asBoolean(), "unter CardStats.MIN_GAMES (5)");

        JsonNode solRing = null;
        for (JsonNode c : msg.get("cards")) {
            if ("Sol Ring".equals(c.get("name").asText())) {
                solRing = c;
                break;
            }
        }
        assertTrue(solRing != null, "Sol Ring fehlt in " + msg);
        assertEquals(2, solRing.get("handGames").asInt());
        assertEquals(1, solRing.get("castGames").asInt());
        assertEquals(1, solRing.get("stuckGames").asInt());
    }

    /** Unbekanntes Deck: derselbe Fehlerpfad/-text wie bei analyzeDeck/suggestCards. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void deckCardsUnbekanntesDeckLiefertError() throws Exception {
        client.send("{\"type\":\"deckCards\",\"deck\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Kartenauswertung gibt es nicht:"), 10);
        assertEquals("Kartenauswertung gibt es nicht: unbekanntes Deck", err.path("text").asText());
    }
}
