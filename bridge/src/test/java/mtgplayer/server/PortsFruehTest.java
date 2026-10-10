package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /ports} muss schon antworten, BEVOR der Ereignisstrom angemeldet ist. Im gepackten
 * Programm geht das Fenster absichtlich vor {@code ForgeBoot.init()} auf; der Strom kommt erst nach
 * dem Kartenladen dazu. Faellt diese Reihenfolge weg, raet die Seite in genau diesen 20-60 Sekunden
 * wieder Port 8081 - und findet dort im Netz womoeglich die Bridge eines fremden Rechners
 * (beobachtet am 2026-10-11: die Partie eines Kollegen lief auf dem Rechner des Entwicklers).
 */
class PortsFruehTest {

    @Test
    void portsAntwortetOhneAngemeldetenStrom() throws Exception {
        Path web = Files.createTempDirectory("web");
        Files.writeString(web.resolve("index.html"), "<html></html>");
        HttpStatic http = new HttpStatic(18099, web);
        try {
            http.ports(18098);          // wie in Main: vor start(), ohne Ereignisstrom
            http.start();

            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:18099/ports")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, res.statusCode(), res.body());
            assertEquals(18098, Json.parse(res.body()).path("ws").asInt(),
                    "ohne diese Antwort raet der Klient 8081 - siehe Klassenkommentar");
        } finally {
            http.stop();
        }
    }
}
