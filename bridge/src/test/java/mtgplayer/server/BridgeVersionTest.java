package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.app.UpdateCheck;
import mtgplayer.app.Version;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task 4 (App-Paket): {@code checkVersion()} in Bridge ueber das echte Protokoll, mit einem
 * eingesetzten {@link UpdateCheck} (kein Netz, wie bei {@link BridgeSuggestCardsTest}/Edhrec) UND
 * einer eingesetzten "eigenen Fassung" (der {@code Supplier<String>}-Konstruktor - siehe Bridge).
 * Jeder Test startet seine eigene Bridge auf demselben Port (nacheinander, JUnit fuehrt Methoden
 * einer Klasse sequenziell aus), damit sich Dev-Schutz und Erfolgspfad mit unterschiedlichen
 * "eigenen Fassungen" unabhaengig voneinander pruefen lassen.
 */
class BridgeVersionTest {

    private static final int PORT = 18090;
    private static final String HASH = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";

    private Bridge bridge;
    private WebSocketClient client;
    private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();

    @TempDir
    Path tmp;

    @BeforeAll
    static void initForge() {
        // Wie jeder andere Bridge*Test hier (siehe BridgeSuggestCardsTest etc.): ohne ForgeBoot.init()
        // scheitert Forges Localizer beim ersten Zugriff auf PhaseType (ueber WebGuiGame -> Stops) mit
        // einem ExceptionInInitializerError - und das vergiftet die Klasse fuer den Rest des JVM-Forks,
        // also auch fuer alle anderen Testklassen, die danach im selben Lauf drankommen. init() ist
        // idempotent (siehe ForgeBoot), darum reicht ein einziger Aufruf fuer die ganze Klasse.
        ForgeBoot.init();
    }

    private void start(UpdateCheck updateCheck, String currentVersion) throws Exception {
        bridge = new Bridge(PORT, new DeckStore(tmp.resolve("decks")), Archidekt.standard(),
                new MatchStore(tmp.resolve("matches.json")), new SubprocessGameRunner(),
                new Edhrec(url -> { throw new RuntimeException("kein Edhrec-Test hier"); }, tmp.resolve("edhrec")),
                CardStore.standard(), updateCheck, () -> currentVersion);
        bridge.start();
        client = new WebSocketClient(new URI("ws://127.0.0.1:" + PORT)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { inbox.add(Json.parse(m)); }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { ex.printStackTrace(); }
        };
        assertTrue(client.connectBlocking(10, TimeUnit.SECONDS), "WebSocket-Verbindung");
    }

    @AfterEach
    void stop() throws Exception {
        client.closeBlocking();
        bridge.stop();
    }

