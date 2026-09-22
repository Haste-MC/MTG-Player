package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckSource;
import mtgplayer.decks.DeckStore;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.Stops;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.HumanMatch;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
    private final DeckStore store;
    private final Archidekt archidekt;
    private final DeckSource decks;
    private final MatchStore matches;
    /** Genau ein archidektImport-Lauf zur Zeit (siehe handle, "archidektImport"). */
    private final AtomicBoolean importRunning = new AtomicBoolean();

    public Bridge(int wsPort) {
        this(wsPort, DeckStore.standard(), Archidekt.standard(), MatchStore.standard());
    }

    /** Fuer Tests (siehe BridgeArchidektImportTest/BridgeEndToEndTest): eigenes Deck-Verzeichnis,
     *  Archidekt ohne Netz und eigener Partien-Speicher - Kevins ~/.mtg-player bleibt unberuehrt. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches) {
        this.store = store;
        this.archidekt = archidekt;
        this.decks = new DeckSource(store, archidekt);
        this.matches = matches;
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
        // Abbrueche des Spiel-Threads (Forge-BugReporter, uncaught) als Fehlerzeile in den Browser.
        CrashLog.setListener(text -> ws.send(new Messages.ErrorMsg(text)));
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
        ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
        ws.send(new Messages.Matches(matches.all()));
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
            case "resyncDeck" -> {
                String name = msg.path("name").asText();
                GuiBase.getInterface().runBackgroundTask("resync", () -> {
                    try {
                        decks.resync(name).save().run();
                        ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
                    } catch (IllegalArgumentException e) {
                        ws.send(new Messages.ErrorMsg(e.getMessage()));
                    } catch (RuntimeException e) {
                        // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                        e.printStackTrace();
                        ws.send(new Messages.ErrorMsg("Resync " + name + ": " + e));
                    }
                });
            }
            case "deleteDeck" -> {
                String name = msg.path("name").asText();
                GuiBase.getInterface().runBackgroundTask("delete", () -> {
                    try {
                        store.delete(name);
                        ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
                    } catch (RuntimeException e) {
                        // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                        e.printStackTrace();
                        ws.send(new Messages.ErrorMsg("Löschen " + name + ": "
                                + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
                    }
                });
            }
            case "archidektList" -> archidektList(msg.path("username").asText(""));
            case "archidektImport" -> archidektImport(msg.path("ids"));
            case "deleteMatch" -> deleteMatch(msg.path("id").asText());
            case "setMatchCounted" -> setMatchCounted(msg.path("id").asText(), msg.path("counted").asBoolean());
            case "requestState" -> onClientConnected();
            default -> ws.send(new Messages.ErrorMsg("unbekannter Nachrichtentyp: " + type));
        }
    }

    /** {"type":"deleteMatch","id":"..."} → aktualisierte {@link Messages.Matches} oder error "Partie <id>: ...". */
    private void deleteMatch(String id) {
        GuiBase.getInterface().runBackgroundTask("delete-match", () -> {
            try {
                matches.delete(id);
                ws.send(new Messages.Matches(matches.all()));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Partie " + id + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /** {"type":"setMatchCounted","id":"...","counted":true|false} → aktualisierte {@link Messages.Matches} oder error "Partie <id>: ...". */
    private void setMatchCounted(String id, boolean counted) {
        GuiBase.getInterface().runBackgroundTask("set-match-counted", () -> {
            try {
                matches.setCounted(id, counted);
                ws.send(new Messages.Matches(matches.all()));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Partie " + id + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /** {"type":"archidektList","username":"..."} → archidektDecks (Konto-Liste, nur Commander) oder error. */
    private void archidektList(String username) {
        GuiBase.getInterface().runBackgroundTask("archidekt-list", () -> {
            try {
                List<Messages.ArchidektEntry> out = new ArrayList<>();
                for (Archidekt.Entry e : archidekt.listDecks(username)) {
                    out.add(new Messages.ArchidektEntry(e.id(), e.name(), e.updatedAt(), e.art()));
                }
                ws.send(new Messages.ArchidektDecks(username.trim(), out));
            } catch (IllegalArgumentException e) {
                ws.send(new Messages.ErrorMsg(e.getMessage()));
            } catch (RuntimeException e) {
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Archidekt: " + e));
            }
        });
    }

    /**
     * {"type":"archidektImport","ids":[long, ...]}: je Id nacheinander {@link DeckSource#importArchidekt}
     * (vorhandenes Deck → Resync unter dem gespeicherten Namen, sonst Neuimport). Vor jedem Deck ein
     * archidektProgress mit current = gespeicherter Name oder "Deck &lt;id&gt;" (die Archidekt-Liste liegt nur
     * beim Client), nach jedem Deck eine lobby-Nachricht; Fehler je Deck landen in errors, der Lauf geht
     * weiter. Zwischen zwei Abrufen ≥ 1 s Pause (Archidekt-Ruecksicht). Nur ein Lauf zur Zeit.
     */
    private void archidektImport(JsonNode idsNode) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : idsNode) {
            if (n.canConvertToLong()) ids.add(n.asLong());
        }
        if (!importRunning.compareAndSet(false, true)) {
            ws.send(new Messages.ErrorMsg("Archidekt: Import läuft noch"));
            return;
        }
        try {
            GuiBase.getInterface().runBackgroundTask("archidekt-import", () -> importRun(ids));
        } catch (RuntimeException e) {
            importRunning.set(false);   // Task kam nie zum Laufen - Flag nicht haengen lassen
            throw e;
        }
    }

    /** Der eigentliche Lauf (Hintergrund-Thread), siehe {@link #archidektImport}. */
    private void importRun(List<Long> ids) {
        int total = ids.size();
        int done = 0;
        List<String> errors = new ArrayList<>();
        try {
            ws.send(new Messages.ArchidektProgress(0, total, null, List.copyOf(errors)));
            for (long id : ids) {
                String current = store.byArchidektId(String.valueOf(id));
                if (current == null) current = "Deck " + id;
                ws.send(new Messages.ArchidektProgress(done, total, current, List.copyOf(errors)));
                try {
                    decks.importArchidekt(id).save().run();
                } catch (RuntimeException e) {
                    if (!(e instanceof IllegalArgumentException)) e.printStackTrace();
                    String reason = e instanceof IllegalArgumentException ? e.getMessage() : e.toString();
                    errors.add(current + ": " + stripDeckPrefix(current, reason));
                }
                done++;
                ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
                if (done < total) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        errors.add(current + ": Import abgebrochen");
                        break;
                    }
                }
            }
        } catch (RuntimeException e) {
            // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
            e.printStackTrace();
            ws.send(new Messages.ErrorMsg("Archidekt-Import: " + e));
        } finally {
            importRunning.set(false);
        }
        ws.send(new Messages.ArchidektProgress(done, total, null, List.copyOf(errors)));
    }

    /**
     * Fehlertext je Deck im Import-Lauf soll "&lt;Name&gt;: &lt;Grund&gt;" sein. DeckSource.resync stellt dem
     * Grund bereits "Resync &lt;Name&gt;: " voran (und andere Pfade ggf. "&lt;Name&gt;: ") - dieser Praefix
     * wird hier entfernt, damit der Name nicht doppelt erscheint.
     */
    static String stripDeckPrefix(String name, String message) {
        if (message == null) return "unbekannter Fehler";
        if (name == null) return message;
        for (String prefix : new String[] { "Resync " + name + ": ", name + ": " }) {
            if (message.startsWith(prefix)) {
                return message.substring(prefix.length());
            }
        }
        return message;
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
        ws.send(new Messages.Lobby(Precons.infos(), store.infos())); // ggf. neu gespeichertes Deck
        Deck humanDeck = human;
        int timeout = aiTimeout;
        // Sink: die beendete Partie landet im Speicher, der Client bekommt zusaetzlich zu gameOver
        // (siehe WebGuiGame) die aktualisierte "matches"-Liste.
        Consumer<MatchRecord> sink = r -> {
            matches.add(r);
            ws.send(new Messages.Matches(matches.all()));
        };
        ui(() -> {
            try {
                if (spectate) {
                    match.startSpectator(ai, names, configs, timeout, gui, sink);
                } else {
                    match.start("Du", humanDeck, ai, names, configs, timeout, gui, sink);
                }
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Spielstart fehlgeschlagen: " + e));
                e.printStackTrace();
            }
        });
    }
}
