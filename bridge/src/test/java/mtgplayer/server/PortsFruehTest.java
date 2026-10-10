package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /**
     * Warum genau diese Ports: die uebrigen Bridge-Tests belegen 18081-18091 und 18100 (jede Klasse ihren
     * eigenen, grep nach "18[01]" zeigt die Liste); 18098/18099 liegen in der Luecke davor und kollidieren
     * mit keinem. Gebunden wird nur der HTTP-Port - der WebSocket-Port ist hier eine Zahl, die /ports nennt.
     * Beide sind weder 8080/8081 (Standard der App) noch ein Wert, den ein Test aus der Reihe waehlt.
     * Kommt ein Test mit neuem Port hinzu: erst die Liste pruefen, dann diese Zahlen meiden.
     */
    private static final int HTTP_PORT = 18099;
    private static final int WS_PORT = 18098;

    @Test
    void portsAntwortetOhneAngemeldetenStrom(@TempDir Path web) throws Exception {
        Files.writeString(web.resolve("index.html"), "<html></html>");
        HttpStatic http = new HttpStatic(HTTP_PORT, web);
        try {
            http.ports(WS_PORT);          // wie in Main: vor start(), ohne Ereignisstrom
            http.start();

            HttpResponse<String> res = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HTTP_PORT + "/ports")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, res.statusCode(), res.body());
            assertEquals(WS_PORT, Json.parse(res.body()).path("ws").asInt(),
                    "ohne diese Antwort raet der Klient 8081 - siehe Klassenkommentar");
        } finally {
            http.stop();
        }
    }
}
