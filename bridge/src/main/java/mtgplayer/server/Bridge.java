package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.gui.GuiBase;
import mtgplayer.forge.Precons;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.HumanMatch;
import mtgplayer.protocol.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Verdrahtet WebSocket ↔ WebGuiGame/HumanMatch. Eingaben, die Forges Input-System berühren,
 * laufen über den UI-Thread; answer geht direkt an den ChoiceBroker (der Game-Thread wartet darauf).
 * concede läuft als Hintergrund-Task, nicht auf dem UI-Thread – AbstractGuiGame.concede() marschalt
 * seine Folgeaufrufe selbst, ein blockierender Dialog darin würde sonst den UI-Thread lahmlegen.
 */
public final class Bridge {

    private final WsServer ws;
    private final WebGuiGame gui;
    private final HumanMatch match = new HumanMatch();

    public Bridge(int wsPort) {
        this.ws = new WsServer(wsPort, this::handle, this::onClientConnected);
        this.gui = new WebGuiGame(ws);
    }

    public void start() {
        ws.start();
    }

    public void stop() throws InterruptedException {
        match.end();
        ws.stop(1000);
    }

    private void onClientConnected() {
        ws.send(new Messages.Lobby(Precons.names()));
        // state vor choice: beides in EINEM Runnable, sonst kann pending() vor pushState() beim Client ankommen
        GuiBase.getInterface().invokeInEdtLater(() -> {
            gui.pushState();
            gui.broker().pending().forEach(ws::send);
        });
    }

    void handle(JsonNode msg) {
        String type = msg.path("type").asText("");
        switch (type) {
            case "startGame" -> startGame(msg);
            case "selectCard" -> ui(() -> gui.onSelectCard(msg.path("id").asInt(), msg.path("alt").asBoolean(false), seqOf(msg)));
            case "selectPlayer" -> ui(() -> gui.onSelectPlayer(msg.path("id").asInt(), seqOf(msg)));
            case "ok" -> ui(() -> gui.onOk(seqOf(msg)));
            case "cancel" -> ui(() -> gui.onCancel(seqOf(msg)));
            case "answer" -> {
                int id = msg.path("id").asInt();
                if (!gui.broker().answer(id, msg.get("value"))) {
                    ws.send(new Messages.ErrorMsg("answer fuer unbekannte id " + id));
                }
            }
            case "concede" -> GuiBase.getInterface().runBackgroundTask("concede", () -> {
                try {
                    gui.onConcede();
                } catch (RuntimeException e) {
                    e.printStackTrace();
                    ws.send(new Messages.ErrorMsg("Bridge: " + e));
                }
            });
            case "requestState" -> onClientConnected();
            default -> ws.send(new Messages.ErrorMsg("unbekannter Nachrichtentyp: " + type));
        }
    }

    private static Integer seqOf(JsonNode msg) {
        JsonNode s = msg.get("seq");
        return s == null || !s.isInt() ? null : s.asInt();
    }

    /** Fehler aus dem Runnable duerfen den UI-Thread nicht stillschweigend beenden - sie muessen zum Browser. */
    private void ui(Runnable r) {
        GuiBase.getInterface().invokeInEdtLater(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Bridge: " + e));
            }
        });
    }

    /**
     * {"type":"startGame","humanDeck":{"precon":"..."},"opponents":[{"precon":"...","name":"KI 1"}]}
     * M2 kennt nur Precons; Textlisten kommen in M4.
     */
    private void startGame(JsonNode msg) {
        if (match.isRunning()) {
            ws.send(new Messages.ErrorMsg("Spiel laeuft noch – erst aufgeben"));
            return;
        }
        Deck human = deck(msg.path("humanDeck"));
        List<Deck> ai = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int i = 1;
        for (JsonNode o : msg.path("opponents")) {
            ai.add(deck(o));
            names.add(o.path("name").asText("KI " + i++));
        }
        ui(() -> {
            try {
                match.start("Du", human, ai, names, gui);
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Spielstart fehlgeschlagen: " + e));
                e.printStackTrace();
            }
        });
    }

    private static Deck deck(JsonNode node) {
        String precon = node.path("precon").asText(null);
        if (precon == null) {
            throw new IllegalArgumentException("Deck braucht ein Feld 'precon'");
        }
        return Precons.load(precon);
    }
}
