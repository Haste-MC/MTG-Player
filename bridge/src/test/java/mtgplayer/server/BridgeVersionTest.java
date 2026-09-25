package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.app.UpdateCheck;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.CardStore;
import mtgplayer.stats.MatchStore;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Task 4 (App-Paket): {@code checkVersion()} in Bridge ueber das echte Protokoll, mit einem
 * eingesetzten {@link UpdateCheck} (kein Netz, wie bei {@link BridgeSuggestCardsTest}/Edhrec).
 *
 * <p>{@link mtgplayer.app.Version#current()} liefert in JEDER Testumgebung {@code "dev"} (kein
 * {@code version.txt} im App-Ordner, keine gepackte Jar mit Manifest-Eintrag - siehe VersionTest).
 * Ein Erfolgspfad "es kommt eine 'version'-Nachricht" liesse sich hier nur pruefen, indem
 * {@code version.txt} an die vom Produktivcode berechnete Stelle geschrieben wuerde - das liegt
 * ausserhalb von {@code @TempDir} (im Bridge-Modulordner selbst) und ist deshalb bewusst NICHT der
 * Weg dieses Tests. Stattdessen wird hier end-to-end genau das Sicherheitsversprechen geprueft, das
 * am meisten zaehlt: eine Entwicklungsfassung bekommt NIE einen Update-Hinweis, selbst wenn
 * UpdateCheck ein "neueres" Release meldet - und die uebrige Startsequenz (Lobby/matches) laeuft
 * dabei unveraendert durch.</p>
 */
class BridgeVersionTest {

    private static final int PORT = 18090;
    private static Bridge bridge;
    private static WebSocketClient client;
    private static final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @TempDir
    static Path tmp;

    @BeforeAll
    static void start() throws Exception {
        // Wie jeder andere Bridge*Test hier (siehe BridgeSuggestCardsTest etc.): ohne ForgeBoot.init()
        // scheitert Forges Localizer beim ersten Zugriff auf PhaseType (ueber WebGuiGame -> Stops) mit
        // einem ExceptionInInitializerError - und das vergiftet die Klasse fuer den Rest des JVM-Forks,
        // also auch fuer alle anderen Testklassen, die danach im selben Lauf drankommen.
        ForgeBoot.init();
        // Eine Quelle, die IMMER ein "neueres" Release meldet (Tag weit ueber jeder denkbaren echten
        // Fassung) - wenn trotzdem keine "version"-Nachricht ankommt, liegt das ausschliesslich am
        // dev-Schutz in Version.isNewer, nicht an einer zufaellig "nicht neueren" Testfassung.
        String json = "{\"tag_name\":\"v999.0.0\",\"body\":\"sha256: "
                + "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2\","
                + "\"assets\":[{\"name\":\"MTG-Player-999.0.0-win.zip\","
                + "\"browser_download_url\":\"https://example.invalid/MTG-Player-999.0.0-win.zip\"}]}";
        UpdateCheck updateCheck = new UpdateCheck(url -> json);

        bridge = new Bridge(PORT, new DeckStore(tmp.resolve("decks")), Archidekt.standard(),
                new MatchStore(tmp.resolve("matches.json")), new SubprocessGameRunner(),
                new mtgplayer.decks.Edhrec(url -> { throw new RuntimeException("kein Edhrec-Test hier"); },
                        tmp.resolve("edhrec")),
                CardStore.standard(), updateCheck);
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

    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void devFassungBekommtTrotzGemeldetemNeuerenReleaseKeineVersionNachricht() throws Exception {
        // Die normale Startsequenz muss unveraendert ankommen (Wiring nicht kaputt).
        boolean sahLobby = false;
        long ende = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < ende) {
            JsonNode n = inbox.poll(Math.max(1, ende - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if ("lobby".equals(n.path("type").asText())) { sahLobby = true; break; }
        }
        assertTrue(sahLobby, "Lobby-Nachricht wie gewohnt beim Verbinden");

        // Genug Zeit fuer den Hintergrund-Task aus checkVersion() (die eingesetzte Quelle antwortet
        // synchron/sofort, kein echtes Netz) - trotz "neuerem" Tag darf NIE "version" ankommen.
        Thread.sleep(500);
        assertFalse(inbox.stream().anyMatch(n -> "version".equals(n.path("type").asText())),
                "dev-Fassung darf nie einen Update-Hinweis bekommen");

        // Auch ein Nachzuegler (requestState, wie ein zweites Browserfenster) bekommt keinen.
        inbox.clear();
        client.send("{\"type\":\"requestState\"}");
        JsonNode again = inbox.poll(5, TimeUnit.SECONDS);
        assertTrue(again != null && "lobby".equals(again.path("type").asText()), "requestState antwortet wie gewohnt");
        assertFalse(inbox.stream().anyMatch(n -> "version".equals(n.path("type").asText())),
                "auch beim Nachzuegler kein Update-Hinweis fuer dev");
    }
}
