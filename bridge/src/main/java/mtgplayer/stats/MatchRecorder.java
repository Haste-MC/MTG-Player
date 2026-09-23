package mtgplayer.stats;

import com.google.common.eventbus.Subscribe;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.player.LobbyPlayerHuman;
import mtgplayer.ai.AiLobbyPlayer;
import mtgplayer.forge.CrashLog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Zaehlt eine laufende Forge-Partie ueber ihren Ereignisbus mit und liefert am Ende einen
 * {@link MatchRecord}. Reine Buchhaltung: der Recorder aendert nie etwas am Spiel.
 *
 * <p>Anmeldung im Konstruktor ({@code game.subscribeToEvents(this)}), wie es {@code AiMatch} schon
 * fuer {@code GameEventTurnEnded} tut. Forges {@code Game} kennt keine Abmeldung (Guava-EventBus,
 * nur {@code register}); nach {@link #finish()} ignoriert der Recorder deshalb alle weiteren
 * Ereignisse, statt sich abzumelden - das Spiel wird danach ohnehin weggeworfen.</p>
 *
 * <p><b>Zugbegriffe.</b> Forge zaehlt jeden Spielerzug einzeln hoch ({@code PhaseHandler.turn}), in
 * einer Vierer-Runde ist Zug 5 also der zweite Zug des ersten Sitzes. Der Datensatz benutzt daher
 * zwei Zaehlungen, jeweils dort, wo sie etwas aussagen:</p>
 * <ul>
 *   <li>{@code turns} (Partie) und {@code eliminatedTurn} (Sitz) sind Forges globale Zugnummer -
 *       "wie lange ging die Partie" bzw. "wann war dieser Sitz raus".</li>
 *   <li>{@code landsByTurn}, {@code firstMissedLandDrop} und {@code firstCommanderTurn} zaehlen die
 *       EIGENEN Zuege des Sitzes (1, 2, 3, …). Nur so heisst "Laender bis Zug 3" in der Auswertung
 *       fuer jeden Sitz dasselbe, unabhaengig von der Spielerzahl.</li>
 * </ul>
 * <p>{@code landsByTurn} ist kumulativ und nach eigenem Zug indiziert: Index 0 ist immer 0 (vor dem
 * ersten eigenen Zug), Index n die Gesamtzahl gespielter Laender bis einschliesslich des n-ten
 * eigenen Zuges. "Laender bis Zug 3" ist damit schlicht {@code landsByTurn.get(3)}.
 * <b>Die Liste hat genau {@code eigeneZuege + 1} Eintraege</b> - ein Sitz, der nur zwei eigene Zuege
 * hatte, liefert {@code [0, 1, 2]} und sonst nichts. Wer nach "Zug 5" fragt, muss die Laenge pruefen
 * (in der Auswertung: kuerzere Partien bei diesem Mittelwert auslassen, nicht mit 0 fuellen).</p>
 *
 * <p><b>Verpasste Landabgabe.</b> Bei jedem {@code GameEventTurnBegan} wird der vorige Zug
 * abgeschlossen: war sein Besitzer noch im Spiel, hatte Karten auf der Hand und hat in diesem Zug kein
 * Land gespielt, zaehlt eine verpasste Abgabe (der erste Zug jedes Sitzes zaehlt mit). Der beim
 * Spielende laufende Zug wird nicht mehr gewertet - er war noch nicht vorbei.</p>
 *
 * <p><b>Nicht gewertet.</b> Ein Absturz waehrend der Partie ({@link #markCrashed()}, gemeldet von
 * {@link CrashLog}), ein Abbruch von aussen ({@link #markAborted()}), der Zugdeckel
 * ({@link #markTurnCapped()}), ein Sitz mit Verlustgrund {@code Conceded} oder weniger als drei Zuege
 * setzen {@code counted = false} mit Grund. Reihenfolge absichtlich so: ein Absturz erklaert auch den
 * Abbruch, den Deckel, die kurze Partie oder die Aufgabe danach, und die Aufgabe erklaert die kurze
 * Partie. Bei Absturz und Abbruch traegt ausserdem KEIN Sitz {@code winner} und die Partie ist kein
 * Remis - beim Zugdeckel dagegen bleiben die Sitz-Ausgaenge stehen, siehe {@link #build()}.</p>
 *
 * <p>Alle Ereignis-Methoden und {@link #finish()} laufen unter demselben Monitor. Forge feuert die
 * Ereignisse zwar alle auf dem Spiel-Thread, {@link #finish()} kann aber von aussen kommen (Test,
 * Abbruch) und {@link #markCrashed()} von einem beliebigen Thread mit einer uncaught exception.</p>
 */
public final class MatchRecorder {

    /** Weniger Zuege als das zaehlen nicht als Partie (Spec: "zu kurz"). */
    public static final int MIN_TURNS = 3;

    /** Nach {@link #finish()} {@code null} - siehe dort, warum die Forge-Objekte losgelassen werden. */
    private Game game;
    private final String source;
    private final Integer aiTimeout;
    private final Consumer<MatchRecord> sink;
    private final Instant startedAt = Instant.now();
    private final List<Seat> seats = new ArrayList<>();
    private final Map<PlayerView, Seat> byView = new HashMap<>();
    private final Runnable crashListener = this::markCrashed;

    private int turns;
    private Seat turnOwner;
    private int landsThisTurn;
    private volatile boolean crashed;
    private volatile boolean aborted;
    private volatile boolean turnCapped;
    private volatile MatchRecord finished;

    /** Ohne bekannte Bedenkzeit ({@code aiTimeout == null}) - fuer Aufrufer, die sie nicht setzen. */
    public MatchRecorder(Game game, String source, Consumer<MatchRecord> sink) {
        this(game, source, null, sink);
    }

    /**
     * @param game      das laufende Spiel; der Recorder meldet sich sofort an seinem Ereignisbus an
     * @param source    {@code "live"}, {@code "spectate"} oder {@code "sparring"}
     * @param aiTimeout Bedenkzeit je KI-Entscheidung in Sekunden, wie der Starter sie gesetzt hat
     *                  ({@code Game.AI_TIMEOUT}); {@code null} wenn unbekannt. Steht im Datensatz,
     *                  weil sich eine lange Partie sonst nicht mehr mit ihrem Budget erklaeren laesst.
     * @param sink      bekommt den fertigen Datensatz genau einmal (aus {@link #finish()}); darf
     *                  {@code null} sein (Tests, oder wenn der Aufrufer selbst abholt)
     */
    public MatchRecorder(Game game, String source, Integer aiTimeout, Consumer<MatchRecord> sink) {
        this.game = game;
        this.source = source;
        this.aiTimeout = aiTimeout;
        this.sink = sink;
        // getRegisteredPlayers() statt getPlayers(): die Liste schrumpft nicht, wenn ein Sitz
        // ausscheidet - sonst fehlte am Ende genau der Sitz, dessen Niederlage wir festhalten wollen.
        for (Player p : game.getRegisteredPlayers()) {
            Seat seat = new Seat(p);
            seats.add(seat);
            byView.put(p.getView(), seat);
        }
        CrashLog.addCrashListener(crashListener);
        game.subscribeToEvents(this);
    }

    // ------------------------------------------------------------------ Ereignisse

    @Subscribe
    public synchronized void onTurnBegan(GameEventTurnBegan e) {
        if (finished != null) {
            return;
        }
        closeRunningTurn();
        noteEliminations();                      // noch mit der Nummer des gerade beendeten Zuges
        turns = Math.max(turns, e.turnNumber());
        turnOwner = seat(e.turnOwner());
        landsThisTurn = 0;
        if (turnOwner != null) {
            turnOwner.ownTurns++;
            turnOwner.landsByTurn.add(turnOwner.lands);
        }
    }

    @Subscribe
    public synchronized void onLandPlayed(GameEventLandPlayed e) {
        Seat s = seat(e.player());
        if (finished != null || s == null) {
            return;
        }
        s.lands++;
        if (s.ownTurns > 0) {
            s.landsByTurn.set(s.landsByTurn.size() - 1, s.lands);
        }
        if (s == turnOwner) {
            landsThisTurn++;
        }
    }

    @Subscribe
    public synchronized void onMulligan(GameEventMulligan e) {
        Seat s = seat(e.player());
        if (finished != null || s == null) {
            return;
        }
        s.mulligans++;
    }

    /**
     * Das Ereignis traegt nur Ansichten ({@code SpellAbilityView}/{@code CardView}), keine
     * Spielobjekte - Mana-Summe und Commander-Eigenschaft kommen daher aus der Ansicht der
     * Ursprungskarte. Zugeordnet wird dem Beherrscher dieser Karte: ein Zauber auf dem Stapel wird
     * von dem beherrscht, der ihn gewirkt hat.
     */
    @Subscribe
    public synchronized void onSpellCast(GameEventSpellAbilityCast e) {
        if (finished != null) {
            return;
        }
        SpellAbilityView sa = e.sa();
        if (sa == null || !sa.isSpell()) {
            return;
        }
        CardView host = sa.getHostCard();
        Seat s = host == null ? null : seat(controllerOf(host));
        if (s == null) {
            return;
        }
        s.spells++;
        ManaCost cost = host.getCurrentState() == null ? null : host.getCurrentState().getManaCost();
        if (cost != null) {
            s.spellMana += cost.getCMC();
        }
        if (host.isCommander()) {
            s.commanderCasts++;
            if (s.firstCommanderTurn == null) {
                // Ein Commander kann per Blitz auch im gegnerischen Zug kommen; dann zaehlt der
                // eigene Zug, in dem der Sitz zuletzt am Zug war (mindestens 1).
                s.firstCommanderTurn = Math.max(1, s.ownTurns);
            }
        }
    }

    @Subscribe
    public synchronized void onPlayerDamaged(GameEventPlayerDamaged e) {
        if (finished != null) {
            return;
        }
        Seat target = seat(e.target());
        if (target != null) {
            target.damageTaken += e.amount();
            if (e.combat()) {
                target.combatDamageTaken += e.amount();
            }
        }
        CardView src = e.source();
        Seat dealer = src == null ? null : seat(controllerOf(src));
        if (dealer != null) {
            dealer.damageDealt += e.amount();
        }
    }

    /** Forge feuert das Ereignis aus {@code Match.startGame}, wenn Ergebnis und Log stehen. */
    @Subscribe
    public void onGameFinished(GameEventGameFinished e) {
        finish();
    }

    /**
     * Aus {@link CrashLog}: die laufende Partie kommt nicht in die Wertung.
     *
     * <p>Bewusst NICHT {@code synchronized}: {@code CrashLog.report} ist {@code static synchronized}
     * und ruft von dort aus die Listener. Haette diese Methode den Recorder-Monitor genommen, waere
     * die Umkehrung moeglich - ein zweiter Thread in {@link #finish()} haelt den Recorder-Monitor und
     * landet ueber den Sink ({@code MatchStore} meldet eine kaputte Datei) in {@code CrashLog.report},
     * das auf den Klassen-Monitor wartet, den der erste Thread haelt. Die zwei {@code volatile}
     * Flags reichen hier: ein Absturz genau im Moment des Abschlusses darf so oder so fallen.</p>
     */
    public void markCrashed() {
        if (finished == null) {
            crashed = true;
        }
    }

    /**
     * Die Partie wurde von aussen abgewuergt ({@code HumanMatch.end()}, z. B. weil der Zuschauer
     * aufhoert oder ein neues Spiel startet) - sie kommt nicht in die Wertung. Ohne das waere der
     * Datensatz irrefuehrend: {@code end()} beendet ueber {@code GameEndReason.AllHumansLost}, und
     * {@code Player.onGameOver()} macht dabei JEDEN Sitz ohne eigenen Ausgang zum Sieger.
     */
    public void markAborted() {
        if (finished == null) {
            aborted = true;
        }
    }

    /**
     * Die Partie hat den Zugdeckel erreicht ({@code AiMatch}: nach {@code maxTurns} Spielerzuegen) - sie
     * kommt nicht in die Wertung. Anders als bei Abbruch und Absturz bleiben die Sitz-Ausgaenge dabei
     * stehen: {@code AiMatch} setzt alle Sitze auf {@code intentionalDraw()} und beendet mit
     * {@code GameEndReason.Draw}, das Ergebnis im Datensatz ist also ein echtes Remis ohne Sieger und
     * erfindet nichts. Nur fachlich hat sich niemand auf ein Remis geeinigt - die Partie wurde
     * abgeschnitten, und deshalb zaehlt sie nicht in die Bilanz.
     *
     * <p>Wie {@link #markCrashed()} bewusst ohne Monitor: der Aufruf kommt aus einem Ereignis-Haken auf
     * dem Spiel-Thread, das {@code volatile} Flag reicht.</p>
     */
    public void markTurnCapped() {
        if (finished == null) {
            turnCapped = true;
        }
    }

    // ------------------------------------------------------------------ Abschluss

    /**
     * Baut den Datensatz aus der Buchhaltung und dem Endzustand des Spiels und reicht ihn genau
     * einmal an den Sink weiter. Weitere Aufrufe liefern denselben Datensatz zurueck, ohne den Sink
     * noch einmal zu bedienen - {@code GameEventGameFinished} und ein Aufruf von aussen duerfen sich
     * ueberschneiden.
     */
    public MatchRecord finish() {
        MatchRecord built = build();
        if (built == null) {
            return finished;                     // ein anderer Aufruf war zuerst da
        }
        // Erst ausserhalb des Monitors - der Sink schreibt eine Datei und kann ueber CrashLog
        // wieder hier hereinlaufen (siehe markCrashed).
        CrashLog.removeCrashListener(crashListener);
        if (sink != null) {
            sink.accept(built);
        }
        return built;
    }

    /** @return der frisch gebaute Datensatz, oder {@code null}, wenn schon einer stand */
    private synchronized MatchRecord build() {
        if (finished != null) {
            return null;
        }
        turns = Math.max(turns, game.getPhaseHandler().getTurn());
        noteEliminations();

        GameOutcome outcome = game.getOutcome();
        // Bei Abbruch oder Absturz hat NIEMAND gewonnen und es ist auch kein Remis: HumanMatch.end()
        // beendet ueber GameEndReason.AllHumansLost, und Player.onGameOver() macht dabei jeden Sitz ohne
        // eigenen Ausgang zum Sieger. Wer so eine Zeile im Screen wieder auf "gewertet" stellt, bekaeme
        // sonst frei erfundene Siege in die Bilanz.
        // Der Zugdeckel steht bewusst NICHT hier: dort setzt AiMatch alle Sitze selbst auf
        // intentionalDraw(), das Remis ohne Sieger ist also der echte Ausgang und darf so im Datensatz
        // stehen - nur gewertet wird die Partie nicht (siehe markTurnCapped).
        boolean noWinners = crashed || aborted;
        List<MatchRecord.Seat> out = new ArrayList<>();
        boolean conceded = false;
        boolean anyWinner = false;
        for (Seat s : seats) {
            MatchRecord.Seat seat = s.toRecord(turns, noWinners);
            conceded |= "Conceded".equals(seat.lossReason());
            anyWinner |= seat.winner();
            out.add(seat);
        }

        String excludeReason = crashed ? "Absturz" : aborted ? "abgebrochen"
                : turnCapped ? "Zugdeckel" : conceded ? "aufgegeben" : turns < MIN_TURNS ? "zu kurz" : null;
        Instant endedAt = Instant.now();
        finished = new MatchRecord(newId(endedAt), iso(startedAt), iso(endedAt),
                Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()), source, aiTimeout, turns,
                outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition()),
                !noWinners && (!anyWinner || (outcome != null && outcome.getWinCondition() == GameEndReason.Draw)),
                excludeReason == null, excludeReason, List.copyOf(out));
        // Der Datensatz steht; ab hier braucht niemand mehr die Forge-Objekte. Ohne das haelt der
        // Recorder ueber seats/byView das ganze Game fest, solange ihn irgendwer noch referenziert
        // (HumanMatch.recorder bis zum naechsten Spiel, oder der CrashLog-Listener, falls ein
        // haengender Spiel-Thread finish() nie erreicht).
        seats.clear();
        byView.clear();
        turnOwner = null;
        game = null;
        return finished;
    }

    // ------------------------------------------------------------------ Hilfen

    private Seat seat(PlayerView view) {
        return view == null ? null : byView.get(view);
    }

    private static PlayerView controllerOf(CardView c) {
        return c.getController() != null ? c.getController() : c.getOwner();
    }

    /**
     * Wertet den gerade zu Ende gegangenen Zug auf eine verpasste Landabgabe aus. Nur ein Zug mit
     * Karten auf der Hand zaehlt (Spec §1: "waehrend er eine Hand hatte") - wer leergespielt ist, kann
     * kein Land legen, und ohne die Bedingung waere die Kennzahl vor allem ein Mass fuer die Spiellaenge.
     */
    private void closeRunningTurn() {
        if (turnOwner == null) {
            return;
        }
        if (landsThisTurn == 0 && turnOwner.player.getOutcome() == null
                && !turnOwner.player.getCardsIn(ZoneType.Hand).isEmpty()) {
            turnOwner.missedLandDrops++;
            if (turnOwner.firstMissedLandDrop == null) {
                turnOwner.firstMissedLandDrop = turnOwner.ownTurns;
            }
        }
        turnOwner = null;
    }

    /**
     * Haelt den Zug fest, in dem ein Sitz ausgeschieden ist. Forge setzt den Ausgang eines Spielers,
     * sobald er verliert ({@code Player.setOutcome}), kennt aber keinen Zeitpunkt dazu - deshalb
     * schauen wir zu Beginn jedes Zuges und beim Abschluss nach, wer neu einen Ausgang hat. Beim
     * Abschluss bekommen alle Spieler einen Ausgang (auch die Sieger), der Zeitpunkt wird deshalb nur
     * fuer Sitze uebernommen, die tatsaechlich verloren haben (siehe {@link Seat#toRecord}).
     */
    private void noteEliminations() {
        for (Seat s : seats) {
            if (s.eliminatedTurn == null && s.player.getOutcome() != null) {
                s.eliminatedTurn = Math.max(1, turns);
            }
        }
    }

    private static String iso(Instant t) {
        return t.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /**
     * Zufaelliger Startwert je JVM-Lauf, danach hochgezaehlt: innerhalb eines Laufs sind die Ids
     * garantiert verschieden - auch wenn mehrere Partien in dieselbe Millisekunde fallen (Stueck 3
     * schreibt Sparring-Partien in Folge zurueck, und {@code MatchStore.delete} traefe bei einer
     * doppelten Id den falschen Datensatz) - und ueber Laeufe hinweg trotzdem nicht vorhersagbar.
     */
    private static final AtomicInteger ID_SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt());

    /** Zeitpunkt des Endes (ISO, Millisekunden) + "-" + 6 Hex-Zeichen; sichtbar fuer den Test. */
    static String newId(Instant endedAt) {
        return endedAt.truncatedTo(ChronoUnit.MILLIS) + "-"
                + String.format("%06x", ID_SEQ.getAndIncrement() & 0xffffff);
    }

    /** Laufende Zaehlung eines Sitzes; wird am Ende in {@link MatchRecord.Seat} umgegossen. */
    private static final class Seat {
        private final Player player;
        private final List<Integer> landsByTurn = new ArrayList<>(List.of(0));
        private int ownTurns;
        private int mulligans;
        private int lands;
        private int missedLandDrops;
        private Integer firstMissedLandDrop;
        private int spells;
        private int spellMana;
        private int commanderCasts;
        private Integer firstCommanderTurn;
        private int damageDealt;
        private int damageTaken;
        private int combatDamageTaken;
        private Integer eliminatedTurn;

        private Seat(Player player) {
            this.player = player;
        }

        /** @param noWinners Abbruch/Absturz: kein Sitz gilt als Sieger (siehe {@link MatchRecorder#build}) */
        private MatchRecord.Seat toRecord(int turns, boolean noWinners) {
            PlayerOutcome o = player.getOutcome();
            boolean winner = !noWinners && o != null && o.hasWon();
            String lossReason = o == null || o.lossState == null ? null : o.lossState.name();
            Integer eliminated = lossReason == null ? null : eliminatedTurn != null ? eliminatedTurn : Math.max(1, turns);
            return new MatchRecord.Seat(player.getName(), deckName(), human(), ai(), winner, lossReason,
                    eliminated, mulligans, lands, List.copyOf(landsByTurn), missedLandDrops,
                    firstMissedLandDrop, spells, spellMana, commanderCasts, commanderTax(),
                    firstCommanderTurn, damageDealt, damageTaken, combatDamageTaken,
                    player.getLife(), player.getPoisonCounters());
        }

        /** Der Name, unter dem die Partie gestartet wurde (Lobby-Auswahl) - danach gruppiert die Statistik. */
        private String deckName() {
            RegisteredPlayer rp = player.getRegisteredPlayer();
            Deck d = rp == null ? null : rp.getDeck();
            return d == null ? null : d.getName();
        }

        private boolean human() {
            return player.getLobbyPlayer() instanceof LobbyPlayerHuman;
        }

        private MatchRecord.Ai ai() {
            return player.getLobbyPlayer() instanceof AiLobbyPlayer ai
                    ? new MatchRecord.Ai(ai.config().mode().json(), ai.config().profile()) : null;
        }

        /** Commander-Steuer nach CR 903.8: 2 je vorherigem Wirken, aufsummiert ueber alle Commander. */
        private int commanderTax() {
            int tax = 0;
            for (Card c : player.getCommanders()) {
                tax += 2 * Math.max(0, player.getCommanderCast(c) - 1);
            }
            return tax;
        }
    }
}
