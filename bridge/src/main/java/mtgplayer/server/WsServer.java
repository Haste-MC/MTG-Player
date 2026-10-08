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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private final Runnable onOpen;
    /** Die gemeinsame Buchhaltung - auch der Ereignisstrom auf dem HTTP-Port traegt sich dort ein. */
    private final Klienten klienten;
    /** Zu jeder Verbindung ihr Klient, damit onClose/onMessage denselben Eintrag finden. */
    private final Map<WebSocket, Klient> zuordnung = new ConcurrentHashMap<>();
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
        this.onOpen = onOpen;
        this.klienten = new Klienten(inbound, onClientGone);
        setReuseAddr(true);
    }

    /** Die Buchhaltung, damit sich auch der Ereignisstrom auf dem HTTP-Port dort eintragen kann. */
    public Klienten klienten() {
        return klienten;
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
        klienten.sendeAllen(message);
    }

    /** Ein Browser am WebSocket. Die Umsetzung von {@link Klient} fuer diesen Weg. */
    private record WsKlient(WebSocket conn) implements Klient {
        @Override public void sende(String json) { conn.send(json); }
        @Override public boolean offen() { return conn.isOpen(); }
        @Override public void schliesse() { conn.close(1000, "Senden fehlgeschlagen"); }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        Klient k = new WsKlient(conn);
        zuordnung.put(conn, k);
        klienten.dazu(k);
        try {
            onOpen.run();
        } catch (RuntimeException e) {
            // wie onMessage: ein Fehler beim Connect-Callback (z. B. Lobby-Aufbau) darf den Socket-Thread
            // nicht mitreissen - der Client erfaehrt es als "error" statt einer stillen Verbindung.
            conn.send(Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            CrashLog.note("ws", "Connect-Rueckruf fehlgeschlagen: " + e, e);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        Klient k = zuordnung.remove(conn);
        if (k != null) {
            klienten.weg(k);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            Klient k = zuordnung.get(conn);
            if (k != null) {
                klienten.empfange(k, Json.parse(message));
            }
        } catch (RuntimeException e) {
            conn.send(Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            CrashLog.note("ws", "Nachricht fehlgeschlagen: " + e, e);
        }
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
