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
        GuiBase.getInterface().invokeInEdtLater(gui::pushState);
        gui.broker().pending().forEach(ws::send);
    }

    void handle(JsonNode msg) {
        String type = msg.path("type").asText("");
        switch (type) {
            case "startGame" -> startGame(msg);
            case "selectCard" -> ui(() -> gui.onSelectCard(msg.path("id").asInt(), msg.path("alt").asBoolean(false)));
            case "selectPlayer" -> ui(() -> gui.onSelectPlayer(msg.path("id").asInt()));
            case "ok" -> ui(gui::onOk);
            case "cancel" -> ui(gui::onCancel);
            case "answer" -> gui.broker().answer(msg.path("id").asInt(), msg.get("value"));
            case "concede" -> ui(gui::onConcede);
            case "requestState" -> onClientConnected();
            default -> ws.send(new Messages.ErrorMsg("unbekannter Nachrichtentyp: " + type));
        }
    }

    private static void ui(Runnable r) {
        GuiBase.getInterface().invokeInEdtLater(r);
    }

    /**
     * {"type":"startGame","humanDeck":{"precon":"..."},"opponents":[{"precon":"...","name":"KI 1"}]}
     * M2 kennt nur Precons; Textlisten kommen in M4.
     */
    private void startGame(JsonNode msg) {
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
