package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.CrashLog;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Der Rueckfallweg auf dem HTTP-Port: ein Ereignisstrom statt des WebSockets.
 *
 * <p>Gegen einen echten {@code HttpStatic} mit echtem HTTP-Klienten, nicht gegen Attrappen - die
 * interessante Frage ist ja gerade, ob ein gewoehnlicher Browser mit ausschliesslich HTTP
 * durchkommt (siehe {@code docs/superpowers/specs/2026-10-08-ein-port-design.md}).</p>
 */
class EreignisstromTest {

    private static final int PORT = 18089;
    /** Bewusst nicht der Standard 8081 und nicht der HTTP-Port: so faellt ein geratener Wert auf. */
    private static final int WS_PORT = 18090;
    private final List<String> gehoert = Collections.synchronizedList(new ArrayList<>());
    private final List<String> zeilen = Collections.synchronizedList(new ArrayList<>());
    private Klienten klienten;
    private HttpStatic http;
    private final HttpClient client = HttpClient.newHttpClient();
    private Thread leser;
    private Path logDatei;

    @BeforeEach
    void start(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("index.html"), "<html></html>");
        // bridge.log in den Testordner: der Test liest die Zeile nach, und ~/.mtg-player bleibt unberuehrt.
        logDatei = dir.resolve("logs").resolve("bridge.log");
        CrashLog.setFile(logDatei);
        klienten = new Klienten(m -> gehoert.add(m.path("type").asText()), () -> { });
        http = new HttpStatic(PORT, dir);
        http.ereignisstrom(klienten, WS_PORT);
        http.start();
    }

    @AfterEach
    void stop() {
        if (leser != null) leser.interrupt();
        http.stop();
        CrashLog.setFile(null);
    }

    /** Oeffnet den Strom im Hintergrund und sammelt seine Zeilen. */
    private void stromOeffnen() {
        leser = new Thread(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/ereignisse")).build();
                client.send(req, HttpResponse.BodyHandlers.ofLines()).body().forEach(zeilen::add);
            } catch (Exception e) {
                // Testende: der Strom wird abgeschnitten, das ist kein Fehler.
            }
        }, "strom-leser");
        leser.setDaemon(true);
        leser.start();
    }

    private static boolean bisGilt(BooleanSupplier b, long ms) throws InterruptedException {
        long ende = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < ende) {
            if (b.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return b.getAsBoolean();
    }

    private String datenZeileMit(String teil) {
        synchronized (zeilen) {
            return zeilen.stream().filter(z -> z.startsWith("data: ") && z.contains(teil)).findFirst().orElse(null);
        }
    }

    private String token() {
        String hello = datenZeileMit("\"type\":\"hello\"");
        int i = hello.indexOf("\"token\":\"") + 9;
        return hello.substring(i, hello.indexOf('"', i));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void stromLiefertTokenUndDanachJedeNachricht() throws Exception {
        stromOeffnen();
        assertTrue(bisGilt(() -> datenZeileMit("\"type\":\"hello\"") != null, 5000), "hello kommt: " + zeilen);
        assertFalse(token().isBlank(), "mit Token");
        assertTrue(bisGilt(() -> klienten.anzahl() == 1, 2000), "der Strom zaehlt als Klient");
        assertTrue(bisGilt(() -> datenZeileMit("\"type\":\"role\"") != null, 3000), "und bekommt seine Rolle");

        klienten.sendeAllen(new Messages.ErrorMsg("etwas ist passiert"));
        assertTrue(bisGilt(() -> datenZeileMit("etwas ist passiert") != null, 3000), "Nachrichten kommen an: " + zeilen);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void eingabePerPostErreichtDieBridge() throws Exception {
        stromOeffnen();
        assertTrue(bisGilt(() -> datenZeileMit("\"type\":\"hello\"") != null, 5000), "hello kommt");

        HttpResponse<Void> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/eingabe?token=" + token()))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"requestState\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(204, res.statusCode());
        assertTrue(bisGilt(() -> gehoert.contains("requestState"), 3000), "angekommen: " + gehoert);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void unbekanntesTokenWirdAbgewiesen() throws Exception {
        HttpResponse<Void> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/eingabe?token=gibtsnicht"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"requestState\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(404, res.statusCode(), "sonst wuerde eine verwaiste Eingabe stillschweigend wirken");
        assertFalse(gehoert.contains("requestState"));
    }

    /**
     * Der Browser soll nach einem 404 den Strom neu aufbauen - sieht er dabei nichts, bleibt als
     * einzige Spur eine Zeile in bridge.log. Ohne sie ist "Spiel starten tut nichts" eine Suche im Dunkeln.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void abgewiesenesTokenHinterlaesstEineZeileImLog() throws Exception {
        client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/eingabe?token=gibtsnicht"))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"requestState\"}")).build(),
                HttpResponse.BodyHandlers.discarding());
        String log = Files.exists(logDatei) ? Files.readString(logDatei) : "";
        assertTrue(log.contains("sse") && log.contains("gibtsnicht"), "Token samt Grund im Log: " + log);
        assertTrue(log.contains("unbekannt"), "und der Grund steht dabei: " + log);
    }

    /**
     * Die gepackte App waehlt freie Ports; die Seite kann den WebSocket-Port nicht raten. Wer die
     * Seite ausliefert, nennt ihn - auf demselben Ursprung, also nie an einer fremden Bridge.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void portsNenntDenWebSocketPortDerBridge() throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/ports")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "als JSON gekennzeichnet, sonst liefert der Datei-Server die index.html");
        assertEquals(WS_PORT, mtgplayer.protocol.Json.parse(res.body()).path("ws").asInt(), res.body());
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void portsNimmtNurGetAn() throws Exception {
        HttpResponse<Void> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/ports"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(405, res.statusCode());
    }

    /**
     * Der Client unterscheidet daran "Bridge faehrt noch hoch" von "dieser Browser kann keinen
     * WebSocket": ein angemeldeter Strom antwortet auf POST mit 405, ohne ihn liefert der
     * Datei-Server die index.html mit 200 (siehe ws.ts).
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void postAufDenStromMeldetDassEsIhnGibt() throws Exception {
        HttpResponse<Void> res = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + PORT + "/ereignisse"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(405, res.statusCode());
    }

    @Test
    void tokenAusAbfrage() {
        assertEquals("abc", Ereignisstrom.token("token=abc"));
        assertEquals("abc", Ereignisstrom.token("x=1&token=abc&y=2"));
        assertEquals("", Ereignisstrom.token("x=1"));
        assertEquals("", Ereignisstrom.token(null));
    }
}
