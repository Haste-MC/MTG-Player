package mtgplayer.stats;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameEntityView;
import forge.game.GameOutcome;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameFinished;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.game.zone.ZoneView;
import forge.player.LobbyPlayerHuman;
import mtgplayer.ai.AiLobbyPlayer;
import mtgplayer.forge.CrashLog;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>Vorfall-Kennzahlen (Runde B).</b> Ueber Zauber, Karten und Verluste zaehlt der Recorder nur,
 * was Forge typisiert liefert: {@code GameEventCardDestroyed} und {@code GameEventTokenCreated}
 * haben keine Nutzlast, Verluste und Spielsteine kommen deshalb aus
 * {@code GameEventCardChangeZone}. Kein Zaehler darf eine Partie abschiessen - jeder neue Handler
 * faengt {@code RuntimeException} und zaehlt den Fall nur in {@link #counterFailures()} mit.</p>
 *
 * <p><b>Kampf und Zeitachse (Runde B, Stueck 2).</b> Angriff und Block kommen aus
 * {@code GameEventAttackersDeclared}/{@code GameEventBlockersDeclared}, der Schaden samt seiner
 * Quelle aus {@code GameEventPlayerDamaged}, der Lebensgewinn aus
 * {@code GameEventPlayerLivesChanged}. Die Zeitachse haengt am Zugbeginn: je eigenem Zug ein Punkt
 * mit dem Stand aus dem Spielzustand, gedeckelt auf {@link #TIMELINE_MAX}.</p>
 *
 * <p><b>Robustheit (Runde B, Stueck 3).</b> Zwei Zaehler bleiben sonst unsichtbar, weil sie keine
 * eigene Kennzahl im Datensatz sind: {@link #counterFailures} (ein Handler ist in eine
 * {@code RuntimeException} gelaufen) und {@code castsNotOnStack} (ein gewirkter Zauber liess sich in
 * {@link #classifyCast} nicht mehr auf dem Stapel finden - {@code counterspellsCast} und
 * {@code removalCast} lesen sich dann unauffaellig als 0, ohne dass irgendwo auffaellt, dass gar
 * nicht gesucht werden konnte). Beide
 * werden am Partieende genau einmal ueber {@code CrashLog.note("MatchRecorder", ...)} gemeldet (nur
 * ins Log, kein Browser-Hinweis - siehe {@link CrashLog#note}), und nur, wenn mindestens einer der
 * beiden ueber 0 liegt.</p>
 *
 * <p><b>Nachtrag zu Runde B.</b> Vier Kennzahlen kosten jetzt fast nichts und liessen sich spaeter aus
 * keinem Datensatz mehr rekonstruieren: {@code TurnPoint.spells} (Zauber je Zugabschnitt, siehe
 * {@link #notePointSpell}), {@code handEnd} (Handkarten beim Ausscheiden, siehe
 * {@link #rememberHands}), {@code openingLands} (Laender der behaltenen Eroeffnungshand, siehe
 * {@link #noteOpeningHands}) und {@code removalCast} (gewirkte Entfernung, siehe
 * {@link #classifyCast}). Alle vier sind rein additiv - ein v1-Datensatz liest sie als 0.</p>
 *
 * <p>Alle Ereignis-Methoden und {@link #finish()} laufen unter demselben Monitor. Forge feuert die
 * Ereignisse zwar alle auf dem Spiel-Thread, {@link #finish()} kann aber von aussen kommen (Test,
 * Abbruch) und {@link #markCrashed()} von einem beliebigen Thread mit einer uncaught exception.</p>
 *
 * <p><b>Kartenbiografie (Runde C, Stueck 1).</b> Zusaetzlich zu den Sitz-Kennzahlen zaehlt der Recorder
 * je Sitz aus {@code ownDecks} ({@code Map<Integer, CardTally>}, siehe {@link Seat#byCardId}) mit, was
 * JEDE einzelne Karte getan hat - Grundlage fuer den Schnittgrund "hat in deinen Partien nie gewirkt"
 * (Spec {@code 2026-09-25-kartenaufzeichnung-design.md}). Gezaehlt wird je Karten-<b>Id</b>, nicht je
 * Name: {@code CardView} traegt bei einer verdeckten Karte (Morph, Manifest) nicht ihren wahren Namen,
 * nur die Id bleibt verlaesslich. Erst in {@link #finish()} loest {@code Player.getAllCards()} - echte
 * {@code Card}-Objekte mit wahrem Namen - jede Id auf ihren endgueltigen Namen und ihre Endzone auf; das
 * deckt auch Karten ab, die die ganze Partie unangetastet in einer Zone lagen (nie gezogen, nie
 * gewirkt) und deshalb in keinem Ereignis vorkamen. Das Ergebnis geht ueber {@link CardLog#merge} je
 * Name zusammengefasst an {@code cardSink} und steht ueber {@link #cardLog()} bereit. Wie beim
 * {@link MatchRecord} selbst gilt: {@link #markCrashed()}, {@link #markAborted()} und
 * {@link #markTurnCapped()} liefern KEINEN {@code CardLog} - eine halbe Aufzeichnung wuerde die
 * Kartenauswertung verfaelschen, genau wie sie nicht in die Bilanz ({@code counted}) zaehlt. Eine "zu
 * kurze" oder aufgegebene, aber sauber beendete Partie liefert dagegen sehr wohl einen {@code CardLog} -
 * ihre Kartendaten sind vollstaendig, nur {@code CardStats} (ein spaeteres Stueck) filtert sie beim
 * Auswerten ueber {@code MatchRecord.counted} heraus.</p>
 */
public final class MatchRecorder {

    /** Weniger Zuege als das zaehlen nicht als Partie (Spec: "zu kurz"). */
    public static final int MIN_TURNS = 3;

    /**
     * Ab so vielen eigenen bleibenden Karten in EINEM Fenster heisst der Verlust Massenentfernung.
     * Kampfschaden zaehlt dafuer nicht mit (siehe {@link #closeSweepWindow}).
     */
    public static final int SWEEP_MIN = 3;

    /**
     * So viele Punkte fasst die Zeitachse eines Sitzes hoechstens. Eine Partie, die aus dem Ruder
     * laeuft, darf den Datensatz nicht aufblaehen; abgeschnitten wird hinten, weil die Frage "wann
     * bist du zurueckgefallen" am Anfang haengt.
     */
    public static final int TIMELINE_MAX = 60;

    /**
     * So viele zuletzt aufgeloeste Zauber merkt sich {@link #justResolved}. Forge feuert
     * {@code GameEventSpellRemovedFromStack} unmittelbar nach dem zugehoerigen
     * {@code GameEventSpellResolved}; die Liste braucht also nur Luft fuer verschachtelte
     * Aufloesungen, nicht fuer die ganze Partie.
     */
    private static final int RESOLVED_MEMORY = 16;

    /** Nach {@link #finish()} {@code null} - siehe dort, warum die Forge-Objekte losgelassen werden. */
    private Game game;
    private final String source;
    private final Integer aiTimeout;
    /** Deckname je {@link RegisteredPlayer}, vom Aufrufer gegeben; siehe der 5-arg-Konstruktor. */
    private final Map<RegisteredPlayer, String> deckNames;
    /** Decknamen, die eine Kartenbiografie bekommen (siehe {@link Seat#byCardId}); leer ohne Kartensenke. */
    private final Set<String> ownDecks;
    private final Consumer<MatchRecord> sink;
    /** Bekommt die fertige {@link CardLog} genau einmal (aus {@link #finish()}); darf {@code null} sein. */
    private final Consumer<CardLog> cardSink;
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
    /** Siehe {@link #cardLog()}; wird zusammen mit {@code finished} in {@link #build()} gesetzt. */
    private CardLog cardLog;
    private int counterFailures;
    /** Die Eroeffnungshaende sind einmal je Partie zu zaehlen; siehe {@link #noteOpeningHands}. */
    private boolean openingTaken;
    /** Siehe {@link #classifyCast}: ein gewirkter Zauber war beim Nachschlagen nicht mehr auf dem Stapel. */
    private int castsNotOnStack;

    /**
     * Die zuletzt aufgeloesten Zauber-Ansichten, aelteste zuerst. Verglichen wird ueber
     * Objektidentitaet: {@code SpellAbility.getView()} liefert immer dieselbe Instanz, und
     * {@code SpellAbilityView} hat kein eigenes {@code equals}.
     */
    private final Deque<SpellAbilityView> justResolved = new ArrayDeque<>();

    /** Ohne bekannte Bedenkzeit ({@code aiTimeout == null}) - fuer Aufrufer, die sie nicht setzen. */
    public MatchRecorder(Game game, String source, Consumer<MatchRecord> sink) {
        this(game, source, null, sink);
    }

    /** Ohne eigene Decknamen (siehe unten) - fuer Aufrufer, die nur Forges (womoeglich sanitierten)
     *  Namen kennen, etwa Tests. */
    public MatchRecorder(Game game, String source, Integer aiTimeout, Consumer<MatchRecord> sink) {
        this(game, source, aiTimeout, Map.of(), sink);
    }

    /**
     * Ohne Kartenbiografie (leeres {@code ownDecks}, keine Kartensenke) - fuer Aufrufer, die Runde C
     * noch nicht kennen; siehe den 7-arg-Konstruktor fuer die volle Beschreibung der Parameter.
     */
    public MatchRecorder(Game game, String source, Integer aiTimeout, Map<RegisteredPlayer, String> deckNames,
                          Consumer<MatchRecord> sink) {
        this(game, source, aiTimeout, deckNames, Set.of(), sink, null);
    }

    /**
     * @param game      das laufende Spiel; der Recorder meldet sich sofort an seinem Ereignisbus an
     * @param source    {@code "live"}, {@code "spectate"} oder {@code "sparring"}
     * @param aiTimeout Bedenkzeit je KI-Entscheidung in Sekunden, wie der Starter sie gesetzt hat
     *                  ({@code Game.AI_TIMEOUT}); {@code null} wenn unbekannt. Steht im Datensatz,
     *                  weil sich eine lange Partie sonst nicht mehr mit ihrem Budget erklaeren laesst.
     * @param deckNames Deckname je {@link RegisteredPlayer}, so wie ihn der Aufrufer VOR dem Spielstart
     *                  kannte. Noetig, weil {@code RegisteredPlayer.getDeck()} nicht das Original liefert,
     *                  sondern eine Kopie ({@code originalDeck.copyTo(originalDeck.getName())}), und deren
     *                  {@code DeckBase}-Konstruktor jeden Schraegstrich im Namen zu einem Unterstrich
     *                  saniert (Forge-Fork, {@code RegisteredPlayer.restoreDeck}/{@code DeckBase}) - bei
     *                  Kevins Decks etwa {@code "…" // Umbris, Fear Manifest} zu {@code "…" __ Umbris,
     *                  Fear Manifest}. {@code originalDeck} ist privat und hat keinen Getter ("never
     *                  return or modify this instance"), der Recorder kann sich den unsanitierten Namen
     *                  also nicht selbst zurueckholen - nur der Aufrufer kennt ihn, er hat das Deck ja
     *                  selbst aufgeloest, BEVOR er es an {@code RegisteredPlayer.forCommander(...)}
     *                  uebergeben hat. Fehlt ein Sitz in der Map (aeltere Aufrufer, Tests), faellt
     *                  {@link Seat#deckName()} auf Forges (womoeglich sanitierten) Namen zurueck - siehe
     *                  auch die anderen Konstruktoren. {@code null} wird wie eine leere Map behandelt.
     * @param ownDecks  Decknamen (vergleichen wie {@link Seat#deckName()} sie liefert), fuer die eine
     *                  Kartenbiografie gefuehrt wird - typischerweise Kevins eigene Decks aus dem
     *                  {@code DeckStore}, siehe Klassenkommentar "Kartenbiografie". Ein Sitz mit einem
     *                  Deck ausserhalb dieser Menge zaehlt keine einzige Karte und erzeugt am Ende auch
     *                  keine {@code CardLog.SeatCards}-Zeile. {@code null} wird wie eine leere Menge
     *                  behandelt.
     * @param sink      bekommt den fertigen Datensatz genau einmal (aus {@link #finish()}); darf
     *                  {@code null} sein (Tests, oder wenn der Aufrufer selbst abholt)
     * @param cardSink  bekommt die fertige {@link CardLog} genau einmal (aus {@link #finish()}), oder
     *                  gar nicht, wenn die Partie keine liefert (siehe Klassenkommentar); darf
     *                  {@code null} sein wie {@code sink}
     */
    public MatchRecorder(Game game, String source, Integer aiTimeout, Map<RegisteredPlayer, String> deckNames,
                          Set<String> ownDecks, Consumer<MatchRecord> sink, Consumer<CardLog> cardSink) {
        this.game = game;
        this.source = source;
        this.aiTimeout = aiTimeout;
        this.deckNames = deckNames == null ? Map.of() : deckNames;
        this.ownDecks = ownDecks == null ? Set.of() : ownDecks;
        this.sink = sink;
        this.cardSink = cardSink;
        // getRegisteredPlayers() statt getPlayers(): die Liste schrumpft nicht, wenn ein Sitz
        // ausscheidet - sonst fehlte am Ende genau der Sitz, dessen Niederlage wir festhalten wollen.
        for (Player p : game.getRegisteredPlayers()) {
            Seat seat = new Seat(p, this.deckNames, this.ownDecks);
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
        rememberHands();
        noteOpeningHands();                      // beim ersten Zug: die behaltene Eroeffnungshand
        closeRunningTurn();
        noteEliminations();                      // noch mit der Nummer des gerade beendeten Zuges
        turns = Math.max(turns, e.turnNumber());
        turnOwner = seat(e.turnOwner());
        landsThisTurn = 0;
        if (turnOwner != null) {
            turnOwner.ownTurns++;
            turnOwner.landsByTurn.add(turnOwner.lands);
            notePoint(turnOwner, turnOwner.ownTurns);   // ab v3 der eigene Zug, nicht e.turnNumber() (Forges globaler)
        }
    }

    @Subscribe
    public synchronized void onLandPlayed(GameEventLandPlayed e) {
        Seat s = seat(e.player());
        if (finished != null || s == null) {
            return;
        }
        rememberHands();
        s.lands++;
        if (s.ownTurns > 0) {
            s.landsByTurn.set(s.landsByTurn.size() - 1, s.lands);
        }
        if (s == turnOwner) {
            landsThisTurn++;
        }
        // Kartenbiografie: ein Land wird nicht gewirkt, zaehlt hier aber als gespielt (Spec §2) -
        // sonst fehlte jedes Land in der Kartentabelle, dabei ist "wann kommt mein Land" fuer Kevin
        // genauso interessant wie ein Zauber.
        try {
            CardView land = e.land();
            CardTally t = land == null ? null : cardTally(s, land);
            if (t != null) {
                t.name = land.getName();
                t.cast++;
                if (t.castTurn == null) {
                    // Der EIGENE Zug des Sitzes, nicht Forges globale Zugnummer - dieselbe Zaehlweise
                    // wie firstCommanderTurn/firstMissedLandDrop. Mit dem globalen Zaehler stuende in
                    // einer 4er-Runde ungefaehr das Vierfache, und "im Schnitt ab Zug 5" waere mit
                    // einem Duell nicht vergleichbar.
                    t.castTurn = Math.max(1, s.ownTurns);
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    @Subscribe
    public synchronized void onMulligan(GameEventMulligan e) {
        Seat s = seat(e.player());
        if (finished != null || s == null) {
            return;
        }
        rememberHands();
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
        rememberHands();
        try {
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
            notePointSpell(s);
            ManaCost cost = host.getCurrentState() == null ? null : host.getCurrentState().getManaCost();
            if (cost != null) {
                s.spellMana += cost.getCMC();
            }
            // Kartenbiografie (Spec §2): cast je Karten-Id, castTurn beim ersten Mal.
            CardTally t = cardTally(s, host);
            if (t != null) {
                t.name = host.getName();
                t.cast++;
                if (t.castTurn == null) {
                    // Der EIGENE Zug des Sitzes (wie firstCommanderTurn zwei Zeilen weiter unten),
                    // nicht Forges globale Zugnummer - siehe onLandPlayed fuer die Begruendung.
                    t.castTurn = Math.max(1, s.ownTurns);
                }
            }
            classifyCast(sa, s);
            if (host.isCommander()) {
                s.commanderCasts++;
                if (s.firstCommanderTurn == null) {
                    // Ein Commander kann per Blitz auch im gegnerischen Zug kommen; dann zaehlt der
                    // eigene Zug, in dem der Sitz zuletzt am Zug war (mindestens 1).
                    s.firstCommanderTurn = Math.max(1, s.ownTurns);
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Ein Zauber hat aufgeloest. Das Ereignis erfuellt hier zwei Aufgaben: es haelt die Ansicht in
     * {@link #justResolved} fest (damit das gleich folgende
     * {@code GameEventSpellRemovedFromStack} nicht als Konter durchgeht) und es schliesst das
     * Aufloesungsfenster fuer {@code biggestSweep} (siehe {@link #closeSweepWindow}; Verluste im
     * Kampfschadenschritt stehen gar nicht erst darin). Forge feuert es NACH den Zonenwechseln der
     * Aufloesung ({@code MagicStack.resolveStack}), die Toten einer Massenentfernung liegen also
     * noch im gerade geschlossenen Fenster.
     */
    @Subscribe
    public synchronized void onSpellResolved(GameEventSpellResolved e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            SpellAbilityView sa = e.spell();
            if (sa != null) {
                rememberResolved(sa);
                Seat s = e.hasFizzled() && sa.isSpell() ? seatOfSpell(sa) : null;
                if (s != null) {
                    s.spellsFizzled++;
                }
            }
            closeSweepWindow();
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Ein Eintrag verlaesst den Stapel. Nach einer normalen Aufloesung kam dafuer eben erst
     * {@code GameEventSpellResolved} - dann ist es kein Konter. Bleibt die Ansicht unbekannt, hat
     * der Eintrag den Stapel ohne Aufloesung verlassen: gekontert, oder von einem Effekt entfernt.
     * Forge nennt beides nicht genauer, der Datensatz auch nicht. {@code sa == null} ist
     * {@code MagicStack.clear()} (Spielende) und zaehlt niemandem.
     */
    @Subscribe
    public synchronized void onSpellRemovedFromStack(GameEventSpellRemovedFromStack e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            SpellAbilityView sa = e.sa();
            if (sa == null || forgetResolved(sa) || !sa.isSpell()) {
                return;
            }
            Seat s = seatOfSpell(sa);
            if (s != null) {
                s.spellsCountered++;
                CardView host = sa.getHostCard();
                CardTally t = host == null ? null : cardTally(s, host);
                if (t != null) {
                    t.name = host.getName();
                    t.countered++;
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /** Phasenwechsel beendet ebenfalls ein Aufloesungsfenster (ein Effekt ohne Zauber auf dem Stapel,
     *  etwa eine aktivierte Faehigkeit, schliesst sonst nie ab). */
    @Subscribe
    public synchronized void onTurnPhase(GameEventTurnPhase e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            closeSweepWindow();
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Ziehen, Abwerfen, Millen, Verluste und Spielsteine kommen alle aus demselben Ereignis: Forges
     * {@code GameEventCardDestroyed} und {@code GameEventTokenCreated} tragen keine Nutzlast, der
     * Zonenwechsel dagegen Karte, Quell- und Zielzone samt Besitzer der Zone.
     *
     * <p>Zugeordnet wird ueber den Besitzer der QUELLzone ({@code from.player()}) - wer eine Karte
     * verliert, verliert sie aus seiner Zone. Nur Spielsteine zaehlen ueber die Zielzone, sie kommen
     * aus dem Nichts. Eine Zone ohne bekannten Sitz (fremdes Spiel, Nutzlast ohne Spieler) wird
     * still uebergangen.</p>
     */
    @Subscribe
    public synchronized void onCardChangeZone(GameEventCardChangeZone e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            CardView card = e.card();
            ZoneView from = e.from(), to = e.to();
            if (card == null) {
                return;
            }
            if (to != null && to.zoneType() == ZoneType.Battlefield && card.isToken()) {
                Seat owner = seat(to.player());
                if (owner != null) {
                    owner.tokensCreated++;
                }
            }
            Seat s = from == null ? null : seat(from.player());
            if (s == null || to == null || from.zoneType() == null || to.zoneType() == null) {
                return;
            }
            boolean gone = to.zoneType() == ZoneType.Graveyard || to.zoneType() == ZoneType.Exile;
            if (from.zoneType() == ZoneType.Library && to.zoneType() == ZoneType.Hand) {
                s.cardsDrawn++;
                // Kartenbiografie (Spec §2): Starthand und jedes Nachziehen zaehlen als "hand" - nach
                // einem Mulligan zaehlt die neue Hand also erneut.
                CardTally t = cardTally(s, card);
                if (t != null) {
                    t.name = card.getName();
                    t.hand++;
                }
            } else if (from.zoneType() == ZoneType.Hand && to.zoneType() == ZoneType.Graveyard) {
                s.cardsDiscarded++;
            } else if (from.zoneType() == ZoneType.Library && gone) {
                s.cardsMilled++;
            } else if (from.zoneType() == ZoneType.Battlefield && gone) {
                s.permanentsLost++;
                boolean inCombat = inCombatDamage();
                // Ins Aufloesungsfenster zaehlt NUR, was ausserhalb des Kampfschadenschritts verloren
                // geht: drei Blocker, die in einem Kampf sterben, sind keine Massenentfernung
                // (siehe closeSweepWindow).
                if (!inCombat) {
                    s.lostInWindow++;
                }
                if (card.getCurrentState() != null && card.getCurrentState().isCreature()) {
                    if (inCombat) {
                        s.creaturesLostInCombat++;
                    } else {
                        s.creaturesLostOther++;
                    }
                }
                // Kartenbiografie (Spec §2): vom Schlachtfeld weg in Friedhof/Exil ist "lost".
                CardTally t = cardTally(s, card);
                if (t != null) {
                    t.name = card.getName();
                    t.lost++;
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Angriffe. Das Ereignis traegt den angreifenden Sitz im Kopf und die Angreifer nach Verteidiger
     * geordnet; {@code attacksDeclared} ist schlicht die Zahl der Angreifer. {@code attackedTurns}
     * zaehlt Zuege, nicht Kaempfe: eine zweite Kampfphase im selben Zug ist kein zweiter
     * Angriffszug, deshalb der Zug-Merker je Sitz. Dass der laufende Zug ueber {@link #turns} kommt
     * (und nicht ueber den Phasenhandler), haelt den Zaehler in Szenen ohne
     * {@code GameEventTurnBegan} bei mindestens 1 - ein Angriff ohne bekannten Zug ist immer noch
     * ein Angriffszug.
     */
    @Subscribe
    public synchronized void onAttackersDeclared(GameEventAttackersDeclared e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            Multimap<GameEntityView, CardView> map = e.attackersMap();
            if (map == null || map.isEmpty()) {
                return;
            }
            Seat attacker = seat(e.player());
            if (attacker != null) {
                attacker.attacksDeclared += map.size();
                int turn = Math.max(1, turns);
                if (attacker.lastAttackTurn != turn) {
                    attacker.lastAttackTurn = turn;
                    attacker.attackedTurns++;
                }
            }
            for (Map.Entry<GameEntityView, Collection<CardView>> entry : map.asMap().entrySet()) {
                Seat defender = seatOfDefender(entry.getKey());
                if (defender != null) {
                    defender.attackersFaced += entry.getValue().size();
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Bloecke. Das Ereignis kommt je verteidigendem Sitz und bildet Verteidiger =&gt; (Angreifer =&gt;
     * Blocker) ab. <b>Achtung:</b> {@code PhaseHandler} traegt einen UNGEBLOCKTEN Angreifer als
     * seinen eigenen "Blocker" ein ({@code getBlockers(att).isEmpty() ? List.of(att) : ...}) - wer
     * die Werte roh mitzaehlt, meldet Bloecke, die es nie gab. Eine Kreatur, die mehrere Angreifer
     * blockt, zaehlt einmal: die Kennzahl heisst "Kreaturen, die geblockt haben".
     */
    @Subscribe
    public synchronized void onBlockersDeclared(GameEventBlockersDeclared e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            Map<GameEntityView, Multimap<CardView, CardView>> blockers = e.blockers();
            Seat s = seat(e.defendingPlayer());
            if (s == null || blockers == null) {
                return;
            }
            Set<CardView> blocked = new HashSet<>();
            for (Multimap<CardView, CardView> perDefender : blockers.values()) {
                if (perDefender == null) {
                    continue;
                }
                for (Map.Entry<CardView, CardView> pair : perDefender.entries()) {
                    if (pair.getValue() != pair.getKey()) {     // Objektidentitaet: CardView ist je Karte eine
                        blocked.add(pair.getValue());
                    }
                }
            }
            s.blocksDeclared += blocked.size();
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Schaden am Spieler, von beiden Seiten gezaehlt: beim Ziel nach Art der Quelle, beim
     * Kontrolleur der Quelle als ausgeteilter Schaden.
     *
     * <p>Fliegen geht vor Trampelschaden - eine Quelle mit beidem zaehlt als Flieger, sonst haenge
     * die Zuordnung an der Reihenfolge der Abfrage. Ein Commander bringt keinen vierten Topf:
     * {@code commanderDamageTaken} liegt quer zu den drei Kampfschaden-Toepfen. Ohne Quelle (Forge
     * feuert das Ereignis auch mit {@code null}) bleibt Kampfschaden "other" und niemand bekommt ihn
     * gutgeschrieben - lieber eine Kennzahl zu niedrig als eine erfundene.</p>
     */
    @Subscribe
    public synchronized void onPlayerDamaged(GameEventPlayerDamaged e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            CardView src = e.source();
            int amount = e.amount();
            Seat target = seat(e.target());
            if (target != null) {
                target.damageTaken += amount;
                if (e.combat()) {
                    target.combatDamageTaken += amount;
                    if (hasKeyword(src, Keyword.FLYING)) {
                        target.damageTakenFlying += amount;
                    } else if (hasKeyword(src, Keyword.TRAMPLE)) {
                        target.damageTakenTrample += amount;
                    } else {
                        target.damageTakenOther += amount;
                    }
                    if (src != null && src.isCommander()) {
                        target.commanderDamageTaken += amount;
                    }
                } else {
                    target.damageTakenNonCombat += amount;
                }
            }
            Seat dealer = src == null ? null : seat(controllerOf(src));
            if (dealer != null) {
                dealer.damageDealt += amount;
                if (e.combat()) {
                    dealer.damageDealtCombat += amount;
                } else {
                    dealer.damageDealtNonCombat += amount;
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Lebensgewinn. Forge feuert dasselbe Ereignis fuer jede Aenderung, gezaehlt wird nur die
     * positive Differenz - der Verlust steht schon in {@code damageTaken} bzw. geht als Zahlung
     * niemanden etwas an.
     */
    @Subscribe
    public synchronized void onLivesChanged(GameEventPlayerLivesChanged e) {
        if (finished != null) {
            return;
        }
        rememberHands();
        try {
            Seat s = seat(e.player());
            if (s != null && e.newLives() > e.oldLives()) {
                s.lifeGained += e.newLives() - e.oldLives();
            }
        } catch (RuntimeException ex) {
            counterFailures++;
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

    /**
     * Wie oft ein Vorfall-Zaehler in eine {@code RuntimeException} gelaufen ist. Ein kaputter Zaehler
     * darf keine Partie abschiessen, also faengt jeder Handler und zaehlt den Fall nur hier mit. Am
     * Partieende einmalig gemeldet ueber {@code CrashLog.note}, siehe {@link #build()}.
     */
    synchronized int counterFailures() {
        return counterFailures;
    }

    /** Wie oft {@link #classifyCast} einen gewirkten Zauber nicht mehr auf dem Stapel fand. */
    synchronized int castsNotOnStack() {
        return castsNotOnStack;
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
        if (cardSink != null && cardLog != null) {
            cardSink.accept(cardLog);
        }
        return built;
    }

    /**
     * Die fertige Kartenbiografie der Partie (siehe Klassenkommentar "Kartenbiografie"), oder
     * {@code null} vor dem Abschluss - und, anders als {@link #finish()}, dauerhaft {@code null}, wenn
     * die Partie abgestuerzt, abgebrochen oder am Zugdeckel geendet ist. Fuer Aufrufer ohne eigene
     * Kartensenke (Tests) und als zweiter Weg neben {@code cardSink}.
     */
    public synchronized CardLog cardLog() {
        return cardLog;
    }

    /** @return der frisch gebaute Datensatz, oder {@code null}, wenn schon einer stand */
    private synchronized MatchRecord build() {
        if (finished != null) {
            return null;
        }
        turns = Math.max(turns, game.getPhaseHandler().getTurn());
        rememberHands();                         // laeuft die Partie noch, steht handEnd erst hier
        noteEliminations();
        closeSweepWindow();                      // das beim Spielende laufende Fenster zaehlt mit

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
        String id = newId(endedAt);
        finished = new MatchRecord(id, iso(startedAt), iso(endedAt),
                Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()), source, aiTimeout, turns,
                outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition()),
                !noWinners && (!anyWinner || (outcome != null && outcome.getWinCondition() == GameEndReason.Draw)),
                excludeReason == null, excludeReason, List.copyOf(out));
        // Kartenbiografie (Spec §2): dieselbe Ausschlussbedingung wie fuer die Bilanz eines Absturzes/
        // Abbruchs/Zugdeckels (siehe Klassenkommentar) - NICHT dieselbe wie excludeReason insgesamt.
        // Eine "zu kurze" oder aufgegebene, aber sauber beendete Partie liefert sehr wohl Kartendaten;
        // nur die drei Flags stehen fuer eine unvollstaendige Aufzeichnung.
        if (!crashed && !aborted && !turnCapped) {
            try {
                cardLog = buildCardLog(id);
            } catch (RuntimeException ex) {
                counterFailures++;
            }
        }
        try {
            reportCounterFailures();
        } catch (RuntimeException ex) {
            // Die Meldung ist die Nebensache, der Datensatz die Hauptsache. Wirft sie (kaputter
            // Log-Pfad, volle Platte), stuende finished zwar, der Sink bekaeme die Partie aber nie -
            // und ein zweiter finish()-Versuch kehrte wegen finished != null sofort um: die Partie
            // waere weg. Ueber CrashLog laesst sich das nicht melden, genau das ist ja kaputt.
            System.err.println("[MatchRecorder] Sammelmeldung fehlgeschlagen: " + ex);
        }
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

    /**
     * Einmalige Sammelmeldung am Partieende (Spec Stueck 3): die beiden Robustheits-Zaehler stehen in
     * keiner Kennzahl im Datensatz und blieben sonst unsichtbar. Nur ins Log ({@link CrashLog#note}) -
     * das geht niemanden im Browser etwas an - und nur, wenn ueberhaupt etwas zu melden ist.
     */
    private void reportCounterFailures() {
        if (counterFailures == 0 && castsNotOnStack == 0) {
            return;
        }
        CrashLog.note("MatchRecorder", counterFailures + " Ereignisse nicht gezaehlt (Ausnahme im Handler), "
                + castsNotOnStack + " gewirkte Zauber beim Counterspell-Check nicht auf dem Stapel gefunden");
    }

    /**
     * Baut die Kartenbiografie der Partie aus der Id-Zaehlung jedes aufgezeichneten Sitzes (Spec §2).
     * Ein Sitz ohne {@link Seat#byCardId} (sein Deck stand nicht in {@code ownDecks}) liefert GAR KEINE
     * {@link CardLog.SeatCards}-Zeile - anders als eine leere Kartenliste, die "eigenes Deck ohne
     * Karten" hiesse. Ein Fehler bei einem einzelnen Sitz darf die Kartendaten der anderen Sitze nicht
     * kosten, deshalb ein eigener try/catch je Sitz statt einem gemeinsamen um die ganze Methode.
     */
    private CardLog buildCardLog(String matchId) {
        List<CardLog.SeatCards> out = new ArrayList<>();
        for (int i = 0; i < seats.size(); i++) {
            Seat s = seats.get(i);
            if (s.byCardId == null) {
                continue;
            }
            try {
                resolveEndZones(s);
                List<CardLog.Card> rows = new ArrayList<>();
                for (CardTally t : s.byCardId.values()) {
                    rows.add(new CardLog.Card(t.name, 1, t.hand, t.cast, t.castTurn, t.countered, t.lost,
                            t.end == null ? "none" : t.end));
                }
                out.add(new CardLog.SeatCards(i, s.deckName(), CardLog.merge(rows)));
            } catch (RuntimeException ex) {
                counterFailures++;
            }
        }
        return new CardLog(matchId, List.copyOf(out));
    }

    /**
     * Loest am Partieende Name und Endzone jeder gezaehlten Karten-Id auf: {@code Player.getAllCards()}
     * liefert echte {@code Card}-Objekte mit wahrem Namen, auch fuer eine verdeckte Karte, deren
     * {@code CardView} waehrend der Partie den Namen versteckt hat (Spec §2, siehe Klassenkommentar
     * "Kartenbiografie"). Eine Karte, die die ganze Partie unangetastet in einer Zone lag (nie gezogen,
     * nie gewirkt) und deshalb in {@link Seat#byCardId} noch gar nicht vorkam, bekommt hier ihre erste
     * (leere) Zaehlung - genau das liefert die Zeile "nie gezogen". Spielsteine ({@code Card.isToken()})
     * fallen raus, sie stehen in keinem Deck. Eine Id, die zwar waehrend der Partie zaehlte, hier aber
     * nicht mehr auftaucht (Ersatz durch eine andere Karte, Sonderfall), behaelt den zuletzt in einem
     * Ereignis gesehenen Namen; ihre Endzone bleibt {@code null} und wird beim Bauen der Zeile
     * (siehe {@link #buildCardLog}) zu {@code "none"}.
     */
    private void resolveEndZones(Seat s) {
        for (Card c : s.player.getAllCards()) {
            if (c.isToken()) {
                continue;
            }
            CardTally t = s.byCardId.computeIfAbsent(c.getId(), id -> new CardTally());
            t.name = c.getName();
            t.end = c.getZone() == null ? null : c.getZone().getZoneType().name().toLowerCase();
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private Seat seat(PlayerView view) {
        return view == null ? null : byView.get(view);
    }

    /**
     * Der laufende Zaehler dieser Karte am aufgezeichneten Sitz, angelegt bei der ersten Beruehrung
     * (Id, nicht Name - siehe Klassenkommentar "Kartenbiografie"). {@code null} ohne Kartenbiografie an
     * diesem Sitz oder bei einem Spielstein: ein Spielstein steht in keinem Deck und darf in keiner
     * Zeile auftauchen (Spec §2), er muss also schon hier ausfallen, nicht erst in
     * {@link #resolveEndZones} - sonst bekaeme er ueber einen Zonenwechsel waehrend der Partie
     * trotzdem eine Zaehlung, die {@link #resolveEndZones} beim Ausfiltern nie zu Gesicht bekommt.
     */
    private CardTally cardTally(Seat s, CardView card) {
        if (s.byCardId == null || card == null || card.isToken()) {
            return null;
        }
        return s.byCardId.computeIfAbsent(card.getId(), id -> new CardTally());
    }

    private static PlayerView controllerOf(CardView c) {
        return c.getController() != null ? c.getController() : c.getOwner();
    }

    /**
     * Der Sitz hinter einem Verteidiger: ein Spieler steht fuer sich selbst, ein Planeswalker fuer
     * seinen Kontrolleur - ein Angriff auf meinen Planeswalker ist ein Angriff auf mich.
     *
     * <p>Eine <b>Battle</b> ist die Ausnahme: sie wird nicht von ihrem Kontrolleur verteidigt,
     * sondern von ihrem Beschuetzer (CR 310.7) - in der Regel also vom GEGNER dessen, der sie
     * kontrolliert. Forge trennt das genauso
     * ({@code Combat.getDefenderPlayerByAttacker}: {@code def.isBattle() ? def.getProtectingPlayer()
     * : def.getController()}); ohne diese Unterscheidung stuende ein Angriff auf eine Battle beim
     * falschen Sitz. Ist kein Beschuetzer gesetzt, zaehlt der Angriff niemandem - lieber eine
     * Kennzahl zu niedrig als beim falschen Sitz.</p>
     *
     * <p><b>Die Asymmetrie zu {@code blocksDeclared} ist Forges eigene und soll so bleiben:</b>
     * {@code attackersFaced} loest eine Battle hier ueber ihren Beschuetzer auf (wie
     * {@code Combat.getDefenderPlayerByAttacker}), waehrend {@code blocksDeclared} an Forges
     * {@code defendingPlayer} aus {@code GameEventBlockersDeclared} haengt - und das ist der
     * KONTROLLEUR. Wer eine der beiden Seiten "geradezieht", damit sie zueinander passen, verschiebt
     * die Kennzahl auf den falschen Sitz; der Unterschied steckt in Forge, nicht hier.</p>
     */
    private Seat seatOfDefender(GameEntityView defender) {
        if (defender instanceof PlayerView pv) {
            return seat(pv);
        }
        if (!(defender instanceof CardView cv)) {
            return null;
        }
        CardStateView state = cv.getCurrentState();
        return state != null && state.isBattle() ? seat(cv.getProtectingPlayer()) : seat(controllerOf(cv));
    }

    private static boolean hasKeyword(CardView c, Keyword kw) {
        return c != null && c.getCurrentState() != null && c.getCurrentState().hasKeyword(kw);
    }

    /**
     * Haengt den Stand zu Beginn dieses eigenen Zuges an die Zeitachse: Laender und Kreaturen im
     * Spiel, Leben und Handkarten. Ab {@link #TIMELINE_MAX} Punkten faellt jeder weitere Zug weg.
     * {@code turn} ist ab {@code MatchRecord.VERSION} 3 der eigene Zug des Sitzes (der Aufrufer
     * uebergibt {@code turnOwner.ownTurns}, denselben Zaehler wie {@code landsByTurn}), nicht mehr
     * Forges globale Zugnummer - siehe {@link MatchRecord.TurnPoint}.
     */
    private void notePoint(Seat s, int turn) {
        try {
            if (s.timeline.size() >= TIMELINE_MAX) {
                s.timelineCapped = true;
                return;
            }
            s.timeline.add(new MatchRecord.TurnPoint(Math.max(1, turn), s.player.getLandsInPlay().size(),
                    s.player.getCreaturesInPlay().size(), s.player.getLife(),
                    s.player.getCardsIn(ZoneType.Hand).size(), 0));
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Zaehlt einen gewirkten Zauber in den zuletzt angehaengten Punkt der Zeitachse. Der Punkt
     * gehoert zu dem Zug-ABSCHNITT, den er eroeffnet (siehe {@link MatchRecord.TurnPoint}): vom
     * Beginn des eigenen Zuges bis zum Beginn des naechsten eigenen Zuges, ein Blitz im Zug eines
     * Gegners zaehlt also zum vorangegangenen eigenen Zug. {@code TurnPoint} ist unveraenderlich,
     * der letzte Punkt wird deshalb ersetzt (fester Index, kein Suchen).
     *
     * <p>Zwei Faelle haben keinen Punkt, in den gezaehlt werden koennte: vor dem ersten eigenen Zug
     * und hinter dem Deckel ({@link #TIMELINE_MAX}, danach kommt kein Punkt mehr dazu). Dort faellt
     * der Zauber aus der Zeitachse, statt beim falschen Zug zu landen - sonst sammelte der letzte
     * Punkt einer langen Partie den ganzen Rest ein. In {@code Seat.spells} steht er weiter.</p>
     */
    private void notePointSpell(Seat s) {
        if (s.timelineCapped || s.timeline.isEmpty()) {
            return;
        }
        int i = s.timeline.size() - 1;
        MatchRecord.TurnPoint p = s.timeline.get(i);
        s.timeline.set(i, new MatchRecord.TurnPoint(p.turn(), p.lands(), p.creatures(), p.life(),
                p.hand(), p.spells() + 1));
    }

    /**
     * Schreibt je noch lebendem Sitz die Zahl seiner Handkarten mit - die Quelle von
     * {@code handEnd}. Spaeter nachzusehen geht nicht: {@code Game.onPlayerLost} laesst in einer
     * Mehrspieler-Partie alle Karten eines ausgeschiedenen Sitzes aufhoeren zu existieren
     * (CR 800.4a), seine Hand ist danach IMMER leer, und ein eigenes Ereignis fuer den Moment des
     * Ausscheidens feuert Forge nicht. Deshalb wird der Stand bei jedem Ereignis fortgeschrieben und
     * fuer einen Sitz mit Ausgang eingefroren: was drinsteht, ist der letzte Blick vor seinem Ende.
     * Billig genug fuer jedes Ereignis - {@code Player.getCardsIn} gibt bei einer Zone abseits des
     * Schlachtfelds die Liste selbst zurueck, {@code size()} ist dann O(1).
     */
    private void rememberHands() {
        try {
            for (Seat s : seats) {
                if (s.player.getOutcome() == null) {
                    s.handEnd = s.player.getCardsIn(ZoneType.Hand).size();
                }
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Einmal je Partie, beim ersten gesehenen {@code GameEventTurnBegan}: die Laender in der Hand,
     * die jeder Sitz nach allen Mulligans behalten hat ({@code openingLands}, Grundlage fuer
     * "behaelt Zwei-Land-Haende"). Genau dieser Zeitpunkt, weil davor noch gemulligant und die Hand
     * neu gezogen wird und gleich danach der erste Zug zieht - einen Punkt spaeter waere es nicht
     * mehr die Eroeffnungshand.
     */
    private void noteOpeningHands() {
        if (openingTaken) {
            return;
        }
        openingTaken = true;
        try {
            for (Seat s : seats) {
                int lands = 0;
                for (Card c : s.player.getCardsIn(ZoneType.Hand)) {
                    if (c.isLand()) {
                        lands++;
                    }
                }
                s.openingLands = lands;
            }
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /** Der Sitz, der diesen Zauber gewirkt hat: Beherrscher seiner Ursprungskarte. */
    private Seat seatOfSpell(SpellAbilityView sa) {
        CardView host = sa.getHostCard();
        return host == null ? null : seat(controllerOf(host));
    }

    /**
     * Ordnet einen gewirkten Zauber ein - Konter und Entfernung in EINEM Gang durch seine
     * Faehigkeitskette. Die Kette gibt es nur am Spielobjekt ({@code SpellAbilityView} kennt weder
     * API noch Unterfaehigkeiten), deshalb wird der Zauber auf dem Stapel nachgeschlagen. Das geht
     * auf, weil {@code MagicStack.add} den Eintrag legt, BEVOR es
     * {@code GameEventSpellAbilityCast} feuert. Ein Zauber, der dort nicht (mehr) liegt, zaehlt in
     * keiner der beiden Kennzahlen mit - lieber eine Kennzahl zu niedrig als eine erfundene.
     *
     * <p><b>Nachtrag aus der Review von Stueck 1.</b> Bleibt die Suche erfolglos, zaehlt das getrennt
     * in {@link #castsNotOnStack} statt still nichts zu tun: aendert Forge einmal die Reihenfolge
     * (Stack-Eintrag erst nach dem Ereignis), liessen {@code counterspellsCast} und
     * {@code removalCast} sonst fuer immer unauffaellig 0, ohne dass es irgendwo auffiele. Eine
     * echte {@code RuntimeException} beim Nachschlagen ist davon zu unterscheiden - die zaehlt
     * weiter in {@link #counterFailures}.</p>
     *
     * <p><b>{@code removalCast} zaehlt die Absicht, nicht den Erfolg.</b> Gezaehlt wird, dass der
     * Sitz einen Zauber gewirkt hat, der eine bleibende Karte loswerden will (siehe
     * {@link #isRemoval}) - ob er aufloest, gekontert wird, verpufft oder der Blitz am Ende ins
     * Gesicht geht, steht hier nicht drin. Das ist gewollt: gefragt ist "wie viel Interaktion hat
     * dieses Deck gewirkt", und die Frage nach dem Erfolg beantwortet Forge ohnehin nicht
     * (welcher Zauber welche Kreatur zerstoert hat, liefert keine Nutzlast). Die Massenvarianten
     * ({@code DestroyAll}, {@code ChangeZoneAll}, {@code DamageAll}) zaehlen NICHT mit - eine
     * Massenentfernung schlaegt sich beim Gegner in {@code sweepsSuffered} nieder.</p>
     */
    private void classifyCast(SpellAbilityView view, Seat s) {
        try {
            for (SpellAbilityStackInstance si : game.getStack()) {
                SpellAbility sa = si.getSpellAbility();
                if (sa == null || sa.getView() != view) {
                    continue;
                }
                boolean counter = false;
                boolean removal = false;
                for (SpellAbility part = sa; part != null; part = part.getSubAbility()) {
                    counter |= part.getApi() == ApiType.Counter;
                    removal |= isRemoval(part);
                }
                if (counter) {
                    s.counterspellsCast++;
                }
                if (removal) {
                    s.removalCast++;
                }
                return;
            }
            castsNotOnStack++;
        } catch (RuntimeException ex) {
            counterFailures++;
        }
    }

    /**
     * Will diese Teilfaehigkeit eine bleibende Karte loswerden? Die Naeherung aus dem Nachtrag zu
     * Runde B, siehe {@link #classifyCast}:
     * <ul>
     *   <li>{@code Destroy} - trifft per Definition eine bleibende Karte.</li>
     *   <li>{@code DealDamage} nur, wenn das Ziel eine bleibende Karte sein KANN: ein Blitz mit
     *       {@code ValidTgts$ Player} ist Reichweite, keine Entfernung. {@code Any} zaehlt mit, denn
     *       genau dafuer spielt man ihn.</li>
     *   <li>{@code ChangeZone} nur VOM Schlachtfeld weg (Rueckhand, Exil). Dieselbe API holt sonst
     *       aus der Bibliothek auf die Hand (Tutor) oder aus dem Friedhof zurueck - das ist keine
     *       Entfernung.</li>
     * </ul>
     */
    private static boolean isRemoval(SpellAbility part) {
        ApiType api = part.getApi();
        if (api == ApiType.Destroy) {
            return true;
        }
        if (api == ApiType.DealDamage) {
            return hitsPermanent(part.getParam("ValidTgts")) || hitsPermanent(part.getParam("Defined"));
        }
        return api == ApiType.ChangeZone && contains(part.getParam("Origin"), "Battlefield");
    }

    /** Nennt diese Ziel- bzw. Auswahlbedingung etwas, das auf dem Schlachtfeld stehen kann? */
    private static boolean hitsPermanent(String valid) {
        for (String typ : new String[]{"Any", "Permanent", "Creature", "Planeswalker", "Battle",
                "Artifact", "Enchantment"}) {
            if (contains(valid, typ)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String param, String needle) {
        return param != null && param.contains(needle);
    }

    private void rememberResolved(SpellAbilityView sa) {
        justResolved.addLast(sa);
        while (justResolved.size() > RESOLVED_MEMORY) {
            justResolved.removeFirst();
        }
    }

    /** @return {@code true}, wenn dieser Zauber eben aufgeloest hat (dann ist er nicht gekontert) */
    private boolean forgetResolved(SpellAbilityView sa) {
        for (var it = justResolved.iterator(); it.hasNext(); ) {
            if (it.next() == sa) {                // Objektidentitaet, siehe justResolved
                it.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Schliesst das laufende Aufloesungsfenster: was ein Sitz darin an bleibenden Karten verloren
     * hat, gilt als EIN Vorgang. Das ist die Naeherung aus Spec Paragraph 2 - zustandsbasierte
     * Aktionen aus mehreren Quellen landen im selben Fenster -, aber sie kommt ohne Eingriff in
     * Forge aus.
     *
     * <p>Im Fenster stehen nur Verluste AUSSERHALB des Kampfschadenschritts (siehe
     * {@link #inCombatDamage} und die Zaehlung in {@code onCardChangeZone}). Das Fenster endet
     * zwar auch an jedem Phasenwechsel, ein ganzer Kampf liegt aber in EINER Phase: ohne diese
     * Bedingung waeren drei Blocker, die in einem Kampf sterben, eine "Massenentfernung" - und
     * das Statistik-Board empfaehle daraufhin Schutz gegen Brettfeger, wo schlicht schlecht
     * geblockt wurde.</p>
     */
    private void closeSweepWindow() {
        for (Seat s : seats) {
            if (s.lostInWindow <= 0) {
                continue;
            }
            s.biggestSweep = Math.max(s.biggestSweep, s.lostInWindow);
            if (s.lostInWindow >= SWEEP_MIN) {
                s.sweepsSuffered++;
            }
            s.lostInWindow = 0;
        }
    }

    /** Kampfschadenschritt (auch Erstschlag) - daran haengt "im Kampf verloren". */
    private boolean inCombatDamage() {
        PhaseType phase = game.getPhaseHandler().getPhase();
        return phase == PhaseType.COMBAT_DAMAGE || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE;
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
        /** Siehe {@link MatchRecorder#deckNames}; hier nur durchgereicht fuer {@link #deckName()}. */
        private final Map<RegisteredPlayer, String> deckNames;
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
        private int spellsCountered;
        private int spellsFizzled;
        private int counterspellsCast;
        private int cardsDrawn;
        private int cardsDiscarded;
        private int cardsMilled;
        private int permanentsLost;
        private int creaturesLostInCombat;
        private int creaturesLostOther;
        private int biggestSweep;
        private int sweepsSuffered;
        private int tokensCreated;
        /** Verluste im laufenden Aufloesungsfenster, ohne die im Kampfschadenschritt; siehe
         *  {@link MatchRecorder#closeSweepWindow}. */
        private int lostInWindow;
        private int attacksDeclared;
        private int attackedTurns;
        /** Zug des letzten eigenen Angriffs; haelt {@link #attackedTurns} bei zwei Kaempfen je Zug. */
        private int lastAttackTurn;
        private int attackersFaced;
        private int blocksDeclared;
        private int damageTakenFlying;
        private int damageTakenTrample;
        private int damageTakenOther;
        private int damageTakenNonCombat;
        private int damageDealtCombat;
        private int damageDealtNonCombat;
        private int commanderDamageTaken;
        private int lifeGained;
        private int removalCast;
        /** Handkarten beim letzten Blick vor dem Ausscheiden; siehe {@link MatchRecorder#rememberHands}. */
        private int handEnd;
        /** Laender der behaltenen Eroeffnungshand; siehe {@link MatchRecorder#noteOpeningHands}. */
        private int openingLands;
        private final List<MatchRecord.TurnPoint> timeline = new ArrayList<>();
        /** Ab hier kommt kein Punkt mehr dazu; siehe {@link MatchRecorder#notePointSpell}. */
        private boolean timelineCapped;
        private int damageDealt;
        private int damageTaken;
        private int combatDamageTaken;
        private Integer eliminatedTurn;
        /** Kartenbiografie je Karten-Id, nur angelegt, wenn dieser Sitz in {@code ownDecks} steht -
         *  {@code null} ist das Zeichen "dieser Sitz wird nicht aufgezeichnet"; siehe
         *  {@link MatchRecorder#cardTally} und die Klassenkommentar-Abschnitt "Kartenbiografie". */
        private final Map<Integer, CardTally> byCardId;

        private Seat(Player player, Map<RegisteredPlayer, String> deckNames, Set<String> ownDecks) {
            this.player = player;
            this.deckNames = deckNames;
            // deckName() kann theoretisch null liefern (kein RegisteredPlayer) - Set.of(...) wirft bei
            // contains(null) eine NullPointerException, deshalb der explizite Vorbehalt statt direkt
            // ownDecks.contains(deckName()).
            String name = deckName();
            this.byCardId = name != null && ownDecks.contains(name) ? new HashMap<>() : null;
        }

        /** @param noWinners Abbruch/Absturz: kein Sitz gilt als Sieger (siehe {@link MatchRecorder#build}) */
        private MatchRecord.Seat toRecord(int turns, boolean noWinners) {
            PlayerOutcome o = player.getOutcome();
            boolean winner = !noWinners && o != null && o.hasWon();
            String lossReason = o == null || o.lossState == null ? null : o.lossState.name();
            Integer eliminated = lossReason == null ? null : eliminatedTurn != null ? eliminatedTurn : Math.max(1, turns);
            return new MatchRecord.Seat(player.getName(), deckName(), human(), ai(), winner, lossReason,
                    eliminated, mulligans, lands, List.copyOf(landsByTurn), missedLandDrops,
                    firstMissedLandDrop, spells, spellMana,
                    spellsCountered, spellsFizzled, counterspellsCast,
                    cardsDrawn, cardsDiscarded, cardsMilled,
                    permanentsLost, creaturesLostInCombat, creaturesLostOther,
                    biggestSweep, sweepsSuffered, tokensCreated,
                    attacksDeclared, attackedTurns, attackersFaced, blocksDeclared,
                    damageTakenFlying, damageTakenTrample, damageTakenOther, damageTakenNonCombat,
                    damageDealtCombat, damageDealtNonCombat, commanderDamageTaken, lifeGained,
                    List.copyOf(timeline),
                    commanderCasts, commanderTax(),
                    firstCommanderTurn, damageDealt, damageTaken, combatDamageTaken,
                    player.getLife(), player.getPoisonCounters(),
                    handEnd, openingLands, removalCast);
        }

        /**
         * Der Name, unter dem die Partie gestartet wurde (Lobby-Auswahl) - danach gruppiert die
         * Statistik. Zuerst der vom Aufrufer gegebene Name ({@link #deckNames}, siehe dort): Forges
         * eigener Weg ({@code RegisteredPlayer.getDeck()}) liefert eine Kopie, deren Name Schraegstriche
         * schon zu Unterstrichen saniert hat. Nur wenn der Aufrufer nichts mitgegeben hat (aeltere
         * Aufrufer, Tests), gilt Forges Name als Rueckfall - besser ein sanitierter Name als gar keiner.
         */
        private String deckName() {
            RegisteredPlayer rp = player.getRegisteredPlayer();
            String given = rp == null ? null : deckNames.get(rp);
            if (given != null) {
                return given;
            }
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

    /**
     * Laufende Zaehlung einer einzelnen Karte (per Id, siehe {@link Seat#byCardId}) waehrend der
     * Partie; wird in {@link #buildCardLog} in eine {@link CardLog.Card}-Zeile umgegossen.
     * {@code name} wird bei jeder Beruehrung neu gesetzt (zuletzt gesehener Name; bei einer verdeckten
     * Karte womoeglich nicht der wahre - siehe {@link #resolveEndZones}, das ihn am Ende korrigiert).
     * {@code end} bleibt {@code null}, bis {@link #resolveEndZones} sie aufloest.
     */
    private static final class CardTally {
        String name;
        int hand;
        int cast;
        Integer castTurn;
        int countered;
        int lost;
        String end;
    }
}
