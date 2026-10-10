package mtgplayer.gui;

import com.fasterxml.jackson.databind.JsonNode;
import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.gui.GuiBase;
import mtgplayer.forge.CrashLog;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.gamemodes.match.input.InputQueue;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerControllerHuman;
import forge.player.PlayerZoneUpdate;
import forge.trackable.TrackableCollection;
import forge.trackable.TrackableTypes;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;
import mtgplayer.protocol.Messages;
import mtgplayer.protocol.Snapshot;
import mtgplayer.protocol.StateSerializer;
import mtgplayer.protocol.ViewContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Forges GUI-Schnittstelle für einen Browser-Sitz. Zustandsänderungen werden gebündelt als
 * {@link Snapshot} gepusht, synchrone Dialoge laufen über den {@link ChoiceBroker}.
 * Eingaben aus dem Browser kommen über die on*-Methoden – die müssen auf dem UI-Thread
 * aufgerufen werden ({@code GuiBase.getInterface().invokeInEdtLater}).
 */
public class WebGuiGame extends AbstractGuiGame {

    private final Transport out;
    private final ChoiceBroker broker;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile Snapshot.PromptSnap prompt = Snapshot.PromptSnap.EMPTY;
    private volatile Stops stops = Stops.defaults();
    private volatile boolean fullControl;
    /** Teamnummern in Sitzreihenfolge, von {@code HumanMatch} gesetzt; {@code null} = keine Teams. */
    private volatile List<Integer> teams;
    /** Haken "Partnerhand zeigen" aus der Lobby, von {@code Bridge.startGame} je Partie gesetzt; Standard: aus. */
    private volatile boolean revealPartnerHand;
    private volatile Consumer<Game> newGameHook;
    private volatile Game hookedGame;
    private final AtomicInteger seq = new AtomicInteger(1);
    @SuppressWarnings("deprecation")
    private Observer inputObserver;
    private InputQueue observedQueue;

    private final ArrayDeque<Messages.LogLine> recentLog = new ArrayDeque<>();
    private static final int LOG_MAX = 200;
    @SuppressWarnings("deprecation")
    private Observer logObserver;
    private GameLog observedLog;
    private int logSeen;
    /** Fortlaufende id je Partie (siehe {@link #remember}), unter dem Puffer-Lock gepflegt. */
    private int logId;

    /** Abschluss schon beim Browser? Macht {@link #finishGame()} wiederholbar und verhindert, dass
     *  das nachgelieferte Spielende (siehe {@link #spielendeNachliefern()}) doppelt rausgeht. */
    private volatile boolean abschlussGesendet;

    /** Wurde das Nachliefern schon angestossen? Sonst taete der Wachposten es in jedem Takt erneut. */
    private volatile boolean nachliefernAngestossen;

    /**
     * Denk-Anzeige und Wachhund der laufenden Partie; {@code null}, solange keine laeuft. Je Partie
     * ein eigener (siehe {@link ThinkingTicker#close()}).
     */
    private volatile ThinkingTicker ticker;
    /** Der zuletzt gebaute Snapshot - daraus kommen Prioritaetssitz und Phase fuer die Anzeige. */
    private volatile Snapshot lastSnapshot;
    /**
     * Forges Spiel-Thread, gemerkt beim ersten Log-Ereignis (der Observer laeuft dort). Der Wachhund
     * braucht genau diesen Stacktrace, um "die KI rechnet" von "es haengt wirklich" zu unterscheiden.
     */
    private volatile Thread gameThread;

    public WebGuiGame(Transport out) {
        this.out = out;
        this.broker = new ChoiceBroker(out);
    }

    public ChoiceBroker broker() {
        return broker;
    }

    // ---- Zustand raus -------------------------------------------------------------

    /** Bündelt beliebig viele Updates zu einem Push auf dem UI-Thread. */
    private void push() {
        if (getGameView() == null) return;
        if (dirty.compareAndSet(false, true)) {
            GuiBase.getInterface().invokeInEdtLater(() -> {
                dirty.set(false);
                pushState();
            });
        }
    }

    /** Muss vor dem Spielstart gerufen werden, sonst zeigt der Snapshot keine Teams. */
    public void setTeams(List<Integer> teams) {
        this.teams = teams;
    }

