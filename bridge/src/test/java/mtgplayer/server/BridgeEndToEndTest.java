package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.decks.Edhrec;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.CardStore;
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

import java.net.URI;
import java.nio.file.Path;
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
 * <p>Alle Tests teilen sich eine statische WebSocket-Verbindung/Inbox (siehe start()); die
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
    // Task 3: "matches" nach gameOver ist nicht garantiert NACH gameOver auf dem Draht - der
    // MatchRecorder haengt am Forge-Ereignisbus (Spiel-Thread), "gameOver" kommt aus
    // WebGuiGame.finishGame() (UI-Thread); beides kann sich ueberschneiden. Ein await("matches",...)
    // DIREKT nach await("gameOver",...) kann daher ins Leere laufen, wenn "matches" schon waehrend
    // des gameOver-Wartens durchkam und dort stillschweigend verworfen wurde - deshalb hier
    // mitgeschnitten wie seenLogLines, siehe awaitMatches().
    private static final List<JsonNode> seenMatches = Collections.synchronizedList(new ArrayList<>());

    @TempDir
    static Path matchDir;

    /** Eigener {@link CardStore} (Task 2, Runde C): siehe {@link #lobbyStartKeepPrioConcede()}, das nach
     *  deleteMatch prueft, dass auch die Kartendatei verschwindet. */
    private static CardStore cards;

    /** Derselbe Store wie der der Bridge - fuer Tests, die die geschriebenen Datensaetze direkt ansehen. */
    private static MatchStore matchStore;

    @BeforeAll
    static void start() throws Exception {
        ForgeBoot.init();
        // eigener MatchStore (Task 3): Kevins echte ~/.mtg-player/matches.json wird nie angefasst,
        // obwohl dieser Test mehrere echte Partien bis gameOver spielt (concede). Eigener CardStore aus
        // demselben Grund fuer ~/.mtg-player/cards (Task 2, Runde C).
        cards = new CardStore(matchDir.resolve("cards"));
        matchStore = new MatchStore(matchDir.resolve("matches.json"));
        bridge = new Bridge(PORT, DeckStore.standard(), Archidekt.standard(), matchStore,
                new SubprocessGameRunner(), new Edhrec(), cards);
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
            if ("matches".equals(n.path("type").asText())) {
                seenMatches.add(n);
            }
            if (type.equals(n.path("type").asText()) && cond.test(n)) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    /** Wie {@link #await}, sucht aber zuerst im Mitschnitt {@link #seenMatches} - siehe dessen Kommentar. */
    private static JsonNode awaitMatches(Predicate<JsonNode> cond, int seconds) throws InterruptedException {
        synchronized (seenMatches) {
            for (int i = seenMatches.size() - 1; i >= 0; i--) {
                if (cond.test(seenMatches.get(i))) return seenMatches.get(i);
            }
        }
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
            if ("matches".equals(n.path("type").asText())) {
                seenMatches.add(n);
                if (cond.test(n)) return n;
            }
        }
        throw new AssertionError("keine Nachricht 'matches' innerhalb " + seconds + " s (auch nicht im Mitschnitt)");
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
        JsonNode precon = lobby.get("precons").get(0);
        assertTrue(precon.get("name").isTextual(), "precon hat name: " + precon);
        assertTrue(precon.get("commanders").size() >= 1, "precon hat commanders: " + precon);
        assertTrue(precon.get("commanders").get(0).get("imageKey").asText().startsWith("c:"),
                "commander hat imageKey: " + precon);
        assertTrue(Json.mapper().convertValue(lobby.get("aiProfiles"), List.class).contains("Default"),
                "aiProfiles enthaelt Default: " + lobby.get("aiProfiles"));

        // Task 3: nach "lobby" kommt beim Connect zusaetzlich "matches" - noch leer, es lief noch keine Partie.
        JsonNode initialMatches = await("matches", n -> true, 5);
        assertTrue(initialMatches.get("matches").isArray());
        assertEquals(0, initialMatches.get("matches").size(), initialMatches.toString());
        // Task 3: "total" traegt die tatsaechliche Gesamtzahl, unabhaengig vom 300er-Deckel.
        assertEquals(0, initialMatches.get("total").asInt(), initialMatches.toString());

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

        // Task 3: die beendete (aufgegebene) Partie landet in einer aktualisierten "matches"-Liste.
        // awaitMatches() statt await(): siehe seenMatches-Kommentar oben (Reihenfolge zu gameOver
        // ist nicht garantiert).
        JsonNode updated = awaitMatches(n -> n.get("matches").size() >= 1, 10);
        assertEquals(1, updated.get("matches").size(), updated.toString());
        assertEquals(1, updated.get("total").asInt(), updated.toString());
        String matchId = updated.get("matches").get(0).get("id").asText();
        assertFalse(updated.get("matches").get(0).get("counted").asBoolean(), "aufgegebene Partie zaehlt nicht");
        // Task 2 (Runde C): eine aufgegebene, aber sauber (ueber concede/GameEventGameFinished statt
        // markAborted/markCrashed/markTurnCapped) beendete Partie liefert trotzdem eine Kartendatei -
        // siehe MatchRecorder-Klassenkommentar "Kartenbiografie".
        assertTrue(cards.read(matchId).isPresent(), "Kartendatei existiert nach einer sauber beendeten Partie");
        // Task 3: die schlanke Liste traegt keine Zeitachse - weder je Partie noch je Sitz.
        assertFalse(updated.get("matches").get(0).has("timeline"), updated.toString());
        assertFalse(updated.get("matches").get(0).get("seats").get(0).has("timeline"), updated.toString());

        // matchDetail liefert denselben Datensatz vollstaendig, inklusive (ggf. leerer) Zeitachse.
        send("{\"type\":\"matchDetail\",\"id\":\"" + matchId + "\"}");
        JsonNode detail = await("match", n -> matchId.equals(n.path("match").path("id").asText()), 10);
        assertTrue(detail.get("match").get("seats").get(0).has("timeline"), detail.toString());

        // setMatchCounted schickt die aktualisierte Liste mit geaendertem counted.
        send("{\"type\":\"setMatchCounted\",\"id\":\"" + matchId + "\",\"counted\":true}");
        JsonNode afterSetCounted = await("matches", n -> matchWithId(n, matchId) != null
                && matchWithId(n, matchId).get("counted").asBoolean(), 10);
        assertTrue(matchWithId(afterSetCounted, matchId).get("counted").asBoolean());

        // deleteMatch entfernt die Partie aus der naechsten "matches"-Liste.
        send("{\"type\":\"deleteMatch\",\"id\":\"" + matchId + "\"}");
        JsonNode afterDelete = await("matches", n -> matchWithId(n, matchId) == null, 10);
        assertEquals(0, afterDelete.get("matches").size(), afterDelete.toString());
        assertEquals(0, afterDelete.get("total").asInt(), afterDelete.toString());
        // Task 2 (Runde C): deleteMatch loescht die Kartendatei mit - sie gehoert zur Partie, anders als
        // bei setMatchCounted (siehe oben), das bewusst nichts loescht.
        assertTrue(cards.read(matchId).isEmpty(), "deleteMatch loescht auch die Kartendatei");
    }

    private static JsonNode matchWithId(JsonNode matchesMsg, String id) {
        for (JsonNode m : matchesMsg.get("matches")) {
            if (id.equals(m.get("id").asText())) return m;
        }
        return null;
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

    /** resyncDeck mit unbekanntem Namen: die Bridge antwortet mit "error" (Text aus DeckSource.resync) statt still zu bleiben. */
    @Test
    @Order(3)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void resyncDeckUnbekanntesDeckLiefertError() throws Exception {
        send("{\"type\":\"resyncDeck\",\"name\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Resync gibt es nicht:"), 10);
        assertTrue(err.path("text").asText().startsWith("Resync gibt es nicht:"), err.toString());
    }

    /** archidektImport mit leerer Id-Liste: sofort ein abschliessendes archidektProgress 0/0 ohne Fehler. */
    @Test
    @Order(4)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void archidektImportLeerLiefertProgressNullVonNull() throws Exception {
        send("{\"type\":\"archidektImport\",\"ids\":[]}");
        JsonNode p = await("archidektProgress", n -> true, 10);
        assertEquals(0, p.get("done").asInt());
        assertEquals(0, p.get("total").asInt());
        // Json.mapper() laesst null-Felder weg (NON_NULL): current fehlt dann im Draht-JSON
        assertTrue(p.path("current").isMissingNode() || p.path("current").isNull(), p.toString());
        assertEquals(0, p.get("errors").size());
    }

    /** archidektList ohne Benutzername: "error" statt Netzzugriff. */
    @Test
    @Order(5)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void archidektListOhneNamenLiefertError() throws Exception {
        send("{\"type\":\"archidektList\",\"username\":\"\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Archidekt:"), 10);
        assertTrue(err.path("text").asText().contains("Benutzername"), err.toString());
    }

    /** deleteDeck mit unbekanntem Namen: die Bridge antwortet mit "error" (Text aus DeckStore.delete) statt still zu bleiben. */
    @Test
    @Order(6)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void deleteDeckUnbekanntesDeckLiefertError() throws Exception {
        send("{\"type\":\"deleteDeck\",\"name\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Löschen gibt es nicht:"), 10);
        assertTrue(err.path("text").asText().startsWith("Löschen gibt es nicht:"), err.toString());
    }

    /** deleteMatch mit unbekannter Id: "error" beginnend mit "Partie <id>: " statt still zu bleiben. */
    @Test
    @Order(7)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void deleteMatchUnbekannteIdLiefertError() throws Exception {
        send("{\"type\":\"deleteMatch\",\"id\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Partie gibt es nicht:"), 10);
        assertTrue(err.path("text").asText().startsWith("Partie gibt es nicht:"), err.toString());
    }

    /** setMatchCounted mit unbekannter Id: "error" beginnend mit "Partie <id>: " statt still zu bleiben. */
    @Test
    @Order(8)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void setMatchCountedUnbekannteIdLiefertError() throws Exception {
        send("{\"type\":\"setMatchCounted\",\"id\":\"gibt es nicht\",\"counted\":false}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Partie gibt es nicht:"), 10);
        assertTrue(err.path("text").asText().startsWith("Partie gibt es nicht:"), err.toString());
    }

    /** analyzeDeck fuer ein Precon: "deckAnalysis" mit plausiblen Zahlen (siehe DeckAnalysisTest). */
    @Test
    @Order(9)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void analyzeDeckLiefertDieDeckanalyse() throws Exception {
        send("{\"type\":\"analyzeDeck\",\"deck\":\"Abzan Armor [TDC] [2025]\"}");
        JsonNode msg = await("deckAnalysis", n -> true, 20);
        assertEquals("Abzan Armor [TDC] [2025]", msg.get("deck").asText());
        JsonNode a = msg.get("analysis");
        assertEquals(100, a.get("cards").asInt(), a.toString());
        assertTrue(a.get("lands").asInt() > 30, a.toString());
        assertTrue(a.get("avgCmc").asDouble() > 0, a.toString());
        assertTrue(a.get("curve").get("7+").isInt(), a.toString());
        assertTrue(a.get("sources").has("any"), a.toString());
        assertTrue(a.get("categories").get("ramp").asInt() > 0, a.toString());
        assertTrue(a.get("unclassified").asInt() > 0, a.toString());
        assertEquals("[\"W\",\"B\",\"G\"]", a.get("identity").toString(), a.toString());
    }

    /** analyzeDeck mit unbekanntem Namen: "error" statt still zu bleiben. */
    @Test
    @Order(10)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void analyzeDeckUnbekanntesDeckLiefertError() throws Exception {
        send("{\"type\":\"analyzeDeck\",\"deck\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Deckanalyse gibt es nicht:"), 10);
        assertEquals("Deckanalyse gibt es nicht: unbekanntes Deck", err.path("text").asText());
    }

    /** matchDetail mit unbekannter Id: "error" beginnend mit "Partie <id>: " statt still zu bleiben. */
    @Test
    @Order(11)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void matchDetailUnbekannteIdLiefertError() throws Exception {
        send("{\"type\":\"matchDetail\",\"id\":\"gibt es nicht\"}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Partie gibt es nicht:"), 10);
        assertEquals("Partie gibt es nicht: unbekannte Partie", err.path("text").asText());
    }

    /** setDeckBracket mit unbekanntem Deck: "error" (Text aus DeckStore.setBracket) statt still zu bleiben. */
    @Test
    @Order(12)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void setDeckBracketUnbekanntesDeckLiefertError() throws Exception {
        send("{\"type\":\"setDeckBracket\",\"name\":\"gibt es nicht\",\"bracket\":3}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Bracket gibt es nicht:"), 10);
        assertTrue(err.path("text").asText().contains("gibt es nicht"), err.toString());
    }

    /**
     * suggestCards mit unbekanntem Namen: "error" statt still zu bleiben - wie analyzeDeck. Bewusst NUR
     * der Fehlerpfad: forAnalysis(name) liefert dort null, BEVOR suggestCards() Edhrec#page() aufruft -
     * dieser Test loest also keinen echten EDHREC-Abruf aus. Ein Erfolgspfad ueber diese laufende Bridge
     * wuerde echtes edhrec (Feld in Bridge, echtes ~/.mtg-player als Zwischenspeicher) treffen; das ist
     * hier bewusst nicht getestet (siehe MessagesTest fuer die Nachrichtenform selbst).
     */
    @Test
    @Order(13)
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void suggestCardsUnbekanntesDeckLiefertError() throws Exception {
        send("{\"type\":\"suggestCards\",\"deck\":\"gibt es nicht\",\"roles\":[\"ramp\"]}");
        JsonNode err = await("error", n -> n.path("text").asText().startsWith("Kartenvorschläge gibt es nicht:"), 10);
        assertEquals("Kartenvorschläge gibt es nicht: unbekanntes Deck", err.path("text").asText());
    }

    /**
     * Wartet auf einen Zustand, in dem alle Sitze ihre volle Starthand (7 Karten) haben - ohne auf den Keep-Prompt zu
     * bestehen: {@link #awaitMulliganPrompt} kann nach dem "Play"-Klick am Zustandsbuendel vorbeilaufen
     * (siehe dessen Kommentar, "der Test hat sich selbst durch den Mulligan geklickt") und wartet dann
     * 90 s ins Leere. Hier genuegt jeder Zustand mit gefuellten Haenden; geklickt wird hoechstens der
     * Muenzwurf-Prompt (Play bzw. Startspieler waehlen), und das einmal.
     */
    private static JsonNode awaitAlleHaende(int seconds) throws InterruptedException {
        return awaitAlleHaende(seconds, 4);
    }

    private static JsonNode awaitAlleHaende(int seconds, int sitze) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        boolean playClicked = false;
        JsonNode letzter = null;
        int portraitSeq = -1;
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if (!"state".equals(n.path("type").asText())) continue;
            letzter = n;
            boolean alleMitHand = n.path("players").size() == sitze;
            for (JsonNode p : n.path("players")) {
                alleMitHand &= p.path("hand").size() >= 7;
            }
            if (alleMitHand) return n;
            // Mit mehr als zwei Sitzen waehlt der Muenzwurf-Gewinner den Startspieler per Klick aufs Portrait
            // (kein "Play"-Prompt): sich selbst waehlen. Je Prompt-Version einmal - ein veralteter Klick wird
            // verworfen, der naechste Zustand traegt die neue seq.
            int seq = n.path("prompt").path("seq").asInt();
            if (seq != portraitSeq && n.path("prompt").path("message").asText().contains("Who would you like to start")) {
                portraitSeq = seq;
                send("{\"type\":\"selectPlayer\",\"id\":" + n.get("me").asInt() + ",\"seq\":" + seq + "}");
            }
            if (!playClicked && "Play".equals(n.path("prompt").path("okLabel").asText())
                    && n.path("prompt").path("okEnabled").asBoolean()) {
                playClicked = true;
                send("{\"type\":\"ok\"}");
            }
        }
        throw new AssertionError("kein Zustand mit vier gefuellten Haenden innerhalb " + seconds + " s; letzter: "
                + (letzter == null ? "keiner" : letzter.path("prompt") + " haende="
                + letzter.path("players").findValues("hand").stream().map(JsonNode::size).toList()));
    }

    /**
     * "Partnerhand zeigen" ueber das echte Protokoll: der Haken geht vom startGame bis in den Snapshot,
     * den der Browser bekommt. Gleiche Stellung zweimal (2v2, Mensch + Sitz 1 gegen zwei KI), einmal mit,
     * einmal ohne Haken - die Starthand liegt beide Male auf dem Tisch, nur die Sicht darauf unterscheidet
     * sich. Der Gegner bleibt in beiden Faellen verdeckt. Die zweite Partie ohne Haken beweist auch, dass
     * der Haken nicht aus der ersten haengen bleibt (die WebGuiGame-Instanz lebt ueber Partien hinweg).
     */
    @Test
    @Order(14)
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void partnerHandHakenGehtVomStartGameBisInDenSnapshot() throws Exception {
        for (boolean haken : new boolean[] {true, false}) {
            send("{\"type\":\"startGame\",\"humanTeam\":1,\"revealPartnerHand\":" + haken
                    + ",\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},\"opponents\":["
                    + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Partner\",\"team\":1},"
                    + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 1\",\"team\":2},"
                    + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 2\",\"team\":2}]}");
            JsonNode mull = awaitAlleHaende(90);
            int me = mull.get("me").asInt();
            int checked = 0;
            for (JsonNode p : mull.get("players")) {
                if (p.get("id").asInt() == me) continue;
                boolean partner = p.path("team").asInt() == 1;
                assertTrue(p.get("hand").size() > 0, "Voraussetzung: die Hand ist gefuellt");
                for (JsonNode id : p.get("hand")) {
                    JsonNode card = mull.get("cards").get(id.asText());
                    boolean offen = partner && haken;
                    assertEquals(!offen, card.path("faceDown").asBoolean(),
                            (partner ? "Partnerhand" : "Gegnerhand") + " mit Haken=" + haken + ": " + card);
                    assertEquals(offen, card.hasNonNull("name"), "Name nur bei offener Karte: " + card);
                    checked++;
                }
            }
            assertTrue(checked >= 21, "drei fremde Haende gesehen: " + checked);
            // Aufgeben beendete hier nichts: in einer 2v2-Partie lebt das Team des Menschen mit dem Partner
            // weiter, die KI spielte die Partie zu Ende. Deshalb von aussen beenden (HumanMatch.end()).
            bridge.match().end();
            assertTrue(bridge.match().lastGameOver(), "die Partie ist wirklich beendet");
            // Was die beendete Partie noch auf den Draht schickt (Keep-Zustaende, gameOver), darf der
            // naechsten Runde nicht als deren Mulligan-Zustand erscheinen - sonst pruefte sie die alte Partie.
            Thread.sleep(1500);
            inbox.clear();
        }
    }

    /**
     * Nach dem Aufgeben im Team zuschauen (Aufgabe 11): in einer 2v2-Partie beendet das Aufgeben des Menschen
     * die Partie nicht, der Partner spielt weiter. Der Snapshot sagt, dass der eigene Sitz draussen ist
     * (daran schaltet der Browser auf die Zuschauer-Fusszeile) - und "End game" muss die Partie dann wirklich
     * beenden, statt eine Frage an einen Sitz zu stellen, der schon verloren hat (frueher: nichts passierte,
     * und startGame lehnte mit "Spiel laeuft noch" ab).
     */
    @Test
    @Order(15)
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void nachDemAufgebenImTeamBeendetEndGameDiePartie() throws Exception {
        send("{\"type\":\"startGame\",\"humanTeam\":1,"
                + "\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},\"opponents\":["
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Partner\",\"team\":1},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 1\",\"team\":2},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 2\",\"team\":2}]}");
        JsonNode start = awaitAlleHaende(90);
        int me = start.get("me").asInt();
        for (JsonNode p : start.get("players")) {
            assertFalse(p.get("lost").asBoolean(), "zu Beginn ist niemand draussen");
        }
        // Erst aufgeben, wenn der Mulligan der KI-Sitze vorbei ist und die Partie wirklich laeuft: wer waehrend
        // des Mulligans aufgibt, laesst Forge an den Zonen der anderen Sitze rutschen (ConcurrentModification
        // auf dem Spiel-Thread) - ein Testartefakt, das mit dem Aufgeben im echten Spiel nichts zu tun hat.
        awaitSpielLaeuft(90);

        // Aufgeben wie immer: Bestaetigung, dann ist der eigene Sitz draussen - der Partner haelt das Team im Spiel.
        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");
        JsonNode out = await("state", n -> seatLost(n, me), 60);
        for (JsonNode p : out.get("players")) {
            assertEquals(p.get("id").asInt() == me, p.get("lost").asBoolean(),
                    "nur der aufgebende Sitz steht als lost im Snapshot: " + p.get("name"));
        }
        assertTrue(bridge.match().isRunning(), "Voraussetzung: der Partner spielt weiter, die Partie laeuft");

        // "End game" aus der Zuschauer-Fusszeile: dieselbe Nachricht wie zuvor, jetzt muss sie wirken.
        java.util.Set<String> bekannt = new java.util.HashSet<>();
        matchStore.all().forEach(r -> bekannt.add(r.id()));
        inbox.clear();
        send("{\"type\":\"concede\"}");
        assertNotNull(await("gameOver", n -> true, 60), "End game beendet die Partie");
        assertFalse(bridge.match().isRunning(), "die Partie laeuft nicht mehr");
        assertTrue(bridge.match().lastGameOver(), "sie hat wirklich GameStage.GameOver erreicht");
        // Der Recorder schliesst sich ueber Forges Ereignisbus ab - kurz warten, bis der neue Datensatz steht.
        mtgplayer.stats.MatchRecord neu = null;
        for (long ende = System.currentTimeMillis() + 15_000; neu == null && System.currentTimeMillis() < ende; ) {
            neu = matchStore.all().stream().filter(r -> !bekannt.contains(r.id())).findFirst().orElse(null);
            if (neu == null) Thread.sleep(100);
        }
        assertNotNull(neu, "die beendete Partie steht in der Statistik");
        assertEquals("abgebrochen", neu.excludeReason(), "End game ist ein Abbruch");
        assertFalse(neu.counted(), "ein Abbruch zaehlt nicht");
    }

    /**
     * Aufgeben im Team gibt NUR den Sitz auf, nicht die Partie (Aufgabe 12): der Partner spielt weiter, der Mensch
     * schaut zu. Der Test wartet ausdruecklich NACH dem Aufgeben - Forges {@code AbstractGuiGame.concede()} schickt
     * nach dem Aufgeben noch {@code nextGameDecision(QUIT)}, und {@code HostedMatch} vergisst daraufhin das Spiel
     * (auf dem UI-Thread, einen Wimpernschlag spaeter). Ein Test, der den Zustand "lost" sofort prueft, sieht das
     * nicht. Danach beendet "End game" die Partie; der aufgebende Sitz steht im Datensatz weiter als
     * {@code Conceded}, die Partie als Abbruch.
     */
    @Test
    @Order(16)
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void imTeamGibtAufgebenNurDenSitzAufUndDerPartnerSpieltWeiter() throws Exception {
        send("{\"type\":\"startGame\",\"humanTeam\":1,"
                + "\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},\"opponents\":["
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Partner\",\"team\":1},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 1\",\"team\":2},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 2\",\"team\":2}]}");
        JsonNode start = awaitAlleHaende(90);
        int me = start.get("me").asInt();
        awaitSpielLaeuft(90);

        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");
        JsonNode out = await("state", n -> seatLost(n, me), 60);
        int turnBeiAufgabe = out.path("turn").asInt();
        int partner = -1;
        for (JsonNode p : out.get("players")) {
            if (p.get("id").asInt() != me && p.path("team").asInt() == 1) partner = p.get("id").asInt();
        }
        assertTrue(partner >= 0, "Voraussetzung: der Partner steht im Snapshot");

        // Die Partie laeuft weiter: der Zugzaehler rueckt vor, ohne dass der Mensch noch etwas tut.
        JsonNode spaeter = await("state", n -> n.path("turn").asInt() >= turnBeiAufgabe + 2, 150);
        assertTrue(seatLost(spaeter, me), "der aufgebende Sitz bleibt draussen");
        assertFalse(seatLost(spaeter, partner), "der Partner spielt weiter und ist nicht ausgeschieden");
        assertTrue(bridge.match().isRunning(), "die Partie laeuft nach dem Aufgeben weiter");

        java.util.Set<String> bekannt = new java.util.HashSet<>();
        matchStore.all().forEach(r -> bekannt.add(r.id()));
        inbox.clear();
        send("{\"type\":\"concede\"}");
        assertNotNull(await("gameOver", n -> true, 60), "End game beendet die Partie auch jetzt");
        assertFalse(bridge.match().isRunning(), "die Partie laeuft nicht mehr");
        mtgplayer.stats.MatchRecord neu = null;
        for (long ende = System.currentTimeMillis() + 15_000; neu == null && System.currentTimeMillis() < ende; ) {
            neu = matchStore.all().stream().filter(r -> !bekannt.contains(r.id())).findFirst().orElse(null);
            if (neu == null) Thread.sleep(100);
        }
        assertNotNull(neu, "die beendete Partie steht in der Statistik");
        assertEquals(List.of(true), neu.seats().stream().filter(s -> "Conceded".equals(s.lossReason()))
                        .map(mtgplayer.stats.MatchRecord.Seat::human).toList(),
                "genau ein Sitz steht als aufgegeben im Datensatz: der Mensch");
        assertEquals("abgebrochen", neu.excludeReason(), "End game bleibt ein Abbruch");
    }

    /**
     * Ohne Partner bleibt alles wie bisher (Aufgabe 12): im Free-for-all (drei Sitze, keine Teams) gibt es nichts zum
     * Zuschauen, und das Aufgeben des Menschen geht Forges Weg, nicht den Team-Zweig.
     *
     * <p>Was "wie bisher" hier heisst, ist gemessen und nicht dasselbe wie in einem Duell: Forge schickt nach dem
     * Aufgeben {@code nextGameDecision(QUIT)}, {@code HostedMatch} vergisst die Partie ({@code isRunning()} wird
     * falsch), der Sitz steht als {@code lost} im Snapshot - aber ein {@code gameOver} kommt von selbst NICHT, solange
     * zwei KI weiterleben (der Spiel-Thread bleibt in der Eingabe des Menschen stehen; nach 40 s Beobachtung bewegte
     * sich nichts mehr). Den Abschluss liefert dann der Knopf "End game". Der Test haelt genau das fest, damit eine
     * Aenderung am Team-Zweig den Rest nicht still mitverschiebt - er behauptet nicht, dass es so schoen ist.</p>
     */
    @Test
    @Order(17)
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void imFreeForAllGehtAufgebenDenWegVonForge() throws Exception {
        send("{\"type\":\"startGame\","
                + "\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},\"opponents\":["
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 1\"},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"Gegner 2\"}]}");
        JsonNode start = awaitAlleHaende(90, 3);
        int me = start.get("me").asInt();
        awaitSpielLaeuft(90);

        send("{\"type\":\"concede\"}");
        JsonNode confirm = await("choice", n -> "confirm".equals(n.path("kind").asText()), 30);
        send("{\"type\":\"answer\",\"id\":" + confirm.get("id").asInt() + ",\"value\":true}");
        await("state", n -> seatLost(n, me), 60);
        // Forges QUIT laeuft auf dem UI-Thread, einen Wimpernschlag nach dem Aufgeben.
        for (long ende = System.currentTimeMillis() + 15_000; bridge.match().isRunning() && System.currentTimeMillis() < ende; ) {
            Thread.sleep(50);
        }
        assertFalse(bridge.match().isRunning(), "Forges Weg: die Partie ist vom Tisch genommen (im Team bleibt sie laufen)");

        inbox.clear();
        send("{\"type\":\"concede\"}");
        assertNotNull(await("gameOver", n -> true, 60), "End game schliesst den Tisch");
    }

    /** Klickt sich durch Keep und Startspieler-Wahl, bis Zug 1 mit freier Prioritaet des Menschen steht. */
    private static void awaitSpielLaeuft(int seconds) throws InterruptedException {
        long end = System.currentTimeMillis() + seconds * 1000L;
        int geklickt = -1;
        JsonNode letzter = null;
        // awaitAlleHaende hat den Zustand mit dem Keep-Prompt schon verbraucht: den aktuellen noch einmal anfordern.
        send("{\"type\":\"requestState\"}");
        while (System.currentTimeMillis() < end) {
            JsonNode n = inbox.poll(Math.min(3000, Math.max(1, end - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
            if (n == null) {
                // Stille: ein Klick kann verloren gehen, solange die Engine den Prompt noch aufbaut - nochmal.
                geklickt = -1;
                send("{\"type\":\"requestState\"}");
                continue;
            }
            if (!"state".equals(n.path("type").asText())) continue;
            letzter = n;
            JsonNode pr = n.path("prompt");
            int seq = pr.path("seq").asInt();
            String ok = pr.path("okLabel").asText();
            if (n.path("turn").asInt() >= 1 && pr.path("okEnabled").asBoolean()
                    && !"Keep".equals(ok) && !"Play".equals(ok)
                    && !pr.path("message").asText().contains("Who would you like to start")) {
                return;
            }
            if (seq == geklickt) continue;
            if (pr.path("message").asText().contains("Who would you like to start")) {
                geklickt = seq;
                send("{\"type\":\"selectPlayer\",\"id\":" + n.get("me").asInt() + ",\"seq\":" + seq + "}");
            } else if (("Keep".equals(ok) || "Play".equals(ok)) && pr.path("okEnabled").asBoolean()) {
                geklickt = seq;
                send("{\"type\":\"ok\",\"seq\":" + seq + "}");
            }
        }
        throw new AssertionError("die Partie kam innerhalb " + seconds + " s nicht in Zug 1; letzter Zustand: "
                + (letzter == null ? "keiner" : "turn=" + letzter.path("turn") + " " + letzter.path("prompt")));
    }

    private static boolean seatLost(JsonNode state, int seat) {
        for (JsonNode p : state.path("players")) {
            if (p.path("id").asInt() == seat) return p.path("lost").asBoolean(false);
        }
        return false;
    }
}
