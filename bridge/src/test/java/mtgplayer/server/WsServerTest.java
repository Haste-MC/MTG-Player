package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WsServer.start() muss blockieren, bis der Port wirklich gebunden ist - sonst kann ein Client
 * verbinden wollen, bevor der Server horcht (Rennen beim Testaufbau und beim echten Start).
 */
class WsServerTest {

    private static WebSocketClient client(int port) throws Exception {
        return new WebSocketClient(new URI("ws://127.0.0.1:" + port)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { }
        };
    }

    @Test
    void startBlockiertBisPortGebundenIst() throws Exception {
        WsServer server = new WsServer(18082, msg -> { }, () -> { });
        server.start();
        try {
            WebSocketClient c = client(18082);
            assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "Verbindung direkt nach start() muss klappen");
            c.closeBlocking();
        } finally {
            server.stop(1000);
        }
    }

    /**
     * Im App-Modus haengt daran, dass sich die App ueberhaupt beenden laesst: das Fenster ist ein
     * eigener Prozess, und nur diese Meldung sagt der Bridge, dass es zu ist (siehe {@code IdleExit}).
     */
    @Test
    void letzterKlientWegWirdGemeldet() throws Exception {
        CountDownLatch weg = new CountDownLatch(1);
        WsServer server = new WsServer(18084, msg -> { }, () -> { }, weg::countDown);
        server.start();
        try {
            WebSocketClient c = client(18084);
            assertTrue(c.connectBlocking(5, TimeUnit.SECONDS), "Verbindung steht");
            c.closeBlocking();
            assertTrue(weg.await(5, TimeUnit.SECONDS), "das Trennen des einzigen Klienten wird gemeldet");
        } finally {
            server.stop(1000);
        }
    }

    /**
     * Loest ein NEUER Klient den alten ab (WsServer haelt genau einen), ist niemand weg - der alte wird
     * nur ersetzt. Wuerde das als "Fenster zu" durchgehen, beendete sich die App ausgerechnet beim
     * Neuladen der Seite.
     */
    @Test
    void abloesungDurchNeuenKlientenIstKeinTrennen() throws Exception {
        CountDownLatch weg = new CountDownLatch(1);
        WsServer server = new WsServer(18085, msg -> { }, () -> { }, weg::countDown);
        server.start();
        try {
            WebSocketClient alt = client(18085);
            assertTrue(alt.connectBlocking(5, TimeUnit.SECONDS), "erste Verbindung steht");
            WebSocketClient neu = client(18085);
            assertTrue(neu.connectBlocking(5, TimeUnit.SECONDS), "zweite Verbindung steht");

            assertFalse(weg.await(1, TimeUnit.SECONDS), "die Abloesung des alten Klienten ist kein Trennen");

            neu.closeBlocking();
            assertTrue(weg.await(5, TimeUnit.SECONDS), "erst wenn der verbliebene geht, wird gemeldet");
        } finally {
            server.stop(1000);
        }
    }

    @Test
    void startAufBelegtemPortWirftException() throws Exception {
        WsServer first = new WsServer(18083, msg -> { }, () -> { });
        first.start();
        try {
            WsServer second = new WsServer(18083, msg -> { }, () -> { });
            assertThrows(IllegalStateException.class, second::start);
        } finally {
            first.stop(1000);
        }
    }
}
