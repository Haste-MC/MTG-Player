package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Spielt die ersten Sekunden eines echten Spiels über das Protokoll: Lobby → Spielstart →
 * Mulligan-Prompt → Keep → Prio-Prompt in Zug 1 → Concede-Dialog → gameOver.
 *
 * <p>Beide Tests teilen sich eine statische WebSocket-Verbindung/Inbox (siehe start()); die
 * Bridge schickt "lobby" beim Connect und erneut nach jedem Spielstart (Bridge#handle, ggf. neu
 * gespeichertes Deck). {@code @TestMethodOrder} stellt sicher, dass
 * {@link #lobbyStartKeepPrioConcede()} (das dieses Connect-"lobby" konsumiert und auf
 * {@code aiProfiles} prüft) vor {@link #aiConfigUndTimeoutWerdenAngenommenUnbekanntesProfilAbgelehnt()}
 * läuft - sonst würde dessen erstes {@code await("error", ...)} das Connect-"lobby" stillschweigend
 * verwerfen (await() verwirft jede Nachricht, die nicht zum gesuchten Typ passt).</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BridgeEndToEndTest {

    private static final int PORT = 18081;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    // await()/awaitMulliganPrompt() verwerfen beim Warten auf state/choice alles, was nicht passt -
    // Log-Zeilen (TURN/MULLIGAN kommen frueh, waehrend Mulligan bzw. Zug 1 laufen) muessen deshalb hier
    // mitgeschnitten werden, sonst sind sie laengst durch den Draht und verworfen, bis der Test explizit
    // danach fragt.
    private static final List<JsonNode> seenLogLines = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        bridge = new Bridge(PORT);
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
            if ("error".equals(n.path("type").asText())) {
                System.err.println("[bridge error] " + n.path("text").asText());
            }
            if ("log".equals(n.path("type").asText())) {
                seenLogLines.add(n);
            }
            if (type.equals(n.path("type").asText()) && cond.test(n)) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    private static void send(String json) {
        client.send(json);
    }

    /**
     * Wartet auf den Mulligan-Prompt (okLabel "Keep"), klickt dabei den Muenzwurf-Prompt
     * "Play or Draw?" (okLabel "Play") explizit weg - der tritt nur auf, wenn der Mensch den
     * Muenzwurf gewinnt (siehe PlayerControllerHuman#chooseStartingPlayer). Kein blindes
     * Wegklicken jedes enabled-ok-Prompts, damit ein unerwarteter dritter Prompt auffaellt statt
     * stillschweigend weggeklickt zu werden.
     *
     * <p>Der "Play"-Klick darf nur <b>einmal</b> gesendet werden, nicht einmal pro Nachricht: Forges
     * {@code InputProxy.update()} zeigt den naechsten Prompt (hier den Mulligan-"Keep"-Dialog) ueber
     * {@code FThreads.invokeInEdtLater(...)} verzoegert auf demselben Single-Thread-Executor an, den
     * auch {@code WebGuiBase} fuer Klicks aus dem Browser nutzt (siehe {@code WebGuiGame.push()} und
     * {@code Bridge.ui(...)}). Der Game-Thread laeuft nach dem "Play"-Klick sofort weiter (Untap,
     * Upkeep, ...) und pusht dabei denselben, noch nicht aktualisierten "Play"-Prompt erneut - der
     * eigentliche "Keep"-Prompt wird erst verzoegert auf dem Executor gesetzt. Ein zweiter, ueberfluessiger
     * "ok"-Klick auf einen dieser Duplikate landet dann - abhaengig vom Scheduling auf demselben
     * Executor - blind auf dem naechsten echten Input (dem Mulligan-Dialog) und beantwortet ihn, bevor
     * der Test dessen "Keep"-Zustand je gesehen hat; da WebGuiGame.push() mehrere Zustandsaenderungen
     * zu einem Snapshot buendelt, wird der "Keep"-Zustand dabei nie einzeln ueber den Draht geschickt.
     * Ergebnis: der Test wartet 90 s auf ein "Keep", das nie ankommt, obwohl Bridge und Spiel korrekt
     * arbeiten - der Test hat sich selbst durch den Mulligan geklickt. Fix: genau ein "ok" pro echtem
     * "Play"-Prompt, kein Klick mehr fuer weitere Duplikate desselben Prompts.
     */
    private static JsonNode awaitMulliganPrompt(int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean playClicked = false;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if ("error".equals(n.path("type").asText())) {
                System.err.println("[bridge error] " + n.path("text").asText());
            }
            if ("log".equals(n.path("type").asText())) {
                seenLogLines.add(n);
            }
            if ("state".equals(n.path("type").asText())) {
                String okLabel = n.path("prompt").path("okLabel").asText();
                if ("Keep".equals(okLabel)) return n;
                if (!playClicked && "Play".equals(okLabel) && n.path("prompt").path("okEnabled").asBoolean()) {
                    playClicked = true;
                    send("{\"type\":\"ok\"}");
                }
            }
        }
        throw new AssertionError("keine Nachricht 'state' mit Keep-Prompt innerhalb " + seconds + " s");
    }

    private static JsonNode myPlayer(JsonNode state) {
        int me = state.get("me").asInt();
        for (JsonNode p : state.get("players")) {
            if (p.get("id").asInt() == me) return p;
        }
        throw new AssertionError("eigener Spieler fehlt im Snapshot");
    }

    @Test
    @Order(1)
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void lobbyStartKeepPrioConcede() throws Exception {
        JsonNode lobby = await("lobby", n -> true, 10);
        assertTrue(lobby.get("precons").size() > 100);
        assertTrue(Json.mapper().convertValue(lobby.get("aiProfiles"), List.class).contains("Default"),
                "aiProfiles enthaelt Default: " + lobby.get("aiProfiles"));

        send("{\"type\":\"startGame\",\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
                + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\"}]}");

        // Gewinnt der Mensch den Muenzwurf, fragt Forge vor dem Mulligan "Play or Draw?"
        // (PlayerControllerHuman.chooseStartingPlayer) - ein echter, ~zufaellig auftretender
        // Prompt, den ein Browser genauso wegklicken muesste. Klick auf ok (== "Play").
        JsonNode mull = awaitMulliganPrompt(90);
        JsonNode me = myPlayer(mull);
        assertEquals(7, me.get("hand").size(), "Starthand");
        assertEquals(false, me.get("isAi").asBoolean());
        for (JsonNode id : me.get("hand")) {
            JsonNode card = mull.get("cards").get(id.asText());
            assertNotNull(card.get("name"), "eigene Handkarte sichtbar");
            // faceDown ist ein primitives boolean im Snapshot (Task 1) - Jackson laesst es nie weg
            // (NON_NULL greift nur bei null), also den Wert pruefen statt has() (wie unten bei foe).
            assertFalse(card.path("faceDown").asBoolean());
        }
        JsonNode foe = null;
        for (JsonNode p : mull.get("players")) if (p.get("isAi").asBoolean()) foe = p;
        assertNotNull(foe);
        assertEquals(40, foe.get("life").asInt());
        for (JsonNode id : foe.get("hand")) {
            JsonNode card = mull.get("cards").get(id.asText());
            assertTrue(card.path("faceDown").asBoolean(), "gegnerische Handkarte verdeckt");
            assertFalse(card.has("name"));
        }

        int keepSeq = mull.path("prompt").path("seq").asInt();
        assertTrue(keepSeq >= 2, "seq wurde durch die InputQueue mindestens einmal erhöht");
        send("{\"type\":\"ok\",\"seq\":" + (keepSeq - 1) + "}"); // veraltet – muss ignoriert werden
        Thread.sleep(1500);
        send("{\"type\":\"requestState\"}");
        JsonNode still = await("state", n -> true, 10);
        assertEquals("Keep", still.path("prompt").path("okLabel").asText(), "veraltetes ok hat nichts ausgelöst");
        send("{\"type\":\"ok\",\"seq\":" + keepSeq + "}");

        JsonNode prio = await("state", n -> n.path("turn").asInt() >= 1
                && n.path("prompt").path("okEnabled").asBoolean()
                && !"Keep".equals(n.path("prompt").path("okLabel").asText()), 120);
        assertNotNull(prio.get("phase"));
        assertTrue(prio.path("prompt").path("message").asText().length() > 0, "Prio-Prompt hat Text");

        // Forge loggt Mulligan-Entscheidungen und Zugwechsel - eine der beiden Arten muss bis hierhin
        // ueber den Log-Stream angekommen sein. Ein await() an dieser Stelle waere zu spaet (siehe
        // seenLogLines oben): TURN/MULLIGAN sind laengst durchgelaufen, waehrend awaitMulliganPrompt()
        // bzw. der Prio-await() auf "state" gewartet haben - deshalb hier den Mitschnitt pruefen.
        JsonNode line = seenLogLines.stream()
                .filter(n -> "TURN".equals(n.path("kind").asText()) || "MULLIGAN".equals(n.path("kind").asText()))
                .findFirst().orElse(null);
        assertNotNull(line, "keine TURN/MULLIGAN Log-Zeile gesehen");
        assertTrue(line.get("text").asText().length() > 0);

        // Reconnect: requestState muss die gepufferten Log-Zeilen erneut schicken.
        send("{\"type\":\"requestState\"}");
        await("log", n -> true, 10);

        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");

        JsonNode over = await("gameOver", n -> true, 60);
        assertNotNull(over);
    }

    /** Deckt AiConfig/aiTimeout ueber das echte Protokoll ab: unbekanntes Profil wird abgelehnt
     *  (Fehler statt Spielstart), ein gueltiger Modus/Profil/Timeout startet ein Spiel wie gewohnt. */
    @Test
    @Order(2)
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void aiConfigUndTimeoutWerdenAngenommenUnbekanntesProfilAbgelehnt() throws Exception {
        send("{\"type\":\"startGame\",\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
            + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\",\"ai\":{\"mode\":\"sim\",\"profile\":\"Nope\"}}]}");
        JsonNode err = await("error", n -> true, 10);
        assertTrue(err.path("text").asText().contains("Nope"), err.toString());
        send("{\"type\":\"startGame\",\"aiTimeout\":3,\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
            + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\",\"ai\":{\"mode\":\"hybrid\",\"profile\":\"Cautious\"}}]}");
        JsonNode mull = awaitMulliganPrompt(90);   // Spiel laeuft an
        assertEquals(2, mull.get("players").size());
        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");
        assertNotNull(await("gameOver", n -> true, 60));
    }
}
