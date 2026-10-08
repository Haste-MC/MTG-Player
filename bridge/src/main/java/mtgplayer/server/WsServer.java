package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.forge.CrashLog;
import mtgplayer.gui.Transport;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Mehrere Browser am selben Tisch: alle sehen denselben Zustand, genau EINER steuert.
 *
 * <p><b>Warum nicht mehr nur einer.</b> Bis zum 2026-10-08 hielt diese Klasse genau einen Klienten
 * und warf bei einer neuen Verbindung die alte raus. Zusammen mit dem Wiederverbinden in
 * {@code web/src/ws.ts} (eine Sekunde nach {@code onclose}) ergab das eine Schaukel: zwei offene
 * Tabs warfen sich endlos gegenseitig raus, und beide zeigten dauerhaft "Verbinde mit der
 * Bridge …". Genau so sah es aus, als Kevin einer anderen Sitzung beim Spielen zusehen wollte.</p>
 *
 * <p><b>Wer steuert.</b> Der erste Klient. Wer spaeter dazukommt, sieht zu - das ist der Fall, um
 * den es geht: die spielende Sitzung ist schon dran, man setzt sich daneben. Geht der Steuernde,
 * rueckt der am laengsten wartende Zuschauer nach, damit kein Tisch ohne Hand zurueckbleibt. Ein
 * Zuschauer kann die Steuerung ausdruecklich holen ({@code {"type":"takeControl"}}), denn wer
 * zuerst verbunden hat, ist nicht zwingend der, der spielen will.</p>
 *
 * <p>Nachrichten gehen nur vom Steuernden an {@code inbound}; {@link #send} ist thread-sicher
 * (Java-WebSocket serialisiert intern) und geht an alle. Ohne Klient wird Gesendetes still
 * verworfen - der Browser holt sich den Zustand per requestState.</p>
 */
public final class WsServer extends WebSocketServer implements Transport {

    private final Consumer<JsonNode> inbound;
    private final Runnable onOpen;
    private final Runnable onClientGone;
    /** Verbundene Browser in der Reihenfolge ihres Eintreffens; der erste steuert (siehe Klassendoc). */
    private final List<WebSocket> clients = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> started = new CompletableFuture<>();

    public WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen) {
        this(port, inbound, onOpen, () -> { });
    }

    /**
     * @param onClientGone laeuft, wenn der LETZTE Klient die Verbindung verliert (siehe {@link #onClose}) -
     *                     im App-Modus das Signal, dass das Fenster zu ist (siehe {@code IdleExit}).
     */
    public WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen, Runnable onClientGone) {
        super(new InetSocketAddress(bindAddress(), port));
        this.inbound = inbound;
        this.onOpen = onOpen;
        this.onClientGone = onClientGone;
        setReuseAddr(true);
    }

    /** Blockiert, bis der Port gebunden ist (oder das Binden scheitert) – kein Rennen mit Clients, die sofort verbinden. */
    @Override
    public void start() {
        super.start();
        try {
            started.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IllegalStateException("WebSocket-Server konnte nicht starten", e);
        }
    }

    /** Bind-Adresse: -Dmtgplayer.bind, Standard 0.0.0.0 – noetig, damit Windows unter WSL2 den Server per localhost erreicht. */
    static String bindAddress() {
        return System.getProperty("mtgplayer.bind", "0.0.0.0");
    }

    @Override
    public void send(Object message) {
        String json = Json.toJson(message);
        for (WebSocket c : clients) {
            sendeAn(c, json);
        }
    }

    /** Einer von mehreren: ein abgerissener Klient darf die uebrigen nicht um ihre Nachricht bringen. */
    private static void sendeAn(WebSocket c, String json) {
        if (c == null || !c.isOpen()) {
            return;
        }
        try {
            c.send(json);
        } catch (RuntimeException e) {
            // deckt WebsocketNotConnectedException (Subklasse) mit ab - ein Client, der mitten im
            // Senden abreisst, darf den Game-Thread nicht mitreissen. Nur ins Log: ein
            // abgerissener Client ist kein Absturz, und die Meldung erreichte ihn ohnehin nicht.
            CrashLog.note("ws", "Senden fehlgeschlagen: " + e, e);
        }
    }

    /** Der Steuernde, oder {@code null} wenn niemand verbunden ist. */
    WebSocket steuernder() {
        return clients.isEmpty() ? null : clients.get(0);
    }

    /** Jedem seine Rolle schicken - der Steuernde erfaehrt dabei, wie viele zusehen. */
    private void rollenMelden() {
        WebSocket chef = steuernder();
        int zuschauer = Math.max(0, clients.size() - 1);
        for (WebSocket c : clients) {
            sendeAn(c, Json.toJson(new Messages.Role(c == chef, zuschauer)));
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        clients.add(conn);                       // hinten dran: der erste bleibt der Steuernde
        rollenMelden();
        try {
            onOpen.run();
        } catch (RuntimeException e) {
            // wie onMessage: ein Fehler beim Connect-Callback (z. B. Lobby-Aufbau) darf den Socket-Thread
            // nicht mitreissen - der Client erfaehrt es als "error" statt einer stillen Verbindung.
            conn.send(Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (!clients.remove(conn)) {
            return;                              // kannten wir nicht (doppeltes onClose)
        }
        if (clients.isEmpty()) {
            try {
                onClientGone.run();              // erst beim LETZTEN: im App-Modus heisst das "Fenster zu"
            } catch (RuntimeException e) {
                // wie onOpen/onMessage: kein Rueckruf darf den Socket-Thread mitreissen
                CrashLog.note("ws", "onClientGone fehlgeschlagen: " + e, e);
            }
            return;
        }
        rollenMelden();                          // ging der Steuernde, rueckt der naechste nach
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonNode msg = Json.parse(message);
            if ("takeControl".equals(msg.path("type").asText())) {
                uebernehmen(conn);               // darf jeder - sonst kaeme man nie an die Steuerung
                return;
            }
            if (conn != steuernder()) {
                return;                          // Zuschauer schauen zu; die Oberflaeche sperrt das schon
            }
            inbound.accept(msg);
        } catch (RuntimeException e) {
            sendeAn(conn, Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            CrashLog.note("ws", "Nachricht fehlgeschlagen: " + e, e);
        }
    }

    /** Diesen Klienten nach vorne holen; danach kennt jeder seine neue Rolle. */
    private void uebernehmen(WebSocket conn) {
        if (conn == steuernder() || !clients.contains(conn)) {
            return;
        }
        clients.remove(conn);
        clients.add(0, conn);
        rollenMelden();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn == null) {
            // Fehler ohne Verbindung betrifft den Server selbst (z. B. Port belegt) - start() muss das erfahren.
            started.completeExceptionally(ex);
        }
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket auf ws://" + bindAddress() + ":" + getPort());
        started.complete(null);
    }
}
