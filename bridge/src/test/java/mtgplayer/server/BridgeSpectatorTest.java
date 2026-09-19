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
import org.junit.jupiter.api.Test;
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
 * Spielt den Anfang eines KI-only-Spiels (Zuschauer-Sitz, Forges Spectator-Pfad über
 * HostedMatch/WatchLocalGame/InputPlaybackControl) über das echte Protokoll: Lobby → Spielstart
 * mit spectate:true → laufender Zuschauer-Zustand ("Pause") → Pause-Klick ("Resume") → Beenden.
 * Eigener Port (18084), damit dieser Test nicht mit {@link BridgeEndToEndTest} (18081) kollidiert.
 */
class BridgeSpectatorTest {

    private static final int PORT = 18084;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
    // wie BridgeEndToEndTest.seenLogLines: await() verwirft alles, was nicht auf den gesuchten Typ
    // passt - log-Zeilen, die waehrend des Wartens auf "Pause" durchlaufen, muessen deshalb hier
    // mitgeschnitten werden, sonst sind sie laengst verworfen, bis der Test danach fragt.
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

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void spectatorLobbyStartPauseEnd() throws Exception {
        JsonNode lobby = await("lobby", n -> true, 10);
        assertTrue(lobby.get("precons").size() > 100);

        send("{\"type\":\"startGame\",\"spectate\":true,\"opponents\":["
                + "{\"precon\":\"Abzan Armor [TDC] [2025]\",\"name\":\"KI 1\"},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 2\"}]}");

        // Der Spielstart pusht zuerst mehrfach den laufenden Zustand mit seq==0 (Forges
        // InputPlaybackControl setzt beim Erzeugen sofort die Pause/Tempo-Buttons, bevor die
        // InputQueue ueberhaupt ein Input traegt) - erst wenn Forge den Input tatsaechlich auf die
        // Queue legt (GameEventGameStarted), bumpt WebGuiGame.onInputChanged die seq einmalig und
        // bleibt danach fuer den Rest der Zuschauer-Sitzung stabil (siehe watchInputQueueOf). Auf
        // genau dieses Signal warten, statt auf die erste "Pause"-Nachricht, sonst schickt der Test
        // ein "ok" mit einer laengst ueberholten seq (siehe seqOk) und der Klick wird verworfen.
        JsonNode running = await("state", n -> n.path("spectator").asBoolean(false)
                && "Pause".equals(n.path("prompt").path("okLabel").asText())
                && n.path("prompt").path("seq").asInt() > 0, 90);
        assertFalse(running.has("me"), "Zuschauer hat keinen eigenen Sitz");
        assertEquals(2, running.get("players").size());
        for (JsonNode p : running.get("players")) {
            assertTrue(p.get("isAi").asBoolean(), "beide Sitze sind KIs");
        }

        // Wie beim menschlichen Sitz (BridgeEndToEndTest) muss Forges Spiel-Log auch beim Zuschauer
        // ankommen. Der Beobachter (WebGuiGame.watchLogOf) haengt bereits an diesem Punkt korrekt am
        // echten GameLog - siehe unten -, aber der "Pause"-Zustand kommt so frueh (Forges
        // InputPlaybackControl haengt sich noch VOR jeder Spielaktion ein, direkt bei ihrer Erzeugung,
        // siehe Kommentar oben bei running) an, dass zu diesem Zeitpunkt oft noch KEINE einzige
        // Log-Zeile erzeugt wurde: ein synchroner Check von seenLogLines direkt nach running waere
        // eine reine Wettlaufbedingung. Richtig ist, wie beim Reconnect-Check in BridgeEndToEndTest,
        // aktiv auf die naechste "log"-Nachricht mit kind zu warten - sie kommt kurz darauf (Zugwechsel
        // Zug 1, Priority-Weitergabe der KIs), seenLogLines faengt dabei alles mit, was schon vorher da war.
        JsonNode logLine = seenLogLines.stream().filter(n -> !n.path("kind").asText().isEmpty()).findFirst()
                .orElseGet(() -> {
                    try {
                        return await("log", n -> !n.path("kind").asText().isEmpty(), 20);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                });
        assertNotNull(logLine, "keine Log-Zeile mit kind beim Zuschauer angekommen");

        int seq = running.path("prompt").path("seq").asInt();
        send("{\"type\":\"ok\",\"seq\":" + seq + "}");
        JsonNode paused = await("state", n -> "Resume".equals(n.path("prompt").path("okLabel").asText()), 30);
        assertTrue(paused.path("spectator").asBoolean(false));

        send("{\"type\":\"concede\"}");
        JsonNode over = await("gameOver", n -> true, 30);
        assertNotNull(over);

        // "Beenden" darf das Spiel nicht nur aus HostedMatchs Buchhaltung loesen, sondern muss den
        // Game-Thread wirklich zum Stillstand bringen (siehe HumanMatch.end) - sonst kollidiert ein
        // verwaister Thread mit dem naechsten Spiel. match.end() laeuft dafuer als Hintergrund-Task
        // (siehe Bridge.handle, case "concede") - die gameOver-Nachricht kommt danach vom selben Task,
        // await() oben stellt also schon sicher, dass end() zu diesem Zeitpunkt durchgelaufen ist.
        assertTrue(bridge.match().lastGameOver(), "Zuschauer-Spiel muss beim Beenden wirklich GameStage.GameOver erreichen");

        // Ein zweites Spiel muss sauber starten - kein Zombie-Thread des ersten Spiels darf mehr in
        // die geteilte WebGuiGame hineinfunken. Wie beim ersten Spiel (siehe oben) erst auf die
        // vollstaendig eingerichtete "Pause"-Sitzung warten (seq>0 heisst: Forges InputPlaybackControl
        // sitzt bereits auf der InputQueue) statt nur auf irgendeinen fruehen turn<=1-Zustand - sonst
        // koennte @AfterAll/stop() dieses zweite Spiel schon mitten in seinem eigenen asynchronen
        // Setup beenden und mit dessen "GameStarted"-Verarbeitung wettlaufen.
        send("{\"type\":\"startGame\",\"spectate\":true,\"opponents\":["
                + "{\"precon\":\"Abzan Armor [TDC] [2025]\",\"name\":\"KI 1\"},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 2\"}]}");
        JsonNode fresh = await("state", n -> n.path("spectator").asBoolean(false)
                && n.path("turn").asInt() <= 1
                && "Pause".equals(n.path("prompt").path("okLabel").asText())
                && n.path("prompt").path("seq").asInt() > 0, 90);
        assertNotNull(fresh);

        // Sauber abschliessen, damit dieses zweite Spiel nicht als "noch laeuft" in eine andere
        // @Test-Methode dieser Klasse hineinragt (beide teilen sich denselben statischen Bridge/WS).
        send("{\"type\":\"concede\"}");
        assertNotNull(await("gameOver", n -> true, 30));
    }

    /**
     * Regression fuer den Deadlock aus dem vorigen Fix-Versuch: "Beenden" sofort klicken, WAEHREND
     * das Spiel noch laeuft (vor jedem "Pause"-Klick) - also bevor die Zuschauer-Sitzung ueberhaupt
     * einmal einen vollstaendig "settled" Zustand erreicht hat. Frueher blockierte der UI-Thread
     * dabei in HumanMatch.end() auf Game.isGameOver() (synchronized), waehrend der Game-Thread mitten
     * in Game.setGameOver(...) (haelt denselben Monitor) synchron auf ebendiesen UI-Thread wartete
     * (invokeInEdtAndWait) - klassischer Deadlock. Der Fix: end() pollt nur noch GameView.isGameOver()
     * (kein Game-Monitor) und der Zuschauer-Concede-Zweig in Bridge laeuft als Hintergrund-Task, nicht
     * mehr auf dem UI-Thread.
     */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void spectatorSofortigesBeendenOhneDeadlock() throws Exception {
        send("{\"type\":\"startGame\",\"spectate\":true,\"opponents\":["
                + "{\"precon\":\"Abzan Armor [TDC] [2025]\",\"name\":\"KI 1\"},"
                + "{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 2\"}]}");
        // Nur bestaetigen, dass das Spiel ueberhaupt gestartet ist (irgendein spectator-Zustand) -
        // absichtlich NICHT auf "Pause"/seq>0 warten, um moeglichst frueh, mitten im asynchronen
        // Spielaufbau, aufzugeben.
        assertNotNull(await("state", n -> n.path("spectator").asBoolean(false), 30));

        send("{\"type\":\"concede\"}");
        assertNotNull(await("gameOver", n -> true, 30), "kein Deadlock: gameOver kommt trotz sofortigem Beenden an");
        assertTrue(bridge.match().lastGameOver(), "auch beim sofortigen Beenden muss das Spiel wirklich GameStage.GameOver erreichen");
    }
}
