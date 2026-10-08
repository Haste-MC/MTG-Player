package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.Test;

import mtgplayer.protocol.Messages;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * WsServer.start() muss blockieren, bis der Port wirklich gebunden ist - sonst kann ein Client
 * verbinden wollen, bevor der Server horcht (Rennen beim Testaufbau und beim echten Start).
 */
class WsServerTest {

    private static WebSocketClient client(int port) throws Exception {
        return client(port, new ArrayList<>());
    }

    /** Wie {@link #client(int)}, nur dass jede Nachricht in {@code posteingang} landet. */
    private static WebSocketClient client(int port, List<String> posteingang) throws Exception {
        return new WebSocketClient(new URI("ws://127.0.0.1:" + port)) {
            @Override public void onOpen(ServerHandshake h) { }
            @Override public void onMessage(String m) { synchronized (posteingang) { posteingang.add(m); } }
            @Override public void onClose(int code, String reason, boolean remote) { }
            @Override public void onError(Exception ex) { }
        };
    }

    /** Wartet, bis die Bedingung gilt - die Nachrichten laufen auf einem anderen Thread ein. */
    private static boolean bisGilt(BooleanSupplier b, long ms) throws InterruptedException {
        long ende = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < ende) {
            if (b.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return b.getAsBoolean();
    }

    /** Letzte role-Nachricht eines Posteingangs, oder null. */
    private static String letzteRolle(List<String> posteingang) {
        synchronized (posteingang) {
            for (int i = posteingang.size() - 1; i >= 0; i--) {
                if (posteingang.get(i).contains("\"type\":\"role\"")) return posteingang.get(i);
            }
        }
        return null;
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
     * Ein zweiter Browser verdraengt den ersten NICHT mehr (bis 2026-10-08 tat er das, und zusammen
     * mit dem Wiederverbinden im Client warfen sich zwei Tabs endlos gegenseitig raus). Gemeldet
     * wird erst, wenn der LETZTE geht - sonst beendete sich die App im App-Modus, sobald jemand
     * einen zweiten Tab schliesst.
     */
    @Test
    void ersterBleibtVerbundenUndGemeldetWirdErstDerLetzte() throws Exception {
        CountDownLatch weg = new CountDownLatch(1);
        WsServer server = new WsServer(18085, msg -> { }, () -> { }, weg::countDown);
        server.start();
        try {
            WebSocketClient alt = client(18085);
            assertTrue(alt.connectBlocking(5, TimeUnit.SECONDS), "erste Verbindung steht");
            WebSocketClient neu = client(18085);
            assertTrue(neu.connectBlocking(5, TimeUnit.SECONDS), "zweite Verbindung steht");
            assertTrue(bisGilt(alt::isOpen, 1000), "der erste bleibt verbunden");

            neu.closeBlocking();
            assertFalse(weg.await(1, TimeUnit.SECONDS), "einer ist noch da - das ist kein 'Fenster zu'");
            alt.closeBlocking();
            assertTrue(weg.await(5, TimeUnit.SECONDS), "erst wenn der letzte geht, wird gemeldet");
        } finally {
            server.stop(1000);
        }
    }

    /** Alle sehen denselben Tisch - sonst waere Zusehen sinnlos. */
    @Test
    void jederVerbundeneBekommtDenZustand() throws Exception {
        List<String> einsPost = new ArrayList<>(), zweiPost = new ArrayList<>();
        WsServer server = new WsServer(18086, msg -> { }, () -> { });
        server.start();
        try {
            WebSocketClient eins = client(18086, einsPost);
            assertTrue(eins.connectBlocking(5, TimeUnit.SECONDS));
            WebSocketClient zwei = client(18086, zweiPost);
            assertTrue(zwei.connectBlocking(5, TimeUnit.SECONDS));

            server.send(new Messages.ErrorMsg("hallo an alle"));
            assertTrue(bisGilt(() -> einsPost.stream().anyMatch(m -> m.contains("hallo an alle")), 3000), "erster");
            assertTrue(bisGilt(() -> zweiPost.stream().anyMatch(m -> m.contains("hallo an alle")), 3000), "zweiter");

            eins.closeBlocking();
            zwei.closeBlocking();
        } finally {
            server.stop(1000);
        }
    }

    /**
     * Genau einer steuert: der erste. Der zweite sieht zu, seine Eingaben verpuffen - und per
     * {@code takeControl} dreht sich das um, sonst kaeme man je nach Reihenfolge des Verbindens nie
     * an die Steuerung.
     */
    @Test
    void nurDerSteuerndeWirdDurchgereichtUndUebernehmenDrehtDasUm() throws Exception {
        List<String> gehoert = Collections.synchronizedList(new ArrayList<>());
        List<String> einsPost = new ArrayList<>(), zweiPost = new ArrayList<>();
        WsServer server = new WsServer(18087, msg -> gehoert.add(msg.path("type").asText()), () -> { });
        server.start();
        try {
            WebSocketClient eins = client(18087, einsPost);
            assertTrue(eins.connectBlocking(5, TimeUnit.SECONDS));
            WebSocketClient zwei = client(18087, zweiPost);
            assertTrue(zwei.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(bisGilt(() -> letzteRolle(einsPost) != null && letzteRolle(zweiPost) != null, 3000), "Rollen kommen an");
            assertTrue(letzteRolle(einsPost).contains("\"control\":true"), "der erste steuert: " + letzteRolle(einsPost));
            assertTrue(letzteRolle(zweiPost).contains("\"control\":false"), "der zweite sieht zu: " + letzteRolle(zweiPost));

            zwei.send("{\"type\":\"vomZuschauer\"}");
            eins.send("{\"type\":\"vomSteuernden\"}");
            assertTrue(bisGilt(() -> gehoert.contains("vomSteuernden"), 3000), "der Steuernde kommt durch");
            assertFalse(gehoert.contains("vomZuschauer"), "der Zuschauer nicht: " + gehoert);

            zwei.send("{\"type\":\"takeControl\"}");
            assertTrue(bisGilt(() -> letzteRolle(zweiPost).contains("\"control\":true"), 3000), "der zweite hat uebernommen");
            assertTrue(letzteRolle(einsPost).contains("\"control\":false"), "der erste sieht jetzt zu");

            zwei.send("{\"type\":\"jetztIch\"}");
            assertTrue(bisGilt(() -> gehoert.contains("jetztIch"), 3000), "und kommt durch");

            eins.closeBlocking();
            zwei.closeBlocking();
        } finally {
            server.stop(1000);
        }
    }

    /** Geht der Steuernde, bleibt der Tisch nicht ohne Hand zurueck. */
    @Test
    void gehtDerSteuerndeRuecktDerNaechsteNach() throws Exception {
        List<String> zweiPost = new ArrayList<>();
        WsServer server = new WsServer(18088, msg -> { }, () -> { });
        server.start();
        try {
            WebSocketClient eins = client(18088);
            assertTrue(eins.connectBlocking(5, TimeUnit.SECONDS));
            WebSocketClient zwei = client(18088, zweiPost);
            assertTrue(zwei.connectBlocking(5, TimeUnit.SECONDS));
            assertTrue(bisGilt(() -> letzteRolle(zweiPost) != null, 3000));
            assertTrue(letzteRolle(zweiPost).contains("\"control\":false"), "zunaechst Zuschauer");

            eins.closeBlocking();
            assertTrue(bisGilt(() -> letzteRolle(zweiPost).contains("\"control\":true"), 3000),
                    "nach dem Abgang des Steuernden: " + letzteRolle(zweiPost));

            zwei.closeBlocking();
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
