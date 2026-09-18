package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.gui.Transport;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Genau ein Browser-Client. Nachrichten vom Client gehen als JsonNode an {@code inbound};
 * {@link #send} ist thread-sicher (Java-WebSocket serialisiert intern). Ohne Client wird
 * gesendetes still verworfen – der Browser holt sich den Zustand per requestState.
 */
public final class WsServer extends WebSocketServer implements Transport {

    private final Consumer<JsonNode> inbound;
    private final Runnable onOpen;
    private volatile WebSocket client;
    private final CompletableFuture<Void> started = new CompletableFuture<>();

    public WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen) {
        super(new InetSocketAddress(bindAddress(), port));
        this.inbound = inbound;
        this.onOpen = onOpen;
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
        WebSocket c = client;
        if (c != null && c.isOpen()) {
            try {
                c.send(Json.toJson(message));
            } catch (RuntimeException e) {
                // deckt WebsocketNotConnectedException (Subklasse) mit ab - ein Client, der mitten im
                // Senden abreisst, darf den Game-Thread nicht mitreissen
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        WebSocket old = client;
        client = conn;
        if (old != null && old.isOpen() && old != conn) {
            old.close(1000, "neuer Client");
        }
        onOpen.run();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (client == conn) client = null;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            inbound.accept(Json.parse(message));
        } catch (RuntimeException e) {
            conn.send(Json.toJson(new Messages.ErrorMsg("Bridge: " + e)));
            e.printStackTrace();
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