    /**
     * Sitz-Id -> Teamnummer. {@code PlayerView} traegt kein Team, deshalb kommt die Zuordnung aus der
     * Liste, mit der {@code HumanMatch} auch {@code setTeamNumber} gerufen hat; die Sitzreihenfolge von
     * {@code GameView.getPlayers()} ist die Registrierungsreihenfolge (Forge befuellt die Liste einmal
     * im Konstruktor von {@code Game} und kuerzt sie nicht, wenn ein Sitz ausscheidet). Passt die Laenge
     * nicht, liefert die Methode eine leere Zuordnung - lieber keine Teams anzeigen als falsche.
     *
     * <p>Die Zuordnung setzt voraus, dass der menschliche Sitz zuerst registriert ist ({@code
     * HostedMatch.startMatch} sortiert {@code LobbyPlayerHuman} stabil nach vorn, {@code HumanMatch.start}
     * legt den Menschen ohnehin zuerst an, {@code startSpectator} hat keinen) - eine kuenftige
     * Sitzreihenfolge, die das bricht, widerspraeche der Engine stillschweigend.
     */
    public static Map<Integer, Integer> teamsById(List<Integer> teams, List<Integer> seatIds) {
        if (teams == null || teams.size() != seatIds.size()) {
            return Map.of();
        }
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < seatIds.size(); i++) {
            out.put(seatIds.get(i), teams.get(i));
        }
        return Map.copyOf(out);
    }

    /**
     * Die Teamzuordnung fuer den aktuellen Snapshot: Sitz-Ids aus der Ansicht, Nummern aus {@link #teams}.
     *
     * <p>Ohne Ansicht (oder ohne Sitzliste) leer statt Ausnahme: {@link #finishGame()} ruft das auch dann,
     * wenn die Partie nie angemeldet wurde, und darf davor nicht scheitern - sonst ginge genau das
     * Spielende verloren, fuer das {@code finishGame()} und {@link #spielendeNachliefern()} da sind.</p>
     */
    Map<Integer, Integer> teamMap(GameView gv) {
        if (gv == null || gv.getPlayers() == null) {
            return Map.of();
        }
        return teamsById(teams, gv.getPlayers().stream().map(PlayerView::getId).toList());
    }

    /**
     * Schaltet "Partnerhand zeigen". Wirksam ab dem naechsten {@link #mayView}-Aufruf - in der Praxis also
     * ab der naechsten Partie, weil {@code Bridge.startGame} nur bei stehendem Tisch startet
     * ({@code match.isRunning()}-Sperre). Gehoert hierher und nicht in {@code HumanMatch}:
     * die Teams sind eine Spielregel (die Engine braucht sie), der Haken nur eine Frage, was der Tisch
     * anzeigt - er beruehrt das Spiel nicht und wirkt allein in {@link #mayView}. {@code Bridge.startGame}
     * setzt ihn bei JEDEM Start, auch mit {@code false}, damit er nicht aus der Vorpartie haengen bleibt.
     * Ohne Teams und ohne menschlichen Sitz bleibt er wirkungslos.
     */
    public void setRevealPartnerHand(boolean on) {
        this.revealPartnerHand = on;
    }

    /**
     * Gehoert die Karte in die HAND eines Sitzes, der mit dem Betrachter im selben Team sitzt (und nicht
     * er selbst ist)? Bewusst eng: nur die Hand - nicht Bibliothek, nicht verdeckte Karten im Exil -,
     * nur ein echtes Team (Nummer {@code >= 0}, sonst saessen zwei Sitze "ohne Team" als Partner da) und
     * nur mit menschlichem Sitz ({@code meId != null}).
     */
    static boolean istPartnerhand(int controllerId, ZoneType zone, Integer meId, Map<Integer, Integer> teams) {
        if (zone != ZoneType.Hand || meId == null || teams.isEmpty() || controllerId == meId) {
            return false;
        }
        Integer meines = teams.get(meId);
        Integer seines = teams.get(controllerId);
        return meines != null && meines >= 0 && meines.equals(seines);
    }

    /**
     * Darf der Tisch diese Karte zeigen? Forges Antwort gilt unveraendert; dazu kommt, wenn der Haken
     * "Partnerhand zeigen" an ist, die Hand des Teampartners.
     *
     * <p>Das ist ABSICHTLICH unsere Entscheidung in der Bridge und nicht Forges: {@code CardView.canBeShownTo}
     * zeigt eine Hand nur ihrem Beherrscher und wuerde hier Nein sagen. Wer das "angleichen" will, nimmt
     * dem Spieler die Partnerhand wieder weg - die Abweichung ist gewollt (Kevins Wunsch, Team-Modi).</p>
     *
     * <p>Der Haken gilt fuer den ganzen Tisch: alle Browser teilen einen Spielzustand, ein zweiter,
     * mitschauender Browser sieht die Partnerhand also ebenfalls. Das ist bekannt und angenommen - es gibt
     * hier nur einen menschlichen Sitz und keine geheimen Haende zwischen Browsern.</p>
     */
    @Override
    public boolean mayView(CardView c) {
        if (super.mayView(c)) {
            return true;
        }
        // Die Zonenpruefung steht hier UND in istPartnerhand: hier als Abkuerzung, damit nicht fuer jede
        // Karte des Tisches die Teamzuordnung gebaut wird - dort als Teil der Regel selbst, die ohne
        // Spielzustand testbar bleiben soll. Keine der beiden ist ueberfluessig.
        if (!revealPartnerHand || c == null || c.getZone() != ZoneType.Hand || c.getController() == null) {
            return false;
        }
        GameView gv = getGameView();
        if (gv == null || getLocalPlayers().isEmpty()) {
            return false;
        }
        return istPartnerhand(c.getController().getId(), c.getZone(),
                getLocalPlayers().iterator().next().getId(), teamMap(gv));
    }

    /** Sofort einen Snapshot senden (z. B. nach Reconnect). Auf dem UI-Thread aufrufen. */
    public void pushState() {
        GameView gv = getGameView();
        if (gv == null) return;
        boolean isSpectator = getLocalPlayers().isEmpty();
        PlayerView me = isSpectator ? null : getLocalPlayers().iterator().next();
        ViewContext ctx = new ViewContext(me, teamMap(gv), this::mayView, this::darfDarunterSehen, this::isSelectable, this::isWeaklySelectable,
                this::isHighlighted, p -> isTargetingInput(), prompt,
                new Messages.StopsMsg(Stops.names(stops.own()), Stops.names(stops.opp())), fullControl, isSpectator);
        Snapshot snap = StateSerializer.snapshot(gv, ctx);
        lastSnapshot = snap;
        touchTicker();                           // ein Zustands-Push ist sichtbarer Fortschritt
        out.send(snap);
    }

    // ---- Denk-Anzeige ------------------------------------------------------------

    /**
     * Neue Partie: frischer Ticker. Der Spiel-Thread ist noch unbekannt - der Log-Observer traegt
     * ihn beim ersten Ereignis nach, ein alter waere fuer den Wachhund schlicht der falsche.
     */
    private void startTicker() {
        stopTicker();
        gameThread = null;
        abschlussGesendet = false;
        nachliefernAngestossen = false;
        ThinkingTicker t = new ThinkingTicker(out::send, System::nanoTime, this::priorityPlayer,
                this::thinkingInfo, () -> gameThread, this::waitingForHuman, this::spielendeNachliefern);
        ticker = t;
        t.start();
    }

    private void stopTicker() {
        ThinkingTicker t = ticker;
        ticker = null;
        if (t != null) {
            t.close();
        }
    }

    private void touchTicker() {
        ThinkingTicker t = ticker;
        if (t != null) {
            t.touch();
        }
    }

    /**
     * Wartet die Bridge gerade auf <em>mich</em>? Dann ruht die Denk-Anzeige samt Wachhund: waehrend ich
     * ueberlege, steht Forges Spiel-Thread im {@code ChoiceBroker} - genau der Stack, den der Wachhund als
     * echten Haenger meldet. Zwei Minuten Nachdenken wuerden also ein Beweismittel erzeugen, das keines ist.
     *
     * <p>Zwei Faelle: eine offene Frage im Broker (Dialog, Kartenwahl) oder - noch ohne Frage - der Sitz mit
     * Prioritaet gehoert mir (Forge wartet im Input auf den Klick, z. B. auf "Zug beenden"). Im
     * Zuschauer-Modus ist {@code getLocalPlayers()} leer, dort greift nur der erste Fall (und auch der
     * praktisch nie).</p>
     */
    private boolean waitingForHuman() {
        if (!broker.pending().isEmpty()) {
            return true;
        }
        Integer prio = priorityPlayer();
        if (prio == null) {
            return false;
        }
        for (PlayerView p : getLocalPlayers()) {
            if (prio.equals(p.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Darf ich sehen, was unter einer verdeckten Karte liegt?
     *
     * <p>Forge beantwortet das fuer jede Herkunft an einer Stelle - eigener Morph/Manifest, per
     * Hideaway verbanntes Exil, Foretell, Cloak: {@code AbstractGuiGame.mayFlip} prueft, ob der
     * andere Zustand der Ansicht gezeigt werden darf. Fuer eine NICHT verdeckte Karte sagt es auch
     * ja (Transform, Meld), deshalb fragt {@code StateSerializer} vorher {@code isFaceDown}.</p>
     */
    private boolean darfDarunterSehen(CardView cv) {
        return mayFlip(cv);
    }

    private Integer priorityPlayer() {
        Snapshot s = lastSnapshot;
        return s == null ? null : s.priorityPlayer();
    }

    /**
     * Kontextzeile fuer den Wachhund: wer am Zug ist, in welcher Phase und in welchem Zug.
     *
     * <p>Zusaetzlich die Frage, die zwei Vorfaelle am 2026-10-06 offen liessen: ist das Spiel
     * ueberhaupt noch im Gang? Die Zahlen kommen aus dem letzten GESENDETEN Zustand und sind bei einem
     * Haenger naturgemaess alt; der Blick auf die {@code GameView} dagegen ist frisch. Steht dort
     * "beendet", waehrend der Wachhund bellt, ist die Diagnose ohne weiteres Nachsehen klar: die Partie
     * ist vorbei und die Bridge hat es nicht mitbekommen (es fehlt {@code finishGame()}), statt dass
     * irgendwo etwas haengt. Nur {@link GameView#isGameOver()} lesen, nie {@code Game.isGameOver()} -
     * letzteres nimmt den Monitor des Spiels und kann sich mit dem Spiel-Thread verklemmen (siehe
     * {@code HumanMatch.end()}).</p>
     */
    private String thinkingInfo() {
        Snapshot s = lastSnapshot;
        if (s == null) {
            return null;
        }
        String name = s.players().stream()
                .filter(p -> Integer.valueOf(p.id()).equals(s.priorityPlayer()))
                .map(Snapshot.PlayerSnap::name).findFirst().orElse("unknown");
        GameView gv = getGameView();
        String over = gv != null && gv.isGameOver()
                ? " - WARNING: GameView reports the game as OVER" : "";
        return "Priority: " + name + ", phase: " + s.phase() + ", turn " + s.turn() + over;
    }

    /** Forge markiert Spieler nicht als wählbar – wir leiten es aus dem aktiven Input ab. */
    private boolean isTargetingInput() {
        if (getLocalPlayers().isEmpty() || observedQueue == null) return false;
        return observedQueue.getInput() instanceof InputSelectTargets;
    }

    public int currentSeq() {
        return seq.get();
    }

    /** Forges Input-Objekt hat gewechselt: neue Sequenz, damit veraltete Klicks verworfen werden. */
    void onInputChanged() {
        synchronized (this) {
            int s = seq.incrementAndGet();
            Snapshot.PromptSnap p = prompt;
            // Buttons deaktivieren, bis das neue Input sie ueber updateButtons() selbst wieder freigibt –
            // sonst kann zwischen Sequenzwechsel und Forges eigenem showMessage() kurz auf das alte Prompt geklickt werden.
            prompt = new Snapshot.PromptSnap(p.message(), p.card(), p.okLabel(), p.cancelLabel(), false, false, s);
        }
        push();
    }

    boolean seqOk(Integer clientSeq) {
        return clientSeq == null || clientSeq == seq.get();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void setOriginalGameController(PlayerView player, IGameController gameController) {
        super.setOriginalGameController(player, gameController);
        watchInputQueueOf(gameController);
        applyFullControlPref();
    }

    /**
     * Zuschauer-Sitz (KI-only-Spiel, siehe {@code HostedMatch.registerSpectator}): kein
     * {@link #setOriginalGameController} wird je aufgerufen (kein lokaler Spieler), also muss die
     * seq-Beobachtung hier ansetzen – sonst bleibt {@code seq} auf ihrem Startwert stehen, waehrend
     * Forges Pause/Resume-Input ({@code InputPlaybackControl}) laengst gewechselt hat, und jeder
     * Klick des Browsers wird als veraltet verworfen (siehe {@link #seqOk}).
     */
    @Override
    @SuppressWarnings("deprecation")
    public void setSpectator(IGameController spectator) {
        super.setSpectator(spectator);
        watchInputQueueOf(spectator);
    }

    @SuppressWarnings("deprecation")
    private void watchInputQueueOf(IGameController controller) {
        if (observedQueue != null && inputObserver != null) {
            observedQueue.deleteObserver(inputObserver);
        }
        if (controller instanceof PlayerControllerHuman pch) {
            observedQueue = pch.getInputQueue();
            inputObserver = (o, arg) -> onInputChanged();
            observedQueue.addObserver(inputObserver);
        }
    }

    public Stops stops() { return stops; }
    public boolean fullControl() { return fullControl; }

    public void setStops(Stops s) {
        stops = s;
        push();
    }

    public void setFullControl(boolean on) {
        fullControl = on;
        applyFullControlPref();
        push();
    }

    /** Volle Kontrolle schaltet auch Forges Auto-Pass-ohne-Aktionen ab – pro Controller (wechselt je Spiel). */
    void applyFullControlPref() {
        IGameController c = getGameController();
        if (c == null || c.getYieldController() == null) return;
        String value = fullControl ? "false" : "true";
        c.getYieldController().setPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, value);
        if (c instanceof PlayerControllerHuman pch) {
            // Forges eigener Weg: wertet ein bereits sitzendes Prompt sofort neu aus (tryAutoPassNow()),
            // sonst wirkt der Pref-Wechsel erst beim naechsten Input.
            pch.setYieldPref(FPref.YIELD_AUTO_PASS_NO_ACTIONS, value);
        }
    }

    /**
     * Wird einmal je beginnender Partie mit dem frischen {@code Game} gerufen, bevor der Spiel-Thread
     * laeuft - {@link mtgplayer.match.HumanMatch} haengt hier den {@code MatchRecorder} an. Siehe
     * {@link #setGameView}, warum das der Anmeldepunkt ist.
     */
    public void onNewGame(Consumer<Game> hook) {
        hookedGame = null;                       // gibt auch die Referenz auf das alte Spiel frei
        newGameHook = hook;
    }

    /**
     * {@code HostedMatch.startGame()} ruft das (mit {@code null}, dann mit der neuen Ansicht) noch
     * auf dem AUFRUFENDEN Thread auf, bevor es das Spiel per {@code game.getAction().invoke(...)} an
     * Forges Spiel-Thread uebergibt - fuer den menschlichen Sitz wie fuer den Zuschauer-Pfad. Das ist
     * damit der frueheste Zeitpunkt, an dem das {@code Game} existiert, und der einzige ohne Rennen
     * gegen die ersten Ereignisse der Partie (Mulligans fallen sonst schon).
     *
     * <p>Der Haken feuert genau einmal je Spiel, denn {@code setGameView} kommt oefter: schon im
     * normalen Start einmal mit {@code null} und einmal mit der Ansicht, und bei einem Teilspiel
     * (Shahrazad) ruft {@code HostedMatch.visit(GameEventSubgameStart/SubgameEnd)} die Folge
     * {@code null}/Ansicht je ZWEIMAL - am Teilspielende wieder mit dem HAUPTspiel, das laengst einen
     * Recorder hat. Ohne Schutz waeren das mehrere Datensaetze je Partie und ein
     * {@code HumanMatch.recorder}, der auf den falschen zeigt. Deshalb zwei Bedingungen: dasselbe
     * {@code Game} wie zuletzt wird uebersprungen (Identitaetsvergleich, {@code Game} hat kein
     * eigenes {@code equals}), und ein Teilspiel ({@code getMaingame() != null}) ist gar keine Partie
     * und wird nie gehakt.</p>
     */
    @Override
    public void setGameView(GameView gameView0) {
        super.setGameView(gameView0);
        watchLogOf(gameView0);
        Game game = gameView0 == null ? null : gameView0.getGame();
        if (game != null && game != hookedGame && game.getMaingame() == null) {
            hookedGame = game;
            startTicker();
            // Der Haken haengt den Recorder an; die Denk-Anzeige laeuft unabhaengig davon, ob sich
            // jemand angemeldet hat.
            Consumer<Game> hook = newGameHook;
            if (hook != null) {
                hook.accept(game);
            }
        }
        push();
    }

    /**
     * Wirft die Match-Buchhaltung der Elternklasse ({@link AbstractGuiGame#resetForNewMatch}) weg und
     * leert zusaetzlich unseren Log-Puffer – {@code HostedMatch} ruft {@code setGameView(null)} und
     * danach {@code setGameView(view)} einmal pro Spiel auf, der alte Log darf nicht ins naechste
     * Spiel durchsickern.
     */
    @Override
    public void resetForNewMatch() {
        super.resetForNewMatch();
        hookedGame = null;
        stopTicker();
        lastSnapshot = null;
        synchronized (recentLog) { recentLog.clear(); logId = 0; }
        watchLogOf(null);
    }

    /** Kopie der letzten Log-Zeilen (älteste zuerst, mit ihren ids) – für den Reconnect. */
    public List<Messages.LogLine> recentLog() {
        synchronized (recentLog) {
            return new ArrayList<>(recentLog);
        }
    }

    /** Vergibt die naechste id (je Partie fortlaufend, ab 1) und puffert die Zeile damit. Gibt die
     *  Zeile MIT id zurueck, damit Aufrufer genau die gepufferte Fassung versenden. */
    private Messages.LogLine remember(Messages.LogLine line) {
        synchronized (recentLog) {
            Messages.LogLine withId = line.withId(++logId);
            recentLog.addLast(withId);
            while (recentLog.size() > LOG_MAX) recentLog.removeFirst();
            return withId;
        }
    }

    private void sendLog(Messages.LogLine line) {
        touchTicker();                           // eine Log-Zeile ist sichtbarer Fortschritt
        out.send(remember(line));
    }

    /**
     * Der Observer laeuft auf dem Game-Thread (ausgeloest von {@code GameLog.notifyObservers()}),
     * {@code watchLogOf} auf dem UI-Thread (aus {@code setGameView}/{@code resetForNewMatch}) – ohne
     * gemeinsames Lock koennte der Reset von {@code logSeen} mit dem Lesen/Hochzaehlen im Observer
     * racen (z. B. ein veralteter Observer schreibt nach dem Reset noch in den neuen Cursor).
     * Deshalb beides unter demselben Lock wie den Puffer ({@code recentLog}, reentrant); {@code
     * out.send} bewusst erst danach, damit das Lock waehrend der (potenziell blockierenden) I/O nicht
     * gehalten wird.
     */
    @SuppressWarnings("deprecation")
    private void watchLogOf(GameView gv) {
        if (observedLog != null && logObserver != null) {
            observedLog.deleteObserver(logObserver);
        }
        observedLog = null;
        logObserver = null;
        synchronized (recentLog) { logSeen = 0; }
        if (gv == null || gv.getGameLog() == null) return;
        GameLog log = gv.getGameLog();
        logObserver = (o, arg) -> {
            // Der Observer laeuft auf Forges Spiel-Thread - der einzige Ort, an dem wir ihn ohne
            // Forge-Interna bekommen. Genau dessen Stacktrace will der Wachhund.
            gameThread = Thread.currentThread();
            List<Messages.LogLine> fresh = new ArrayList<>();
            synchronized (recentLog) {
                // Veralteter Observer eines vorigen Spiels (watchLogOf inzwischen erneut aufgerufen,
                // observedLog zeigt schon auf den neuen Log) - nichts mehr tun, sonst schreibt er noch
                // in den neuen Cursor/Puffer hinein.
                if (observedLog != log) return;
                List<GameLogEntry> all = log.getAllEntries();
                for (; logSeen < all.size(); logSeen++) {
                    GameLogEntry e = all.get(logSeen);
                    Messages.LogLine line = new Messages.LogLine(e.message(), e.type().name(),
                            e.sourceCard() == null ? null : e.sourceCard().getId());
                    fresh.add(remember(line));
                }
            }
            touchTicker();                       // neue Log-Zeilen = sichtbarer Fortschritt
            fresh.forEach(out::send);
        };
        observedLog = log;
        log.addObserver(logObserver);
    }

    @Override protected void updateCurrentPlayer(PlayerView player) { push(); }
    @Override public void openView(TrackableCollection<PlayerView> myPlayers) { push(); }
    @Override public void updateZones(Iterable<PlayerZoneUpdate> zonesToUpdate) { push(); }
    @Override public void updateCards(Iterable<CardView> cards) { push(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { push(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { push(); }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { push(); }
    @Override public void updateStack() { push(); }
    @Override public void updatePhase(boolean saveState) { push(); }
    @Override public void updateTurn(PlayerView player) { push(); }
    @Override public void updatePlayerControl() { push(); }
    @Override public void refreshField() { push(); }
    @Override public void showCombat() { push(); }
    @Override public void setPanelSelection(CardView hostCard) { }
    @Override public void setCard(CardView card) { }
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) { }
    @Override public void enableOverlay() { }
    @Override public void disableOverlay() { }
    @Override public void flashIncorrectAction() { out.send(new Messages.ErrorMsg("You can't do that right now.")); }
    @Override public void alertUser() { }
    @Override public void showManaPool(PlayerView player) { push(); }
    @Override public void hideManaPool(PlayerView player) { push(); }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) { super.setSelectables(cards, min, max); push(); }
    @Override public void clearSelectables() { super.clearSelectables(); push(); }
    @Override public void setWeaklySelectable(Iterable<CardView> cards) { super.setWeaklySelectable(cards); push(); }
    @Override public void clearWeaklySelectable() { super.clearWeaklySelectable(); push(); }
    @Override public void setHighlighted(Iterable<GameEntityView> entities, boolean b) { super.setHighlighted(entities, b); push(); }

    @Override
    public synchronized void showPromptMessage(PlayerView playerView, String message, CardView card) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(message == null ? "" : message, card == null ? null : card.getId(),
                p.okLabel(), p.cancelLabel(), p.okEnabled(), p.cancelEnabled(), p.seq());
        push();
    }

    @Override
    public synchronized void updateButtons(PlayerView owner, String label1, String label2, boolean enable1, boolean enable2, boolean focus1) {
        Snapshot.PromptSnap p = prompt;
        prompt = new Snapshot.PromptSnap(p.message(), p.card(), label1, label2, enable1, enable2, p.seq());
        push();
    }

    /**
     * Forges Abschluss der Partie - die einzige Stelle, die dem Browser "gameOver" schickt und den
     * Wachhund abschaltet.
     *
     * <p>Wiederholbar, weil zwei Wege hierher fuehren: Forges Ereigniskette und, wenn die ausbleibt,
     * {@link #spielendeNachliefern()}. Ein zweiter Aufruf darf dem Spieler kein zweites Spielende
     * anzeigen.</p>
     */
    @Override
    public void finishGame() {
        if (abschlussGesendet) {
            return;
        }
        Ausgang ausgang = ausgang(getGameView(), teamMap(getGameView()));
        broker.cancelAll();
        // pushState() statt push(): finishGame läuft auf dem UI-Thread (Forges Event-Handler), der finale
        // Snapshot muss den Client also synchron vor GameOver erreichen statt erst später über invokeInEdtLater.
        pushState();
        out.send(new Messages.GameOver(ausgang.winner(), ausgang.sitze()));
        abschlussGesendet = true;
        stopTicker();                            // beendet auch eine noch laufende Denk-Anzeige
    }

    /**
     * Wer die Partie gewonnen hat, wie der Abschluss-Dialog es braucht: {@code winner} ist der Name oder
     * das Teamkennzeichen ("Team 1"), {@code sitze} die Sitz-Ids dahinter. Ohne Sieger ist {@code winner}
     * {@code null} und {@code sitze} leer, nie {@code null} - der Browser muss nichts abfangen.
     */
    record Ausgang(String winner, List<Integer> sitze) {
        static final Ausgang KEIN_SIEGER = new Ausgang(null, List.of());
    }

    /**
     * Der Name fuer den Abschluss-Dialog, oder {@code null} wenn es keinen eindeutigen Sieger gibt.
     * Siehe {@link #ausgang(GameView, Map)} fuer die Gruende.
     */
    static String gewinner(GameView gv, Map<Integer, Integer> teams) {
        return ausgang(gv, teams).winner();
    }

    /**
     * Sieger und Sitze fuer den Abschluss-Dialog.
     *
     * <p>Mit Teams zuerst {@link #teamWinner(List, Map)}: in einer Team-Partie bleiben am Ende zwei Sitze
     * "nicht verloren" stehen, und Forges {@code getWinningPlayerName()} nennte dann hoechstens einen
     * von beiden - das Team hat aber gewonnen, nicht der Einzelne.</p>
     *
     * <p>Sonst Forges Namen, und wenn der fehlt: Forge fuellt {@code GameView.getWinningPlayerName()} in
     * {@code updateGameOver} nur, wenn {@code GameOutcome.getWinningLobbyPlayer()} einen Sitz findet,
     * dessen Statistik auf "hat gewonnen" steht. In einer am 2026-10-08 durchgespielten Partie (vier
     * Sitze, Grund {@code AllOpponentsLost}, drei Gegner auf 0 Leben) war das keiner: der Dialog zeigte
     * "Spiel beendet", waehrend der Datensatz KI 4 voellig richtig als Sieger fuehrte. Fuer eine
     * Zuschauer-Partie ist das die eine Auskunft, auf die man wartet. Deshalb der Rueckfall auf die
     * Sitze selbst ({@link #einzigerUeberlebender(List)}).</p>
     */
    static Ausgang ausgang(GameView gv, Map<Integer, Integer> teams) {
        if (gv == null || gv.getPlayers() == null) {
            return Ausgang.KEIN_SIEGER;
        }
        List<Integer> uebrigeIds = new ArrayList<>();
        List<String> uebrigeNamen = new ArrayList<>();
        for (PlayerView p : gv.getPlayers()) {
            if (!p.getHasLost()) {
                uebrigeIds.add(p.getId());
                uebrigeNamen.add(p.getName());
            }
        }
        String team = teamWinner(uebrigeIds, teams);
        if (team != null) {
            return new Ausgang(team, List.copyOf(uebrigeIds));
        }
        String name = gv.getWinningPlayerName();
        if (name == null || name.isBlank()) {
            name = einzigerUeberlebender(uebrigeNamen);
        }
        if (name == null) {
            return Ausgang.KEIN_SIEGER;
        }
        // Die Sitze zum Namen: ein einzelner Sitz, auch wenn Forge ihn nennt, waehrend ein zweiter
        // "nicht verloren" ist - der Dialog soll keinen zweiten Namen erfinden.
        List<Integer> sitze = new ArrayList<>();
        for (PlayerView p : gv.getPlayers()) {
            if (name.equals(p.getName())) {
                sitze.add(p.getId());
            }
        }
        return new Ausgang(name, List.copyOf(sitze));
    }

    /**
     * Gewinnt ein ganzes Team, sind mehrere Sitze "nicht verloren" - dann ist der Sieger das Team, sofern
     * alle Ueberlebenden dasselbe tragen. Ohne Teams (leere Zuordnung) bleibt es bei
     * {@link #einzigerUeberlebender(List)}: mehrere Ueberlebende sind dort ein Unentschieden, und
     * Ueberlebende aus verschiedenen Teams ebenso.
     */
    static String teamWinner(List<Integer> ueberlebendeIds, Map<Integer, Integer> teams) {
        if (teams.isEmpty() || ueberlebendeIds.isEmpty()) {
            return null;
        }
        Integer first = teams.get(ueberlebendeIds.get(0));
        if (first == null || first < 0) {
            return null;
        }
        for (int id : ueberlebendeIds) {
            if (!first.equals(teams.get(id))) {
                return null;
            }
        }
        return "Team " + first;
    }

    /**
     * Genau ein Sitz uebrig: der hat gewonnen. Keiner oder mehrere: kein Name - und damit bleibt es
     * beim bisherigen "Spiel beendet"/"Unentschieden". Bewusst nicht "der erste Ueberlebende": bei
     * einem echten Unentschieden stehen mehrere, und dann waere jeder Name eine Behauptung.
     */
    static String einzigerUeberlebender(List<String> nichtVerloren) {
        return nichtVerloren.size() == 1 ? nichtVerloren.get(0) : null;
    }

    /**
     * Rettungsnetz gegen einen Tisch, der stehenbleibt, weil das Spielende den Browser nie erreicht.
     *
     * <p>Am 2026-10-07 um 20:01 stand im Protokoll: Partie laut {@code GameView} beendet,
     * Spiel-Thread fertig, UI-Thread untaetig - und im Browser noch Zug 35. Der Weg von Forges
     * {@code GameEventGameFinished} zu {@link #finishGame()} fuehrt ueber
     * {@code FControlGameEventHandler}, und dessen Merker {@code processEventsQueued},
     * {@code gameFinished} und {@code gameOver} sind einfache, nicht-{@code volatile} Felder: der
     * Spiel-Thread schreibt sie, der UI-Thread liest sie. Bleibt einer davon fuer einen der beiden
     * veraltet, hoert die Oberflaeche auf, Ereignisse zu verarbeiten - mitten in der Partie und
     * dauerhaft. Der Fork behebt das an der Wurzel; dieses Netz haengt darunter, weil ein verlorenes
     * Spielende aus jeder kuenftigen Ursache genauso aussieht und den Spieler genauso ratlos vor den
     * Tisch setzt.
     *
     * <p>Laeuft je Takt der {@link ThinkingTicker Denk-Anzeige} (also etwa sekuendlich), schlaegt
     * genau einmal je Partie zu und faellt dann in {@link #finishGame()} auf dem UI-Thread - nicht
     * auf dem Taktgeber, der mit {@code pushState} nichts zu tun hat.
     */
    public void spielendeNachliefern() {
        if (abschlussGesendet || nachliefernAngestossen) {
            return;
        }
        GameView gv = getGameView();
        if (gv == null || !gv.isGameOver()) {
            return;
        }
        nachliefernAngestossen = true;
        CrashLog.note("Spielende", "nachgeliefert - die Partie war beendet, Forges Abschluss blieb aus");
        GuiBase.getInterface().invokeInEdtLater(this::finishGame);
    }

    @Override
    public void afterGameEnd() {
        super.afterGameEnd();
        // sonst ueberlebt die Auswahl/Prompt-Anzeige des letzten Spiels ins naechste
        clearSelectables();
        clearWeaklySelectable();
        prompt = Snapshot.PromptSnap.EMPTY.withSeq(seq.get());
    }

    // ---- Eingaben rein (UI-Thread) ------------------------------------------------

    private CardView cardById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.CardViewType, id);
    }

    private PlayerView playerById(int id) {
        GameView gv = getGameView();
        return gv == null ? null : gv.getTracker().getObj(TrackableTypes.PlayerViewType, id);
    }

    private static ITriggerEvent trigger(boolean alt) {
        return new ITriggerEvent() {
            @Override public int getButton() { return alt ? 3 : 1; }
            @Override public int getX() { return 0; }
            @Override public int getY() { return 0; }
        };
    }

    public boolean onSelectCard(int cardId, boolean alt, Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veralteter selectCard verworfen"); return false; }
        CardView cv = cardById(cardId);
        IGameController c = getGameController();
        if (cv == null || c == null) return false;
        if (!c.selectCard(cv, null, trigger(alt))) {
            flashIncorrectAction();
        }
        return true;
    }
    public void onSelectCard(int cardId, boolean alt) { onSelectCard(cardId, alt, null); }

    public boolean onSelectPlayer(int playerId, Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veralteter selectPlayer verworfen"); return false; }
        PlayerView pv = playerById(playerId);
        IGameController c = getGameController();
        if (pv == null || c == null) return false;
        c.selectPlayer(pv, trigger(false));
        return true;
    }
    public void onSelectPlayer(int playerId) { onSelectPlayer(playerId, null); }

    public boolean onOk(Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veraltetes ok verworfen"); return false; }
        IGameController c = getGameController();
        if (c == null) return false;
        c.selectButtonOk();
        return true;
    }
    public void onOk() { onOk(null); }

    public boolean onCancel(Integer clientSeq) {
        if (!seqOk(clientSeq)) { System.out.println("[bridge] veraltetes cancel verworfen"); return false; }
        IGameController c = getGameController();
        if (c == null) return false;
        c.selectButtonCancel();
        return true;
    }
    public void onCancel() { onCancel(null); }

    /**
     * Ob hier kein lokaler Sitz mehr im Spiel ist: entweder gibt es keinen (Zuschauer) oder jeder ist schon
     * ausgeschieden (Team-Partie, der Partner spielt weiter). In beiden Faellen hat {@link #onConcede()}
     * niemanden, den es fragen koennte - Forge fragt den lokalen Spieler, und der hat nichts mehr aufzugeben.
     */
    public boolean keinLokalerSitzImSpiel() {
        for (PlayerView p : getLocalPlayers()) {
            if (!p.getHasLost()) {
                return false;
            }
        }
        return true;
    }

    public void onConcede() {
        if (getGameView() == null) return;
        concede();
    }

    // ---- Synchrone Dialoge (Game-Thread) -------------------------------------------

    private <T> List<Messages.Option> options(List<T> choices, FSerializableFunction<T, String> display) {
        List<Messages.Option> out = new ArrayList<>();
        if (choices == null) return out;
        // Die Teamzuordnung bleibt unbenutzt: dieser Dialogweg baut nie einen PlayerSnap. Sie steht hier der
        // Einheitlichkeit mit pushState wegen - kein toter Code, bitte nicht loeschen (ViewContext verlangt sie).
        ViewContext ctx = getGameView() == null ? null : new ViewContext(
                getLocalPlayers().isEmpty() ? null : getLocalPlayers().iterator().next(),
                teamMap(getGameView()),
                this::mayView, this::darfDarunterSehen, c -> false, c -> false, e -> false, p -> false, Snapshot.PromptSnap.EMPTY,
                new Messages.StopsMsg(List.of(), List.of()), false, getLocalPlayers().isEmpty());
        int i = 0;
        for (T t : choices) {
            String label = display != null ? display.apply(t) : String.valueOf(t);
            Integer card = null;
            Integer player = t instanceof PlayerView pv ? pv.getId() : null;
            Snapshot.CardSnap detail = null;
            CardView cv = t instanceof CardView c ? c
                    : (t instanceof SpellAbilityView sav ? sav.getHostCard() : null);
            if (cv != null) {
                card = cv.getId();
                if (ctx != null) {
                    Snapshot.CardSnap snap = StateSerializer.cardSnap(cv, ctx);
                    detail = snap.faceDown() ? null : snap;
                }
            }
            out.add(new Messages.Option(i++, label, card, player, detail, null, null, null));
        }
        return out;
    }

    /** Index-Liste aus der Antwort; bei null/ungültig die ersten {@code min} Einträge, auf {@code max} gekappt. */
    private static List<Integer> indices(JsonNode value, int min, int max, int size) {
        List<Integer> out = new ArrayList<>();
        if (value != null && value.isArray()) {
            for (JsonNode n : value) {
                int i = n.asInt(-1);
                if (i >= 0 && i < size && !out.contains(i)) out.add(i);
            }
        } else if (value != null && value.isInt()) {
            int i = value.asInt();
            if (i >= 0 && i < size) out.add(i);
        }
        if (out.size() < Math.max(min, 0)) {
            out.clear();
            for (int i = 0; i < Math.min(Math.max(min, 0), size); i++) out.add(i);
        }
        if (max > 0 && out.size() > max) {
            out = new ArrayList<>(out.subList(0, max));
        }
        return out;
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices, List<T> selected,
                                  FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) return new ArrayList<>();
        // Forge nutzt min == max == -1 fuer reine Anzeige (reveal); kein Warten auf eine Antwort,
        // sonst blockiert der Game-Thread.
        if (min < 0 && max < 0) {
            broker.notify(message, message, options(choices, display), null);
            return new ArrayList<>();
        }
        String kind = max == 1 ? "one" : "many";
        JsonNode v = broker.ask(kind, message, message, options(choices, display), min, max, null);
        List<T> out = new ArrayList<>();
        for (int i : indices(v, min, max, choices.size())) out.add(choices.get(i));
        return out;
    }

    /**
     * Zwei Modi, je nach Forges "remaining objects"-Vertrag (siehe AbstractGuiGame#many/order):
     * <ul>
     *   <li>{@code remainingObjectsMin == 0 && remainingObjectsMax == 0}: volle Umsortierung – Antwort ist
     *       eine Permutation aller Elemente (kind {@code order}), Sicherheitsnetz füllt fehlende Indizes auf.</li>
     *   <li>sonst: Teilauswahl – {@code remainingObjectsMin/Max} begrenzen, wie viele Elemente in der Quelle
     *       bleiben dürfen ({@code -1} = unbegrenzt); die Antwort ist nur die gewählte Teilmenge (kind
     *       {@code many}), ohne Sicherheitsnetz.</li>
     * </ul>
     */
    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> all = new ArrayList<>(sourceChoices == null ? List.of() : sourceChoices);
        if (destChoices != null) all.addAll(destChoices);
        if (all.isEmpty()) return new OrderResult<>(new ArrayList<>(), false);

        int n = all.size();
        Integer card = referenceCard == null ? null : referenceCard.getId();

        if (remainingObjectsMin == 0 && remainingObjectsMax == 0) {
            JsonNode v = broker.ask("order", title, top, options(all, null), n, n, card);
            List<Integer> idx = indices(v, n, n, n);
            List<T> ordered = new ArrayList<>();
            for (int i : idx) ordered.add(all.get(i));
            for (T t : all) if (!ordered.contains(t)) ordered.add(t); // Sicherheitsnetz bei unvollständiger Antwort
            return new OrderResult<>(ordered, false);
        }

        int pickMin = remainingObjectsMax >= 0 ? Math.max(0, n - remainingObjectsMax) : 0;
        int pickMax = remainingObjectsMin >= 0 ? Math.max(0, n - remainingObjectsMin) : n;
        JsonNode v = broker.ask("many", title, top, options(all, null), pickMin, pickMax, card);
        List<Integer> idx = indices(v, pickMin, pickMax, n);
        List<T> chosen = new ArrayList<>();
        for (int i : idx) chosen.add(all.get(i));
        return new OrderResult<>(chosen, false);
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText, String noButtonText, boolean defaultYes) {
        List<Messages.Option> opts = List.of(new Messages.Option(0, yesButtonText, null, null),
                new Messages.Option(1, noButtonText, null, null));
        JsonNode v = broker.ask("confirm", title, message, opts, 1, 1, null);
        return v == null || v.isNull() ? defaultYes : v.asBoolean(defaultYes);
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        String yes = options != null && options.size() > 0 ? options.get(0) : "Yes";
        String no = options != null && options.size() > 1 ? options.get(1) : "No";
        List<Messages.Option> opts = List.of(new Messages.Option(0, yes, null, null), new Messages.Option(1, no, null, null));
        JsonNode v = broker.ask("confirm", c == null ? "" : c.getCurrentState().getName(), question, opts, 1, 1,
                c == null ? null : c.getId());
        return v == null || v.isNull() ? defaultIsYes : v.asBoolean(defaultIsYes);
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        JsonNode v = broker.ask("one", title, message, options(options, null), 1, 1, null);
        List<Integer> idx = indices(v, 0, 1, options.size());
        return idx.isEmpty() ? Math.max(0, defaultOption) : idx.get(0);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon, String initialInput,
                                  List<String> inputOptions, boolean isNumeric) {
        if (inputOptions != null && !inputOptions.isEmpty()) {
            JsonNode v = broker.ask("one", title, message, options(inputOptions, null), 1, 1, null);
            List<Integer> idx = indices(v, 0, 1, inputOptions.size());
            return idx.isEmpty() ? (initialInput == null ? inputOptions.get(0) : initialInput) : inputOptions.get(idx.get(0));
        }
        JsonNode v = broker.ask(isNumeric ? "number" : "text", title, message, List.of(), 0, 0, null);
        // Freie Eingabe: null/NullNode heißt Abbruch (Forges getInteger-Cutoff-Schleife re-prompt't sonst endlos).
        if (v == null || v.isNull()) return null;
        return v.isNumber() ? String.valueOf(v.asInt()) : v.asText();
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        JsonNode v = broker.ask("ability", hostCard == null ? "" : hostCard.getCurrentState().getName(),
                "Which ability?", options(abilities, null), 0, 1, hostCard == null ? null : hostCard.getId());
        List<Integer> idx = indices(v, 0, 1, abilities.size());
        return idx.isEmpty() ? null : abilities.get(idx.get(0));
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                                                      DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), isOptional ? 0 : 1, 1, null);
        List<Integer> idx = indices(v, isOptional ? 0 : 1, 1, list.size());
        return idx.isEmpty() ? null : list.get(idx.get(0));
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                                                        int min, int max, DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) return new ArrayList<>();
        List<GameEntityView> list = new ArrayList<>(optionList);
        JsonNode v = broker.ask("entities", title, title, options(list, null), min, max, null);
        List<GameEntityView> out = new ArrayList<>();
        for (int i : indices(v, min, max, list.size())) out.add(list.get(i));
        return out;
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        List<CardView> all = new ArrayList<>();
        if (cards != null) for (CardView c : cards) all.add(c);
        if (all.isEmpty()) return all;
        Set<Integer> movable = new HashSet<>();
        if (manipulable != null) for (CardView c : manipulable) movable.add(c.getId());
        // ViewContext (in options()) einmal fuer alle Karten aufbauen statt pro Karte neu.
        List<Messages.Option> base = options(all, null);
        List<Messages.Option> opts = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Messages.Option b = base.get(i);
            opts.add(new Messages.Option(i, b.label(), b.card(), null, b.detail(), null, null,
                    movable.contains(all.get(i).getId()) ? Boolean.TRUE : null));
        }
        String where = toAnywhere ? "anywhere" : (toTop && toBottom ? "top or bottom" : toTop ? "top only" : "bottom only");
        List<String> flags = new ArrayList<>();
        if (toAnywhere) {
            flags.add("anywhere");
        } else {
            if (toTop) flags.add("top");
            if (toBottom) flags.add("bottom");
        }
        JsonNode v = broker.ask("cardlist", title, "Movable cards: " + where + ". Top card first.",
                opts, all.size(), all.size(), null, null, null, flags);
        List<Integer> idx = indices(v, all.size(), all.size(), all.size());
        if (idx.size() != all.size()) return all; // keine gültige Permutation → unverändert
        List<CardView> out = new ArrayList<>();
        for (int k : idx) out.add(all.get(k));
        return out;
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        return new ArrayList<>(); // kein Sideboarding
    }

    /** Zahlen-Liste je Option; null, wenn Antwort fehlt/ungültig oder die Summe nicht passt. */
    private static List<Integer> amounts(JsonNode v, int n, int total, List<Integer> maxPer, boolean atLeastOne) {
        if (v == null || !v.isArray() || v.size() != n) return null;
        List<Integer> out = new ArrayList<>();
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int a = v.get(i).asInt(-1);
            if (a < 0) return null;
            if (atLeastOne && a < 1) return null;
            Integer max = maxPer.get(i);
            if (max != null && max > 0 && a > max) return null;
            out.add(a);
            sum += a;
        }
        return sum == total ? out : null;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers, int damage,
                                                     GameEntityView defender, boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        if (damage <= 0) return out;
        List<CardView> targets = blockers == null ? new ArrayList<>() : new ArrayList<>(blockers);
        boolean trample = attacker != null && attacker.getCurrentState().hasTrample() && defender != null;
        List<Messages.Option> opts = new ArrayList<>();
        List<Integer> maxPer = new ArrayList<>();
        int i = 0;
        for (CardView b : targets) {
            opts.add(new Messages.Option(i++, b.getCurrentState().getName(), b.getId(), null, null, null,
                    Math.max(1, b.getLethalDamage()), null));
            maxPer.add(null);
        }
        if (trample) {
            Integer pid = defender instanceof PlayerView pv ? pv.getId() : null;
            Integer cid = defender instanceof CardView dc ? dc.getId() : null;
            opts.add(new Messages.Option(i++, "Defender (trample)", cid, pid, null, null, null, null));
            maxPer.add(null);
        }
        if (opts.isEmpty()) return out; // keine Blocker, kein Trample-Ziel → nichts zu fragen
        String title = (attacker == null ? "Attacker" : attacker.getCurrentState().getName()) + " - assign " + damage + " damage";
        JsonNode v = broker.ask("damage", title, "Assign damage freely among blockers (lethal is only a hint, no required order).",
                opts, opts.size(), opts.size(), attacker == null ? null : attacker.getId(), damage, Boolean.FALSE);
        List<Integer> a = amounts(v, opts.size(), damage, maxPer, false);
        if (a == null) {
            return autoAssign(targets, damage, trample);
        }
        for (int k = 0; k < targets.size(); k++) {
            if (a.get(k) > 0) out.put(targets.get(k), a.get(k));
        }
        if (trample && a.get(targets.size()) > 0) {
            out.put(null, a.get(targets.size()));
        }
        return out.isEmpty() ? autoAssign(targets, damage, trample) : out;
    }

    /** M2-Verhalten als Rückfallebene: tödlich in Reihenfolge, Rest auf den letzten bzw. bei Trample auf den Verteidiger. */
    private static Map<CardView, Integer> autoAssign(List<CardView> blockers, int damage, boolean trample) {
        Map<CardView, Integer> out = new LinkedHashMap<>();
        int rest = damage;
        for (CardView b : blockers) {
            int give = Math.min(Math.max(1, b.getLethalDamage()), rest);
            out.put(b, give);
            rest -= give;
            if (rest <= 0) break;
        }
        if (rest > 0) {
            if (trample) {
                out.put(null, rest);
            } else if (!out.isEmpty()) {
                CardView last = null;
                for (CardView b : out.keySet()) last = b;
                out.put(last, out.get(last) + rest);
            }
        }
        return out;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target, int amount,
                                                    boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> out = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) return out;
        List<Object> keys = new ArrayList<>(target.keySet());
        List<Messages.Option> opts = new ArrayList<>();
        List<Integer> maxPer = new ArrayList<>();
        int i = 0;
        for (Object k : keys) {
            Integer card = k instanceof CardView cv ? cv.getId() : null;
            Integer player = k instanceof PlayerView pv ? pv.getId() : null;
            Integer max = target.get(k);
            String label = k instanceof forge.card.MagicColor.Color col ? colorName(col) : String.valueOf(k);
            opts.add(new Messages.Option(i++, label, card, player, null, max, null, null));
            maxPer.add(max);
        }
        String title = amount + " " + (amountLabel == null ? "" : amountLabel) + " verteilen";
        JsonNode v = broker.ask("amount", title, effectSource == null ? "" : effectSource.getCurrentState().getName(),
                opts, opts.size(), opts.size(), effectSource == null ? null : effectSource.getId(), amount, atLeastOne);
        List<Integer> a = amounts(v, keys.size(), amount, maxPer, atLeastOne);
        // Fallback bei fehlender/ungueltiger Antwort: mit atLeastOne bekommt jeder Schluessel mindestens 1,
        // den Rest der erste; sonst (wie bisher) alles auf den ersten.
        int rest = Math.max(0, amount - keys.size());
        for (int k = 0; k < keys.size(); k++) {
            int fallback = atLeastOne ? (k == 0 ? 1 + rest : 1) : (k == 0 ? amount : 0);
            out.put(keys.get(k), a == null ? fallback : a.get(k));
        }
        return out;
    }

    private static String colorName(forge.card.MagicColor.Color col) {
        return switch (col) {
            case WHITE -> "White";
            case BLUE -> "Blue";
            case BLACK -> "Black";
            case RED -> "Red";
            case GREEN -> "Green";
            case COLORLESS -> "Colorless";
            default -> col.name();
        };
    }

    // ---- Sonstiges ------------------------------------------------------------------

    @Override public void message(String message, String title) { sendLog(new Messages.LogLine(title + ": " + message)); }
    @Override public void showErrorDialog(String message, String title) { out.send(new Messages.ErrorMsg(title + ": " + message)); }

    @Override public GameState getGamestate() { return null; }

    /** true = Phase überspringen. Ohne Spielzustand wird der eigene Zug angenommen. */
    @Override
    public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
        if (fullControl || phase == null) return false;
        if (getGameView() != null && getLocalPlayers().isEmpty()) {
            // Echter Zuschauer-Sitz (KI-only-Spiel laeuft): niemand ist "der eigene Sitz" –
            // jeder Zug zaehlt wie ein Gegnerzug. Ausserhalb eines Spiels (getGameView() == null,
            // z. B. vor dem ersten Spielstart) bleibt es bei der bisherigen Annahme unten (eigener Zug).
            return !stops.stopsAt(false, phase);
        }
        boolean ownTurn = playerTurn == null || getLocalPlayers().contains(playerTurn);
        return !stops.stopsAt(ownTurn, phase);
    }
}
