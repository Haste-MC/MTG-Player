package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.decks.Edhrec;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * suggestCards ueber das echte Protokoll (Spec 2026-09-25-kartenvorschlaege §2), Erfolgspfad. Anders
 * als in BridgeEndToEndTest wird hier NICHT {@code new Bridge(wsPort, store, archidekt, matches)}
 * benutzt: dieser Konstruktor haengt fest an einer echten {@link Edhrec} (echter Netzabruf, echter
 * Zwischenspeicher unter {@code ~/.mtg-player/edhrec}). Stattdessen der eigens dafuer vorhandene
 * 6-Parameter-Konstruktor mit einer eingesetzten Edhrec-Quelle (Funktion statt HTTP-Client) und einem
 * Temp-Verzeichnis als Zwischenspeicher - wie {@link mtgplayer.decks.EdhrecTest}, nur ueber die Bridge
 * hinweg. Die Quelle liefert immer den Pruefstein {@code edhrec-titania.json} (aus EdhrecTest bekannt),
 * unabhaengig von der angefragten URL - kein Netz, kein Schreiben ausserhalb von {@code tmp}.
 */
class BridgeSuggestCardsTest {

    private static final int PORT = 18088;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    private static final AtomicInteger edhrecCalls = new AtomicInteger();

    @TempDir
    static Path tmp;

    private static String fixture() throws Exception {
        try (var in = BridgeSuggestCardsTest.class.getResourceAsStream("/edhrec-titania.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PaperCard card(String name) {
        return FModel.getMagicDb().getCommonCards().getCard(name);
    }

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        DeckStore decks = new DeckStore(tmp.resolve("decks"));
        // Gruenes Commander-Deck mit Titania (wie SuggestionsTest.deck()) - "Harrow" (Landsuche, siehe
        // DeckAnalysis-Regel "ramp") ist bewusst NICHT im Hauptdeck, damit er als Vorschlag infrage kommt.
        Deck deck = new Deck("Titania Test");
        deck.getOrCreate(DeckSection.Commander).add(card("Titania, Protector of Argoth"));
        deck.getMain().add(card("Llanowar Elves"));
        deck.getMain().add(card("Forest"), 30);
        decks.save("Titania Test", deck);

        String json = fixture();
        Edhrec edhrec = new Edhrec(url -> { edhrecCalls.incrementAndGet(); return json; }, tmp.resolve("edhrec"));

        bridge = new Bridge(PORT, decks, Archidekt.standard(), new MatchStore(tmp.resolve("matches.json")),
                new SubprocessGameRunner(), edhrec);
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

    /** Erfolgspfad: bekanntes Deck, angefragte Rolle "ramp" - Antwort traegt alle vom Client erwarteten
     *  Felder (Spec §2), source "edhrec" (der Pruefstein liefert einen echten Commander-Stand), und
     *  mindestens einen Vorschlag mit plausiblen Feldern. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void suggestCardsLiefertVorschlaegeAusDemEingesetztenEdhrecStand() throws Exception {
        client.send("{\"type\":\"suggestCards\",\"deck\":\"Titania Test\",\"roles\":[\"ramp\"]}");
        JsonNode msg = await("cardSuggestions", n -> true, 20);

        assertEquals("cardSuggestions", msg.get("type").asText());
        assertEquals("Titania Test", msg.get("deck").asText());
        assertEquals("edhrec", msg.get("source").asText());
        assertTrue(msg.get("suggestions").size() > 0, msg.toString());

        JsonNode suggestion = msg.get("suggestions").get(0);
        assertEquals("ramp", suggestion.get("role").asText());
        assertTrue(suggestion.has("name") && !suggestion.get("name").asText().isBlank(), suggestion.toString());
        assertTrue(suggestion.has("manaCost"), suggestion.toString());
        assertTrue(suggestion.has("cmc"), suggestion.toString());
        assertTrue(suggestion.has("share"), "source edhrec traegt einen Anteil: " + suggestion);
        assertTrue(suggestion.has("imageKey"), suggestion.toString());
        assertTrue(suggestion.has("text"), suggestion.toString());
        // Quelle "edhrec": die Bridge hat gerade erst abgerufen (Edhrec.page() ohne Zwischenstand im
        // frischen Temp-Verzeichnis) - fetched muss also da sein und ein gueltiger ISO-Zeitpunkt.
        assertTrue(msg.has("fetched"), msg.toString());
        assertFalse(msg.get("fetched").isNull(), msg.toString());
        Instant.parse(msg.get("fetched").asText());   // wirft, wenn kein gueltiges ISO-Datum

        assertEquals(1, edhrecCalls.get(), "genau ein Abruf ueber die eingesetzte Quelle, kein echtes Netz");
    }

    /** Unbekanntes Deck: derselbe Fehlerpfad wie in BridgeEndToEndTest, hier zusaetzlich mit Nachweis,
     *  dass dabei kein einziger Edhrec-Aufruf stattfindet (forAnalysis(name) liefert null zuerst). */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void suggestCardsUnbekanntesDeckLiefertErrorOhneEdhrecAufruf() throws Exception {
        int callsBefore = edhrecCalls.get();
        client.send("{\"type\":\"suggestCards\",\"deck\":\"gibt es nicht\",\"roles\":[\"ramp\"]}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Kartenvorschläge gibt es nicht:"), 10);
        assertEquals("Kartenvorschläge gibt es nicht: unbekanntes Deck", err.path("text").asText());
        assertEquals(callsBefore, edhrecCalls.get(), "unbekanntes Deck darf Edhrec#page() nie erreichen");
    }
}
