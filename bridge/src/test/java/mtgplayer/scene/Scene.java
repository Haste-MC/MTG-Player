package mtgplayer.scene;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.IPaperCard;
import forge.item.PaperToken;
import forge.model.FModel;
import mtgplayer.ai.AiConfig;
import mtgplayer.match.CommanderRules;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Praeparierte Spielstellung fuer Mechanik-Tests, nach dem Muster von Forges {@code AITest}
 *  (forge-gui-desktop; dort nur mit Display lauffaehig). Leere Decks, Commander-Regeln
 *  ({@link CommanderRules#create()}), {@code GameStage.Play}, Start in Main 1 des ersten Spielers.
 *  Karten werden direkt in Zonen gelegt ({@link #card}), Phasen per {@link #setPhase} gesetzt und
 *  mit {@link #loopUntil}/{@link #step} ueber {@code PhaseHandler.mainLoopStep} getrieben - die
 *  KI-Sitze entscheiden dabei wie im echten Spiel. Braucht {@code ForgeBoot.init()}.
 *
 *  <p>Achtung: leere Bibliothek = Verlust beim ersten Ziehen (SBA). Szenen, die ueber einen
 *  Zugwechsel laufen, fuellen die Bibliothek vorher ({@link #cards} mit {@code ZoneType.Library}). */
public final class Scene {
    /** Deckel fuer {@link #loopUntil}: mehr Schritte als ein Zug je braucht, aber endlich. */
    public static final int MAX_STEPS = 500;
    private static final String[] NAMES = {"A", "B", "C", "D"};

    private final Game game;

    private Scene(Game game) {
        this.game = game;
    }

    public static Scene twoPlayers(AiConfig a, AiConfig b) {
        return twoPlayers(a, b, 3);
    }

    public static Scene twoPlayers(AiConfig a, AiConfig b, int aiTimeout) {
        return of(List.of(a, b), aiTimeout);
    }

    public static Scene threePlayers(AiConfig a, AiConfig b, AiConfig c) {
        return of(List.of(a, b, c), 3);
    }

    public static Scene fourPlayers(AiConfig a, AiConfig b, AiConfig c, AiConfig d) {
        return of(List.of(a, b, c, d), 3);
    }

    /** Beliebig viele Sitze (max. {@code NAMES.length}) mit gemeinsamer KI-Bedenkzeit. */
    public static Scene of(List<AiConfig> configs, int aiTimeout) {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            players.add(new RegisteredPlayer(new Deck()).setPlayer(configs.get(i).newLobbyPlayer(NAMES[i])));
        }
        GameRules rules = CommanderRules.create();
        Match match = new Match(rules, players, "Scene");
        Game game = new Game(players, rules, match);
        game.AI_TIMEOUT = aiTimeout;
        game.setAge(GameStage.Play);
        Scene s = new Scene(game);
        s.setPhase(PhaseType.MAIN1, game.getPlayers().get(0));
        return s;
    }

    public Game game() {
        return game;
    }

    /** Spieler nach Sitz-Index - ueber die registrierten Spieler, damit der Index nach einem Ausscheiden nicht verrutscht. */
    public Player player(int i) {
        return game.getRegisteredPlayers().get(i);
    }

    /** Karte per {@code Card.fromPaperCard} anlegen (neuer Timestamp, sonst kollidieren statische
     *  Faehigkeiten) und in die Zone legen. Bleibende Karten im Spiel kommen ohne Einsatzverzoegerung. */
    public Card card(String name, Player owner, ZoneType zone) {
        return card(name, owner, zone, false);
    }

    public Card card(String name, Player owner, ZoneType zone, boolean sick) {
        IPaperCard paper = FModel.getMagicDb().getCommonCards().getCard(name);
        if (paper == null) {
            StaticData.instance().attemptToLoadCard(name);
            paper = FModel.getMagicDb().getCommonCards().getCard(name);
        }
        if (paper == null) {
            throw new IllegalArgumentException("Karte nicht gefunden: " + name);
        }
        return place(Card.fromPaperCard(paper, owner), owner, zone, sick);
    }

    public List<Card> cards(String name, int n, Player owner, ZoneType zone) {
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(card(name, owner, zone));
        }
        return out;
    }

    /** Spielstein aus {@code res/tokenscripts/<script>.txt} (z. B. {@code c_a_treasure_sac}). */
    public Card token(String script, Player owner, ZoneType zone) {
        PaperToken paper = FModel.getMagicDb().getAllTokens().getToken(script);
        if (paper == null) {
            throw new IllegalArgumentException("Tokenskript nicht gefunden: " + script);
        }
        return place(CardFactory.getCard(paper, owner, game), owner, zone, false);
    }

    private Card place(Card c, Player owner, ZoneType zone, boolean sick) {
        c.setGameTimestamp(game.getNextTimestamp());
        owner.getZone(zone).add(c);
        if (zone == ZoneType.Battlefield) {
            c.setSickness(sick);
        }
        return c;
    }

    /** Phase und aktiven Spieler direkt setzen ({@code devModeSet}, Zugnummer bleibt 1) und dem
     *  Spieler Prioritaet geben ({@code onStackResolved}), damit der naechste Schritt entscheidet. */
    public void setPhase(PhaseType phase, Player active) {
        game.getPhaseHandler().devModeSet(phase, active);
        game.getPhaseHandler().onStackResolved();
    }

    /** {@code mainLoopStep}, bis Phase und aktiver Spieler erreicht sind; wirft {@link AssertionError},
     *  wenn das Spiel vorher endet oder {@link #MAX_STEPS} ueberschritten wird. Steht die Szene bereits in
     *  der Zielphase, laeuft sie mindestens einen Schritt (sonst waere der Aufruf ein Nichts). */
    public void loopUntil(PhaseType phase, Player active) {
        for (int i = 0; i < MAX_STEPS; i++) {
            if (game.isGameOver()) {
                throw new AssertionError("Spiel vorbei vor " + phase + " von " + active + ": " + game.getOutcome()
                        + "\n" + log(20));
            }
            game.getPhaseHandler().mainLoopStep();
            if (game.getPhaseHandler().is(phase) && game.getPhaseHandler().getPlayerTurn().equals(active)) {
                return;
            }
        }
        throw new AssertionError(MAX_STEPS + " Schritte ohne " + phase + " von " + active + " (jetzt: "
                + game.getPhaseHandler().getPhase() + " von " + game.getPhaseHandler().getPlayerTurn() + ")");
    }

    public void step(int n) {
        for (int i = 0; i < n && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
        }
    }

    public boolean has(Player p, ZoneType zone, String name) {
        return count(p, zone, name) > 0;
    }

    public int count(Player p, ZoneType zone, String name) {
        int n = 0;
        for (Card c : p.getCardsIn(zone)) {
            if (c.getName().equals(name)) {
                n++;
            }
        }
        return n;
    }

    /** Die letzten n Zeilen des Forge-Spielprotokolls (aelteste zuerst) - fuer Assertions und Reports. */
    public String log(int n) {
        List<GameLogEntry> all = game.getGameLog().getAllEntries();
        List<String> lines = all.stream().map(GameLogEntry::message).collect(Collectors.toList());
        int from = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /** Alle Zonen aller Spieler als Text (fuer Fehlermeldungen). */
    public String state() {
        StringBuilder sb = new StringBuilder();
        for (Player p : game.getPlayers()) {
            for (ZoneType z : ZoneType.values()) {
                if (p.getCardsIn(z).isEmpty()) {
                    continue;
                }
                sb.append(p.getName()).append(' ').append(z).append(": ");
                sb.append(p.getCardsIn(z).stream().map(c -> c.getName() + (c.isTapped() ? "(T)" : ""))
                        .collect(Collectors.joining(", "))).append('\n');
            }
        }
        return sb.toString();
    }
}