    /** Wie in BridgeSuggestCardsTest: unerhebliche Zwischennachrichten (matches, log, state, ...)
     *  werden ueberlesen, bis die gesuchte Art ankommt oder die Zeit ablaeuft. */
    private JsonNode await(String type, int seconds) throws InterruptedException {
        long ende = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < ende) {
            JsonNode n = inbox.poll(Math.max(1, ende - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            if (n == null) break;
            if (type.equals(n.path("type").asText())) return n;
        }
        throw new AssertionError("keine Nachricht '" + type + "' innerhalb " + seconds + " s");
    }

    private static String releaseJson(String tag, String zipUrl, String zipName, String notes) {
        return releaseJsonMitShaAsset(tag, zipUrl, zipName, "https://example.invalid/SHA256SUMS", notes);
    }

    private static String releaseJsonMitShaAsset(String tag, String zipUrl, String zipName, String shaUrl,
                                                   String notes) {
        return "{\"tag_name\":\"" + tag + "\",\"body\":\"" + notes + "\",\"assets\":["
                + "{\"name\":\"" + zipName + "\",\"browser_download_url\":\"" + zipUrl + "\"},"
                + "{\"name\":\"SHA256SUMS\",\"browser_download_url\":\"" + shaUrl + "\"}]}";
    }

    /** Kritischer Review-Befund: eine unbekannte/Entwicklungsfassung ({@link Version#DEV}) darf
     *  GitHub gar nicht erst fragen - nicht nur "keine Nachricht ankommen lassen" (das pruefte die
     *  Vorversion dieses Tests schon), sondern die eingesetzte Quelle darf NIE aufgerufen werden. Ein
     *  Zaehler in der Quelle beweist das direkt, statt sich auf Trial-and-Error-Timing zu verlassen. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void devFassungFragtGithubGarNichtErstAn() throws Exception {
        AtomicInteger quellAufrufe = new AtomicInteger();
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            quellAufrufe.incrementAndGet();
            return releaseJson("v999.0.0", "https://example.invalid/zip", "MTG-Player-999.0.0-win.zip", "");
        });
        start(updateCheck, Version.DEV);

        await("lobby", 5);

        // Genug Zeit, falls checkVersion() faelschlich doch einen Hintergrund-Task angestossen haette.
        Thread.sleep(500);
        assertEquals(0, quellAufrufe.get(), "dev darf UpdateCheck#latest() nie ausloesen - auch kein Netzversuch");
        assertFalse(inbox.stream().anyMatch(n -> "version".equals(n.path("type").asText())));

        // Auch ein Nachzuegler (requestState, wie ein zweites Browserfenster) bekommt keinen Hinweis.
        inbox.clear();
        client.send("{\"type\":\"requestState\"}");
        await("lobby", 5);
        assertFalse(inbox.stream().anyMatch(n -> "version".equals(n.path("type").asText())));
        assertEquals(0, quellAufrufe.get(), "requestState darf ebenfalls keinen Abruf nachholen");
    }

    /** Erfolgszweig, bisher ungetestet (Review-Befund): eine ECHTE eigene Fassung plus ein ECHTES
     *  neueres Release muss als "version"-Nachricht mit den richtigen Werten in den richtigen Feldern
     *  ankommen - deckt sowohl {@code new Messages.VersionMsg(...)} (Feldreihenfolge!) als auch das
     *  Zwischenspeichern/Senden in {@code checkVersion()} wirklich ab. */
    @Test
    @Timeout(value = 1, unit = TimeUnit.MINUTES)
    void echtesUpdateKommtMitDenRichtigenFeldernAn() throws Exception {
        String zipName = "MTG-Player-1.3.0-win.zip";
        String zipUrl = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/" + zipName;
        String shaUrl = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/SHA256SUMS";
        // Zwei verschiedene URLs, zwei verschiedene Antworten - wie beim echten GitHub-Release
        // (releases/latest liefert das JSON, das SHA256SUMS-Asset seinen eigenen zweiten Abruf).
        Map<String, String> antworten = Map.of(
                "https://api.github.com/repos/Haste-MC/MTG-Player/releases/latest",
                releaseJsonMitShaAsset("v1.3.0", zipUrl, zipName, shaUrl, "Neu: Sparring-Verbesserungen"),
                shaUrl, HASH + "  " + zipName + "\n");
        UpdateCheck updateCheck = new UpdateCheck(url -> {
            String body = antworten.get(url);
            if (body == null) throw new RuntimeException("keine eingesetzte Antwort fuer " + url);
            return body;
        });
        start(updateCheck, "1.2.0");

        // checkVersion() laeuft in einem eigenen Hintergrund-Thread, unabhaengig von
        // onClientConnected() - "version" kann vor, zwischen oder nach lobby/matches ankommen
        // (direkter Broadcast, sobald der Client verbunden ist) ODER erst ueber das Nachreichen in
        // onClientConnected() (falls der Hintergrund-Task noch nicht fertig war). await() ueberliest
        // alles andere (lobby, matches, ...), bis "version" da ist.
        JsonNode msg = await("version", 20);

        assertEquals("1.2.0", msg.get("current").asText());
        assertEquals("1.3.0", msg.get("latest").asText(), "fuehrendes 'v' des Tags abgeschnitten");
        assertEquals(zipUrl, msg.get("url").asText());
        assertEquals(HASH, msg.get("sha256").asText());
        assertEquals("Neu: Sparring-Verbesserungen", msg.get("notes").asText());

        // Ein Nachzuegler bekommt denselben, zwischengespeicherten Stand nachgereicht.
        inbox.clear();
        client.send("{\"type\":\"requestState\"}");
        JsonNode again = await("version", 10);
        assertEquals("1.3.0", again.get("latest").asText());
    }
}
