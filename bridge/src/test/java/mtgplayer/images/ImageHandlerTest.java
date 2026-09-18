package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import forge.item.PaperCard;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * End-to-End für {@link ImageHandler} über einen echten JDK-{@code HttpServer} auf einem freien
 * Port – prüft Methoden-Routing (GET/HEAD/POST), 404 für unbekannte Keys und dass ein Key wie ihn
 * der Browser über {@code encodeURIComponent} schickt (mit "|" → "%7C") korrekt dekodiert wird.
 */
class ImageHandlerTest {

    private HttpServer server;
    private ImageHandler handler;
    private String base;
    private String realKey;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @BeforeEach
    void start(@TempDir java.nio.file.Path dir) throws Exception {
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        realKey = felothar.getImageKey(false);
        assertTrue(realKey.contains("|"), "Key sollte ein '|' enthalten (Set-Trenner)");

        ImageCache cache = new ImageCache(dir, url -> new byte[] {1, 2, 3}, 0);
        handler = new ImageHandler(cache);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/img/", handler);
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        handler.close();
        server.stop(0);
    }

    private static String encode(String key) {
        // simuliert JS encodeURIComponent: Leerzeichen als %20, nicht '+'
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void getLiefertBildAusDemCache() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/img/" + encode(realKey))).GET().build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
        assertEquals("image/jpeg", res.headers().firstValue("Content-Type").orElse(null));
        assertArrayEquals(new byte[] {1, 2, 3}, res.body());
    }

    @Test
    void headLiefert200OhneBody() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/img/" + encode(realKey)))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
        assertEquals(0, res.body().length);
    }

    @Test
    void postWird405() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/img/" + encode(realKey)))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(405, res.statusCode());
    }

    @Test
    void unbekannterKeyWird404() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/img/t%3Agoblin")).GET().build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, res.statusCode());
    }

    @Test
    void pipeImKeyWirdKorrektDekodiert() throws Exception {
        // "|" wird von encodeURIComponent zu "%7C" – muss beim Server wieder zum echten Key werden,
        // sonst passt der sha1(imageKey)-Dateiname im ImageCache nie und es gibt immer 404.
        String encoded = encode(realKey);
        assertTrue(encoded.contains("%7C"), "encode() sollte '|' zu %7C machen: " + encoded);
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/img/" + encoded)).GET().build();
        HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, res.statusCode());
    }
}
