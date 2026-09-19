package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.DeckSource;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.Stops;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.HumanMatch;
import mtgplayer.protocol.Json;
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
    private final DeckStore store = DeckStore.standard();
    private final DeckSource decks = new DeckSource(store);

    public Bridge(int wsPort) {
        this.ws = new WsServer(wsPort, this::handle, this::onClientConnected);
        this.gui = new WebGuiGame(ws);
        // KI-only-Modus: HostedMatch.startGame holt sich bei einer leeren guis-Map (Zuschauer, kein
        // menschlicher Sitz) sein IGuiGame ueber GuiBase.getInterface().getNewGuiGame() - liefert unsere
        // eine WebGuiGame-Instanz statt der Standard-Exception.
        if (GuiBase.getInterface() instanceof WebGuiBase wgb) {
            wgb.setGuiSupplier(() -> gui);
        }
    }

    public void start() {
        ws.start();
    }

    /** Fuer Tests (siehe BridgeSpectatorTest): Zugriff auf das HumanMatch, z. B. um nach dem
     *  Beenden eines Zuschauer-Spiels {@code lastGameOver()} zu pruefen. */
    HumanMatch match() {
        return match;
    }

    public void stop() throws InterruptedException {
        match.end();
        ws.stop(1000);
    }

    private void onClientConnected() {
        ws.send(new Messages.Lobby(Precons.names(), store.names()));
        // state vor choice: beides in EINEM Runnable, sonst kann pending() vor pushState() beim Client ankommen
        GuiBase.getInterface().invokeInEdtLater(() -> {
            gui.pushState();
            gui.recentLog().forEach(ws::send);
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
            case "concede" -> {
                if (gui.getLocalPlayers().isEmpty()) {
                    // Zuschauer: kein Sitz, der AbstractGuiGame.concede() auslösen könnte (das fragt den
                    // lokalen Spieler) - stattdessen das Spiel direkt beenden. NICHT auf dem UI-Thread
                    // (frueher ein Deadlock: match.end() wartet dort auf GameView.isGameOver(), waehrend
                    // Game.setGameOver(...) auf dem Game-Thread synchron auf genau diesen UI-Thread wartet
                    // (invokeInEdtAndWait) - siehe HumanMatch.end()). Stattdessen ein Hintergrund-Task, der
                    // pushState()/das Senden selbst wieder auf den UI-Thread zurueckgibt.
                    GuiBase.getInterface().runBackgroundTask("spectator-end", () -> {
                        // Ist das Spiel pausiert (Forges "Pause"-Zustand, siehe FControlGamePlayback),
                        // haengt der Game-Thread in einem CyclicBarrier fest, bis jemand "Resume"/"Step"
                        // ausloest - unser erzwungenes setGameOver() in match.end() erreichte den
                        // Game-Thread in diesem Zustand nicht zuverlaessig innerhalb der Frist. Deshalb
                        // erst fortsetzen (auf dem UI-Thread, wie ein normaler "Resume"-Klick, und
                        // synchron abgewartet, bevor wir end() aufrufen) und danach erst beenden.
                        GuiBase.getInterface().invokeInEdtAndWait(() -> {
                            if (gui.isGamePaused()) {
                                gui.resumeMatch();
                            }
                        });
                        match.end();
                        GuiBase.getInterface().invokeInEdtLater(gui::pushState);
                        ws.send(new Messages.GameOver(null));
                    });
                } else {
                    GuiBase.getInterface().runBackgroundTask("concede", () -> {
                        try {
                            gui.onConcede();
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                            ws.send(new Messages.ErrorMsg("Bridge: " + e));
                        }
                    });
                }
            }
            case "setStops" -> ui(() -> {
                Stops s = gui.stops();
                if (msg.has("own")) s = s.with(true, Stops.parse(Json.mapper().convertValue(msg.get("own"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })));
                if (msg.has("opp")) s = s.with(false, Stops.parse(Json.mapper().convertValue(msg.get("opp"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { })));
                gui.setStops(s);
            });
            case "fullControl" -> ui(() -> gui.setFullControl(msg.path("value").asBoolean(false)));
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
     * {"type":"startGame","humanDeck":{deckAngabe},"opponents":[{deckAngabe,"name":"KI 1",
     * "ai":{"mode":"sim","profile":"Reckless"}}],"aiTimeout":10}
     * KI-only-Modus (Zuschauer, kein humanDeck): {"type":"startGame","spectate":true,
     * "opponents":[{deckAngabe,"name":"KI 1"}, ... 2–6 Eintraege]}
     * deckAngabe: {"precon":"..."} | {"saved":"..."} | {"text":"...", "deckName":"..."?}
     * | {"archidekt":"https://archidekt.com/decks/...", "deckName":"..."?}
     * "name" bei einem Gegner-Eintrag ist der Spielername, nicht der Speichername des Decks.
     * "ai" (optional, je Gegner) und "aiTimeout" (optional, ganze Nachricht) – fehlt beides,
     * gilt AiConfig.DEFAULT bzw. 5 s.
     */
    private void startGame(JsonNode msg) {
        if (match.isRunning()) {
            ws.send(new Messages.ErrorMsg("Spiel laeuft noch – erst aufgeben"));
            return;
        }
        boolean spectate = msg.path("spectate").asBoolean(false);
        if (spectate && msg.path("opponents").size() < 2) {
            ws.send(new Messages.ErrorMsg("KI-Modus braucht mindestens 2 Decks"));
            return;
        }
        Deck human = null;
        List<Deck> ai = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<AiConfig> configs = new ArrayList<>();
        List<Runnable> saves = new ArrayList<>();
        int aiTimeout;
        try {
            aiTimeout = AiConfig.timeout(msg);
            if (!spectate) {
                DeckSource.Resolved humanR = decks.resolve(msg.path("humanDeck"));
                human = humanR.deck();
                saves.add(humanR.save());
            }
            int i = 1;
            for (JsonNode o : msg.path("opponents")) {
                DeckSource.Resolved r = decks.resolve(o);
                ai.add(r.deck());
                saves.add(r.save());
                names.add(o.path("name").asText("KI " + i++));
                configs.add(AiConfig.fromJson(o.path("ai")));
            }
        } catch (IllegalArgumentException e) {
            ws.send(new Messages.ErrorMsg(e.getMessage()));
            return;
        }
        // Erst wenn ALLE Decks aufgeloest sind speichern - ein fehlgeschlagener KI-Import
        // darf ein zuvor erfolgreich aufgeloestes Text-Deck nicht trotzdem auf Platte lassen.
        saves.forEach(Runnable::run);
        ws.send(new Messages.Lobby(Precons.names(), store.names())); // ggf. neu gespeichertes Deck
        Deck humanDeck = human;
        int timeout = aiTimeout;
        ui(() -> {
            try {
                if (spectate) {
                    match.startSpectator(ai, names, configs, timeout, gui);
                } else {
                    match.start("Du", humanDeck, ai, names, configs, timeout, gui);
                }
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Spielstart fehlgeschlagen: " + e));
                e.printStackTrace();
            }
        });
    }
}
