package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.gui.Transport;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
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

    public WsServer(int port, Consumer<JsonNode> inbound, Runnable onOpen) {
        super(new InetSocketAddress("127.0.0.1", port));
        this.inbound = inbound;
        this.onOpen = onOpen;
        setReuseAddr(true);
    }

    @Override
    public void send(Object message) {
        WebSocket c = client;
        if (c != null && c.isOpen()) {
            c.send(Json.toJson(message));
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
        ex.printStackTrace();
    }

    @Override
    public void onStart() {
        System.out.println("WebSocket auf ws://127.0.0.1:" + getPort());
    }
}
