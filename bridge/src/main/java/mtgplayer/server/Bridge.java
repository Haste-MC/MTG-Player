package mtgplayer.server;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import mtgplayer.ai.AiConfig;
import mtgplayer.app.UpdateApply;
import mtgplayer.app.UpdateCheck;
import mtgplayer.app.Version;
import mtgplayer.decks.Archidekt;
import mtgplayer.decks.DeckAnalysis;
import mtgplayer.decks.DeckSource;
import mtgplayer.decks.DeckStore;
import mtgplayer.decks.Edhrec;
import mtgplayer.decks.Suggestions;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.Stops;
import mtgplayer.gui.WebGuiGame;
import mtgplayer.match.HumanMatch;
import mtgplayer.protocol.Json;
import mtgplayer.protocol.Messages;
import mtgplayer.sparring.GameRunner;
import mtgplayer.sparring.SparringArgs;
import mtgplayer.sparring.SparringRun;
import mtgplayer.sparring.SubprocessGameRunner;
import mtgplayer.stats.CardLog;
import mtgplayer.stats.CardStats;
import mtgplayer.stats.CardStore;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    /** Kartenbiografien (Runde C): eigene Ablage neben {@link #matches}, siehe {@link CardStore}. */
    private final CardStore cards;
    /** Kartenvorschlaege (Stueck 2): EDHREC-Anreicherung, siehe {@link #suggestCards}. */
    private final Edhrec edhrec;
    /** Sparring (Stueck 3): genau ein Lauf zur Zeit, siehe {@link SparringRun}. */
    private final SparringRun sparring;
    /** App-Paket, Task 4: Fassungsabgleich gegen GitHub, siehe {@link #checkVersion()}. */
    private final UpdateCheck updateCheck;
    /** Die eigene Fassung fuer {@link #checkVersion()} - wie {@link #updateCheck} eine einsetzbare
     *  Naht (Vorgabe {@link Version#current()}), damit ein Test den Erfolgszweig (eine ECHTE neuere
     *  Fassung) durchspielen kann, ohne ein echtes {@code version.txt}/Jar-Manifest vorzutaeuschen. */
    private final Supplier<String> currentVersion;
    /** Ergebnis von {@link #checkVersion()}, sobald (und nur wenn) es tatsaechlich etwas Neueres
     *  gibt - {@code null} bis dahin bzw. dauerhaft ohne Update. Ein Client, der sich VOR dem Ende
     *  der Pruefung verbindet, bekaeme die Nachricht sonst nie: {@link #onClientConnected()} schickt
     *  sie zusaetzlich beim (Re-)Verbinden nach, wenn sie inzwischen vorliegt. */
    private volatile Messages.VersionMsg versionMsg;
    /** App-Paket, Aufgabe 5: das erkannte Update herunterladen/pruefen/entpacken, siehe
     *  {@link #applyUpdate()}. Injiziert (Review-Nachtrag): BridgeApplyUpdateTest braucht eine eigene
     *  Download-Quelle, um den Protokollweg ohne echtes Netz durchzuspielen. */
    private final UpdateApply updateApply;
    /** Woher {@link #applyUpdate()} den aktuellen App-Ordner nimmt - im Betrieb der Elternordner von
     *  {@code assets/} ({@link ForgeBoot#assetsDir()}, wie {@code Main}/{@link Version}), im Test ein
     *  eingesetzter Ordner (Review-Nachtrag: die appDir-Wache in {@link UpdateApply#run} muss sich
     *  ueber das echte Protokoll pruefen lassen, nicht nur innerhalb von UpdateApplyTest). */
    private final Supplier<Path> appDirForUpdate;
    /**
     * Letzter Schritt von {@link #applyUpdate()}: das geschriebene Skript starten und danach geordnet
     * beenden. Eine eigene Schnittstelle statt direktem {@code ProcessBuilder}/{@code System.exit}
     * (Review-Befund): kein Test darf einen echten Windows-Prozess starten oder die eigene JVM
     * beenden - {@link #realLaunch} ist die echte Umsetzung, BridgeApplyUpdateTest setzt eine Attrappe ein.
     */
    interface UpdateLauncher {
        void launch(Path script) throws IOException;
    }
    private final UpdateLauncher launcher;
    /** Genau ein applyUpdate-Lauf zur Zeit (2. Review-Nachtrag, Befund 5) - selbes Muster wie
     *  {@link #importRunning}: zwei Klicks auf "Aktualisieren" sollen nicht zwei Downloads und zwei
     *  Tauschskripte gleichzeitig anstossen. */
    private final AtomicBoolean updateRunning = new AtomicBoolean();
    /** Genau ein archidektImport-Lauf zur Zeit (siehe handle, "archidektImport"). */
    private final AtomicBoolean importRunning = new AtomicBoolean();
    /** Deckel der "matches"-Liste zum Client (Task 3) - siehe {@link #matchesMsg()}. */
    private static final int LIST_MAX = 300;

    public Bridge(int wsPort) {
        // Entpackt bewusst die ganze Vorgabe-Kette (statt nur DeckStore.standard()/Archidekt.standard()/
        // MatchStore.standard() an den 4-Parameter-Konstruktor zu reichen): nur HIER, am einzigen
        // oeffentlichen Konstruktor, soll das ECHTE UpdateCheck (mit echtem Netzabruf) landen - siehe
        // Kommentar bei NO_UPDATE_CHECK, warum die Testkonstruktoren darunter etwas anderes vorgeben.
        this(wsPort, DeckStore.standard(), Archidekt.standard(), MatchStore.standard(),
                new SubprocessGameRunner(), new Edhrec(), CardStore.standard(), new UpdateCheck());
    }

    /** Sichere Vorgabe fuer jeden Testkonstruktor unten, der kein eigenes {@link UpdateCheck} angibt:
     *  die Quelle wirft sofort, ohne je ins Netz zu gehen. Anders als {@link Edhrec} (dort ist der
     *  Zugriff traege - nur auf ausdrueckliche Anfrage ueber "suggestCards") ruft {@link #start()}
     *  {@link #checkVersion()} IMMER auf. Ein "echtes" {@code new UpdateCheck()} als Vorgabe wuerde
     *  also jeden bestehenden Bridge-Test, der {@code start()} aufruft, unbemerkt ins Netz schicken -
     *  genau das verbietet Task 4 ("kein Test geht ins Netz"). */
    private static final UpdateCheck NO_UPDATE_CHECK = new UpdateCheck(url -> {
        throw new RuntimeException("Test-Bridge ohne eingesetztes UpdateCheck - siehe NO_UPDATE_CHECK");
    });

    /** Fuer Tests (siehe BridgeArchidektImportTest/BridgeEndToEndTest): eigenes Deck-Verzeichnis,
     *  Archidekt ohne Netz und eigener Partien-Speicher - Kevins ~/.mtg-player bleibt unberuehrt.
     *  Sparring spielt hier wie im Betrieb in Kindprozessen. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches) {
        this(wsPort, store, archidekt, matches, new SubprocessGameRunner());
    }

    /** Fuer Tests, die Sparring ueber das Protokoll pruefen (siehe BridgeSparringTest): der
     *  {@link GameRunner} ist dort eine Attrappe, damit kein echtes Spiel und kein Kindprozess anlaeuft.
     *  Edhrec bleibt die echte (kein Netz, solange kein Test suggestCards anfragt). */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner) {
        this(wsPort, store, archidekt, matches, sparringRunner, new Edhrec());
    }

    /** Fuer Tests, die suggestCards ueber das Protokoll pruefen (siehe BridgeSuggestCardsTest): eigene
     *  Edhrec-Quelle/-Zwischenspeicher (wie {@link Edhrec#Edhrec(java.util.function.Function, java.nio.file.Path)}),
     *  damit kein Test den echten EDHREC-Abruf ausloest oder nach ~/.mtg-player schreibt. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner,
           Edhrec edhrec) {
        this(wsPort, store, archidekt, matches, sparringRunner, edhrec, CardStore.standard());
    }

    /** Fuer Tests, die die Kartendatei pruefen (siehe BridgeEndToEndTest, "deleteMatch loescht die
     *  Kartendatei mit"): eigener {@link CardStore} statt {@link CardStore#standard()} - Kevins echte
     *  {@code ~/.mtg-player/cards} bleibt unberuehrt, und die Pruefung haengt nicht am geteilten
     *  {@code target/test-data}, das sich alle Testklassen im selben Fork teilen. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner,
           Edhrec edhrec, CardStore cards) {
        this(wsPort, store, archidekt, matches, sparringRunner, edhrec, cards, NO_UPDATE_CHECK);
    }

    /** Fuer Tests, die die Fassungspruefung ueber das Protokoll pruefen (siehe VersionMsg/checkVersion):
     *  eigener {@link UpdateCheck} mit eingesetzter Quelle, damit kein Test den echten GitHub-Abruf
     *  ausloest (Task 4 - "kein Test geht ins Netz"). Die eigene Fassung kommt weiterhin aus
     *  {@link Version#current()} - in jeder Testumgebung "dev" (siehe dort), pruefbar ueber den
     *  9-Parameter-Konstruktor darunter. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner,
           Edhrec edhrec, CardStore cards, UpdateCheck updateCheck) {
        this(wsPort, store, archidekt, matches, sparringRunner, edhrec, cards, updateCheck, Version::current);
    }

    /** Fuer Tests, die den ERFOLGSZWEIG von checkVersion() pruefen (siehe BridgeVersionTest): eine
     *  eingesetzte "eigene Fassung" statt {@link Version#current()} - dieselbe Idee wie bei
     *  {@code updateCheck}, nur fuer die andere Haelfte des Vergleichs. Ohne diese Naht liefert
     *  {@code Version.current()} im Testlauf IMMER "dev" (kein {@code version.txt}, keine gepackte
     *  Jar), und der Erfolgspfad ("es kommt tatsaechlich eine 'version'-Nachricht an") waere nie
     *  durchspielbar. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner,
           Edhrec edhrec, CardStore cards, UpdateCheck updateCheck, Supplier<String> currentVersion) {
        this(wsPort, store, archidekt, matches, sparringRunner, edhrec, cards, updateCheck, currentVersion,
                null, null, null);
    }

    /** Fuer Tests, die applyUpdate/updateState ueber das echte Protokoll pruefen (siehe
     *  BridgeApplyUpdateTest): ein eigenes {@link UpdateApply} (typischerweise mit eingesetzter
     *  Download-Quelle), eine eigene {@link UpdateLauncher}-Attrappe statt echtem Prozessstart/
     *  {@code System.exit}, und ein eingesetzter App-Ordner statt {@link ForgeBoot#assetsDir()}.
     *  {@code null} fuer irgendeinen der drei laesst {@link #applyUpdate()} bei der echten Umsetzung -
     *  so bleiben alle kuerzeren Konstruktoren oben unveraendert und jeder bestehende Bridge-Test
     *  weiterhin ohne Netz/Prozessstart/JVM-Ende. */
    Bridge(int wsPort, DeckStore store, Archidekt archidekt, MatchStore matches, GameRunner sparringRunner,
           Edhrec edhrec, CardStore cards, UpdateCheck updateCheck, Supplier<String> currentVersion,
           UpdateApply updateApply, UpdateLauncher launcher, Supplier<Path> appDirForUpdate) {
        this.store = store;
        this.archidekt = archidekt;
        this.decks = new DeckSource(store, archidekt);
        this.matches = matches;
        this.cards = cards;
        this.edhrec = edhrec;
        this.updateCheck = updateCheck;
        this.currentVersion = currentVersion;
        this.updateApply = updateApply != null ? updateApply : new UpdateApply();
        this.launcher = launcher != null ? launcher : this::realLaunch;
        this.appDirForUpdate = appDirForUpdate != null ? appDirForUpdate : () -> ForgeBoot.assetsDir().getParent();
        this.ws = new WsServer(wsPort, this::handle, this::onClientConnected);
        // Der Lauf meldet Fortschritt und - je gespeicherter Partie - den Datensatz selbst; die
        // gedeckelte "matches"-Liste baut nur die Bridge (siehe matchesMsg()), deshalb hier die
        // Umsetzung MatchRecord -> matches. Derselbe CardStore wie oben (nicht CardStore.standard()) -
        // Befund 4: SparringRun loescht damit die Kartendatei einer gekappten Partie ueber denselben
        // Store, in den auch der Bridge-Sink (startGame) schreibt.
        this.sparring = new SparringRun(store, matches, sparringRunner, cards,
                o -> ws.send(o instanceof MatchRecord ? matchesMsg() : o));
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
        checkVersion();
    }

    /** Task 4 (App-Paket): einmal beim Start im Hintergrund pruefen, ob es bei GitHub eine neuere
     *  Fassung gibt. {@link UpdateCheck#latest()} verschluckt jeden Fehlschlag schon selbst (kein
     *  Netz, kaputte Antwort, Zeitueberschreitung) - hier bleibt nur noch der Vergleich gegen die
     *  eigene Fassung. Ergebnis wird zwischengespeichert ({@link #versionMsg}) UND sofort an bereits
     *  verbundene Clients geschickt; {@link #onClientConnected()} holt Nachzuegler nach.
     *
     *  <p>Review-Befund (kritisch): eine unbekannte/Entwicklungsfassung ({@link Version#DEV}) fragt
     *  GAR NICHT erst nach - nicht nur im App-Modus, sondern bei JEDEM {@link #start()}, also auch
     *  in Kevins Entwicklungs-Bridge ({@code mvn exec:java} ohne {@code -Dmtgplayer.app}). Ohne diese
     *  Sperre haette {@code start()} dort bei jedem Neustart einen echten, wenn auch folgenlosen
     *  ({@link Version#isNewer} liefert fuer "dev" ohnehin immer {@code false}) Netzabruf ausgeloest -
     *  reiner Leerlauf, der trotzdem wirklich ins Netz geht. Die Regel "current == dev" ist schaerfer
     *  als "nur im App-Modus prüfen": sie deckt auch einen App-Modus-Lauf direkt aus dem Quellbaum ab
     *  (kein {@code version.txt}, keine gepackte Jar - {@link Version#current()} liefert dann
     *  ebenfalls "dev").</p> */
    private void checkVersion() {
        String current = currentVersion.get();
        if (Version.DEV.equals(current)) return;
        GuiBase.getInterface().runBackgroundTask("version-check", () -> {
            updateCheck.latest()
                    .filter(release -> Version.isNewer(release.tag(), current))
                    .ifPresent(release -> {
                        Messages.VersionMsg msg = new Messages.VersionMsg(current, release);
                        versionMsg = msg;
                        ws.send(msg);
                    });
        });
    }

    /**
     * {"type":"applyUpdate"} (Aufgabe 5, Spec §5/§6): das ueber {@link #checkVersion()} erkannte
     * Update herunterladen, pruefen und entpacken ({@link UpdateApply#run}) - Fortschritt/Fehler
     * gehen als updateState raus. Ohne zuvor gemeldetes Update ({@link #versionMsg} noch
     * {@code null}) passiert nichts: ohne "version" haette der Client (Aufgabe 6) den
     * "Aktualisieren"-Knopf nie gezeigt, ein "applyUpdate" waere dann ein Client-Fehler.
     *
     * <p>Wiedereintrittssperre wie {@link #archidektImport} (2. Review-Nachtrag, Befund 5): zwei Klicks
     * auf "Aktualisieren" (Doppelklick, zwei Browserfenster) sollen nicht zwei Downloads und zwei
     * Tauschskripte gleichzeitig anstossen. Anders als {@code importRunning} muss das Flag im
     * ERFOLGSFALL nicht zurueckgesetzt werden - der echte {@link #realLaunch} beendet die JVM ohnehin;
     * das Flag lebt nur so lange wie diese eine Bridge.</p>
     */
    private void applyUpdate() {
        Messages.VersionMsg vm = versionMsg;
        if (vm == null) {
            ws.send(new Messages.ErrorMsg("Update: keine neuere Fassung bekannt"));
            return;
        }
        if (!updateRunning.compareAndSet(false, true)) {
            ws.send(new Messages.ErrorMsg("Update: laeuft schon"));
            return;
        }
        UpdateCheck.Release release = new UpdateCheck.Release(vm.latest(), vm.url(), vm.sha256(), vm.notes());
        Path appDir = appDirForUpdate.get();
        try {
            GuiBase.getInterface().runBackgroundTask("apply-update", () -> {
                try {
                    UpdateApply.Result result = updateApply.run(release, appDir,
                            (state, text) -> ws.send(new Messages.UpdateStateMsg(state, text)));
                    if (result == null) return; // "fehler" ist schon raus, siehe UpdateApply.run
                    try {
                        launcher.launch(result.script());
                    } catch (IOException e) {
                        ws.send(new Messages.ErrorMsg("Update: Skript konnte nicht gestartet werden - " + e.getMessage()));
                    }
                } finally {
                    // Laeuft die echte Umsetzung durch (realLaunch), beendet System.exit die JVM ohnehin
                    // vorher - dieses set(false) erreicht dann niemanden mehr. Fuer jeden Fehlerpfad UND
                    // fuer Tests mit eingesetztem UpdateLauncher (kein echtes Prozessende) ist es der
                    // Unterschied zwischen "haengt fuer immer auf gesperrt" und einem neuen Versuch.
                    updateRunning.set(false);
                }
            });
        } catch (RuntimeException e) {
            updateRunning.set(false);   // Task kam nie zum Laufen - Flag nicht haengen lassen
            throw e;
        }
    }

    /**
     * Echte Umsetzung von {@link UpdateLauncher} (Vorgabe, wenn kein Test etwas anderes einsetzt): das
     * Skript starten und danach geordnet beenden. Arbeitsverzeichnis auf den Ordner des Skripts gesetzt
     * (Review-Befund, Blocker 1): {@code ProcessBuilder} setzt sonst KEIN Arbeitsverzeichnis, cmd.exe
     * wuerde also den App-Ordner erben (den aktuellen JVM-Arbeitsordner) - und Windows kann ein
     * Verzeichnis nicht umbenennen, das das Arbeitsverzeichnis eines laufenden Prozesses ist.
     *
     * <p>Erst {@link #stop()} (Sparring/Match/WebSocket geordnet beenden), DANACH {@code System.exit}
     * (Review-Befund, Punkt 12): ein blosses {@code System.exit(0)} wuerde ein laufendes
     * Sparring-Kind ueberleben lassen, das dann weiter Dateien im App-Ordner offenhaelt - genau der
     * Zustand, den das Skript beim Umbenennen vermeiden soll. {@code stop()} kann selbst etwas dauern
     * (eine laufende Sparring-Partie beendet sich erst nach dem aktuellen Spiel) - das Skript wartet
     * ohnehin auf das echte Prozessende, bevor es den App-Ordner anfasst.</p>
     */
    private void realLaunch(Path script) throws IOException {
        new ProcessBuilder(script.toString())
                .directory(script.getParent().toFile())
                .start();
        try {
            stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Forge/Swing koennen nicht-daemon Threads hinterlassen, die ein blosses return ueberleben
        // wuerden (siehe Main.java, --bench-one/--sparring-one) - die App soll aber zuverlaessig enden.
        System.exit(0);
    }

    /** Fuer Tests (siehe BridgeSpectatorTest): Zugriff auf das HumanMatch, z. B. um nach dem
     *  Beenden eines Zuschauer-Spiels {@code lastGameOver()} zu pruefen. */
    HumanMatch match() {
        return match;
    }

    public void stop() throws InterruptedException {
        // Ein laufendes Sparring soll das Herunterfahren nicht ueberdauern (es endet nach der
        // gerade laufenden Partie, der Thread ist ohnehin ein Daemon).
        sparring.cancel();
        match.end();
        ws.stop(1000);
    }

    private void onClientConnected() {
        ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
        ws.send(matchesMsg());
        // Nachzuegler: die Pruefung aus checkVersion() lief evtl. noch, als dieser Client sich
        // verband (Netzabruf), oder verbindet sich erst jetzt neu - liegt inzwischen ein Ergebnis
        // vor, bekommt er es hier nachgereicht statt nie.
        Messages.VersionMsg vm = versionMsg;
        if (vm != null) ws.send(vm);
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
            case "setDeckBracket" -> {
                String name = msg.path("name").asText();
                Integer bracket = msg.hasNonNull("bracket") ? msg.path("bracket").asInt() : null;
                GuiBase.getInterface().runBackgroundTask("set-deck-bracket", () -> {
                    try {
                        store.setBracket(name, bracket);
                        ws.send(new Messages.Lobby(Precons.infos(), store.infos()));
                    } catch (RuntimeException e) {
                        // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                        e.printStackTrace();
                        ws.send(new Messages.ErrorMsg("Bracket " + name + ": "
                                + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
                    }
                });
            }
            case "analyzeDeck" -> analyzeDeck(msg.path("deck").asText());
            case "suggestCards" -> suggestCards(msg.path("deck").asText(), roles(msg.path("roles")));
            case "deckCards" -> deckCards(msg.path("deck").asText());
            case "archidektList" -> archidektList(msg.path("username").asText(""));
            case "archidektImport" -> archidektImport(msg.path("ids"));
            case "deleteMatch" -> deleteMatch(msg.path("id").asText());
            case "setMatchCounted" -> setMatchCounted(msg.path("id").asText(), msg.path("counted").asBoolean());
            case "matchDetail" -> matchDetail(msg.path("id").asText());
            case "sparringStart" -> sparringStart(msg);
            case "sparringCancel" -> sparring.cancel();
            case "applyUpdate" -> applyUpdate();
            case "requestState" -> onClientConnected();
            default -> ws.send(new Messages.ErrorMsg("unbekannter Nachrichtentyp: " + type));
        }
    }

    /**
     * {"type":"analyzeDeck","deck":"&lt;Name&gt;"} → {@link Messages.DeckAnalysisMsg} oder error
     * "Deckanalyse &lt;name&gt;: unbekanntes Deck". Der Name meint erst ein gespeichertes Deck, dann ein
     * Precon (gleiche Reihenfolge wie in der Lobby-Liste). Hintergrund-Task: die Analyse liest ~100
     * Kartenregeln, das hat auf dem UI-Thread nichts verloren.
     */
    private void analyzeDeck(String name) {
        GuiBase.getInterface().runBackgroundTask("analyze-deck", () -> {
            try {
                Deck deck = forAnalysis(name);
                if (deck == null) {
                    ws.send(new Messages.ErrorMsg("Deckanalyse " + name + ": unbekanntes Deck"));
                    return;
                }
                ws.send(new Messages.DeckAnalysisMsg(name, DeckAnalysis.of(deck)));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Deckanalyse " + name + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /** Gespeichertes Deck, sonst Precon, sonst null (beide werfen bei unbekanntem Namen). */
    private Deck forAnalysis(String name) {
        try {
            return store.load(name);
        } catch (IllegalArgumentException ignored) {
            // kein gespeichertes Deck dieses Namens - dann eben ein Precon
        }
        try {
            return Precons.load(name);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * {"type":"suggestCards","deck":"&lt;Name&gt;","roles":[…]} → {@link Messages.CardSuggestionsMsg} oder
     * error "Kartenvorschläge &lt;name&gt;: unbekanntes Deck". Hintergrund-Task wie analyzeDeck: der
     * EDHREC-Abruf, die Kartenauswertung ueber die Partienhistorie und der Rückfall über die ganze
     * Kartendatenbank haben auf dem UI-Thread nichts verloren.
     */
    private void suggestCards(String name, List<String> roles) {
        GuiBase.getInterface().runBackgroundTask("suggest-cards", () -> {
            try {
                Deck deck = forAnalysis(name);
                if (deck == null) {
                    ws.send(new Messages.ErrorMsg("Kartenvorschläge " + name + ": unbekanntes Deck"));
                    return;
                }
                // Befund 10: lookup() statt page() - der Grund eines Fehlschlags (nicht erreichbar vs.
                // Commander dort unbekannt) geht sonst verloren, bevor er im Text landen kann.
                Edhrec.Lookup lookup = edhrec.lookup(commanderNames(deck));
                // Task 5: eigene Partien schlagen EDHREC-Vermutungen beim Schnittgrund - aber nur, wenn die
                // Auswertung ueberhaupt ausreichend abgesichert ist (CardStats#enough); sonst waere "nie
                // gewirkt" eine Behauptung auf duenner Grundlage, also eine leere Map statt einer Zahl.
                CardStats own = CardStats.of(name, matches.all(), cards);
                Map<String, CardStats.Card> ownCards = own.enough()
                        ? own.cards().stream().collect(Collectors.toMap(CardStats.Card::name, c -> c))
                        : Map.of();
                ws.send(new Messages.CardSuggestionsMsg(name,
                        Suggestions.of(deck, roles, store.bracket(name), lookup.page().orElse(null),
                                lookup.reason(), ownCards)));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Kartenvorschläge " + name + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /**
     * {"type":"deckCards","deck":"&lt;Name&gt;"} → {@link Messages.CardStatsMsg} oder error
     * "Kartenauswertung &lt;name&gt;: unbekanntes Deck". Deckaufloesung wie bei analyzeDeck (gespeichertes
     * Deck, sonst Precon) - {@link CardStats#of} selbst braucht dafuer nur den Namen (Abgleich mit
     * {@link MatchRecord.Seat#deck}), das aufgeloeste {@link Deck} dient hier allein der
     * Bekanntheitspruefung, damit ein Tippfehler nicht schlicht "keine Partien" liefert. Hintergrund-Task
     * wie analyzeDeck: die Auswertung liest die ganze Partienhistorie und je gewerteter Partie eine
     * Kartendatei, das hat auf dem UI-Thread nichts verloren.
     */
    private void deckCards(String name) {
        GuiBase.getInterface().runBackgroundTask("deck-cards", () -> {
            try {
                Deck deck = forAnalysis(name);
                if (deck == null) {
                    ws.send(new Messages.ErrorMsg("Kartenauswertung " + name + ": unbekanntes Deck"));
                    return;
                }
                ws.send(new Messages.CardStatsMsg(name, CardStats.of(name, matches.all(), cards)));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Kartenauswertung " + name + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /** Kommandeurnamen fuer {@link Edhrec#page}: Forge liefert PaperCard, getName() ist nie null. */
    private static List<String> commanderNames(Deck deck) {
        return deck.getCommanders().stream().map(PaperCard::getName).toList();
    }

    /**
     * Wandelt das JSON-Array aus "roles" in eine Liste bekannter Rollennamen (siehe
     * {@link DeckAnalysis#rules()}) - unbekannte Namen fallen weg, der Client darf nichts erfinden.
     */
    private static List<String> roles(JsonNode rolesNode) {
        Set<String> known = DeckAnalysis.rules().stream().map(DeckAnalysis.Rule::name).collect(Collectors.toSet());
        List<String> out = new ArrayList<>();
        for (JsonNode n : rolesNode) {
            String role = n.asText("");
            if (known.contains(role)) {
                out.add(role);
            }
        }
        return out;
    }

    /**
     * {"type":"sparringStart","deck":"…","games":5,"ai":{…},"timeout":5,"maxTurns":60} (Spec &sect;3) -
     * startet den Hintergrundlauf und antwortet danach nur noch mit sparringProgress/matches. Ein
     * zweiter Start waehrend eines Laufs meldet "Sparring läuft noch", ein Deck ohne Gegner (oder mit
     * unbrauchbaren Parametern) "Sparring: &lt;Grund&gt;".
     *
     * <p>Absichtlich KEIN Hintergrund-Task: die Sperre gegen einen zweiten Lauf sitzt in
     * {@link SparringRun#start} und wuerde in zwei parallel gestarteten Threads zum Wettlauf - hier
     * auf dem WebSocket-Thread ist die Reihenfolge zweier Nachrichten die, in der sie ankamen. Der
     * Start selbst liest nur die Deck-Dateien (wie {@code onClientConnected}), gespielt wird auf dem
     * Thread des Laufs.</p>
     */
    private void sparringStart(JsonNode msg) {
        try {
            sparring.start(SparringArgs.fromJson(msg));
        } catch (IllegalStateException e) {
            ws.send(new Messages.ErrorMsg(e.getMessage()));
        } catch (RuntimeException e) {
            if (!(e instanceof IllegalArgumentException)) {
                e.printStackTrace();
            }
            ws.send(new Messages.ErrorMsg("Sparring: "
                    + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
        }
    }

    /** {"type":"deleteMatch","id":"..."} → aktualisierte {@link Messages.Matches} oder error "Partie <id>: ...". */
    private void deleteMatch(String id) {
        GuiBase.getInterface().runBackgroundTask("delete-match", () -> {
            try {
                matches.delete(id);
                // Die Kartendatei gehoert zur Partie (siehe CardStore) und muss mit ihr verschwinden -
                // anders als bei "nicht gewertet" (setMatchCounted), das bewusst nichts loescht.
                cards.delete(id);
                ws.send(matchesMsg());
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
                ws.send(matchesMsg());
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Partie " + id + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /**
     * {"type":"matchDetail","id":"..."} → {@link Messages.MatchMsg} mit dem vollstaendigen Datensatz
     * (inkl. Zeitachse), oder error "Partie <id>: ..." (unbekannte Partie oder Lesefehler). Hintergrund-Task wie bei
     * deleteMatch/setMatchCounted: {@link MatchStore#all()} liest die Datei, das hat auf dem UI-Thread
     * nichts verloren.
     */
    private void matchDetail(String id) {
        GuiBase.getInterface().runBackgroundTask("match-detail", () -> {
            try {
                MatchRecord r = matches.all().stream().filter(m -> m.id().equals(id)).findFirst().orElse(null);
                if (r == null) {
                    ws.send(new Messages.ErrorMsg("Partie " + id + ": unbekannte Partie"));
                    return;
                }
                ws.send(new Messages.MatchMsg(r));
            } catch (RuntimeException e) {
                // wie bei "concede": sonst stirbt der Fehler still auf dem Hintergrund-Thread - und der
                // Client wartet ewig auf die Zeitachse, die nie kommt
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Partie " + id + ": "
                        + (e instanceof IllegalArgumentException ? e.getMessage() : e.toString())));
            }
        });
    }

    /**
     * Die "matches"-Liste fuer den Client (Task 3): die neuesten {@link #LIST_MAX} Partien, aelteste
     * zuerst wie {@link MatchStore#all()}, je Datensatz ohne Zeitachse (siehe
     * {@link MatchRecord#withoutTimeline()}) - bei ~25 Kennzahlen und Zeitachse je Sitz waere die volle
     * Liste am Deckel (2000) sonst zweistellige MB gross. {@code total}: die tatsaechliche Gesamtzahl,
     * auch wenn mehr als {@link #LIST_MAX} gespeichert sind - das Board zeigt sie im Kopf. Details laedt
     * der Client erst bei Bedarf ueber {@code matchDetail}.
     */
    private Messages.Matches matchesMsg() {
        List<MatchRecord> all = matches.all();
        int total = all.size();
        List<MatchRecord> newest = total <= LIST_MAX ? all : all.subList(total - LIST_MAX, total);
        List<MatchRecord> stripped = newest.stream().map(MatchRecord::withoutTimeline).toList();
        return new Messages.Matches(stripped, total);
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
            // Der Sink laeuft im Guava-EventBus des Spiels (GameEventGameFinished): eine Ausnahme von
            // hier wuerde dort verschluckt, und niemand erfuehre, dass die Partie nicht gespeichert ist.
            List<String> dropped;
            try {
                dropped = matches.add(r);
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Partie konnte nicht gespeichert werden: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage())));
                return;
            }
            // Befund 4: eine gekappte Partie (MatchStore.MAX) verliert ihre matches.json-Zeile - ihre
            // Kartendatei (CardStore) muss mit, sonst bleibt sie fuer immer verwaist liegen. Ein
            // Loeschfehler darf die gerade erfolgreich gespeicherte Partie nicht als "nicht gespeichert"
            // melden, deshalb ein eigener try/catch statt im obigen.
            for (String droppedId : dropped) {
                try {
                    cards.delete(droppedId);
                } catch (RuntimeException e) {
                    CrashLog.note("Bridge", "Kartendatei der gekappten Partie " + droppedId
                            + " nicht geloescht: " + e);
                }
            }
            ws.send(matchesMsg());
        };
        // Eigene Decks = alle Namen, die der Store gerade kennt (siehe CardStore/MatchRecorder) - nicht
        // nur die dieser einen Partie: ein Sitz mit einem fremden/importierten Deck bekommt so weiterhin
        // keine Kartenbiografie, aber Kevins eigene Decks immer, egal gegen wen sie antreten.
        Set<String> ownDecks = Set.copyOf(store.names());
        Consumer<CardLog> cardSink = log -> {
            // Wie beim Sink oben: laeuft im Guava-EventBus, eine Ausnahme von hier darf nicht die
            // bereits gespeicherte Partie (matches.add oben) unsichtbar machen.
            try {
                cards.write(log);
            } catch (RuntimeException e) {
                // Befund 8: ein stiller printStackTrace liesse "seit Wochen keine Kartendaten" nie
                // auffallen - CrashLog.note schreibt wenigstens eine Zeile nach bridge.log (kein
                // Browser-Text: die Partie selbst ist bereits gespeichert, nur ihre Kartenbiografie fehlt).
                CrashLog.note("Bridge", "Kartendaten fuer " + log.id() + " nicht geschrieben: " + e);
            }
        };
        ui(() -> {
            try {
                if (spectate) {
                    match.startSpectator(ai, names, configs, timeout, gui, sink, ownDecks, cardSink);
                } else {
                    match.start("Du", humanDeck, ai, names, configs, timeout, gui, sink, ownDecks, cardSink);
                }
            } catch (RuntimeException e) {
                ws.send(new Messages.ErrorMsg("Spielstart fehlgeschlagen: " + e));
                e.printStackTrace();
            }
        });
    }
}
