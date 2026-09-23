package mtgplayer.match;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.Match;
import forge.game.event.GameEventTurnEnded;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;
import mtgplayer.stats.MatchRecord;
import mtgplayer.stats.MatchRecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Observer;
import java.util.function.Consumer;

/**
 * Ein Commander-Spiel nur mit KI-Spielern, synchron auf dem aufrufenden Thread.
 * Benutzt Match/Game aus forge-game direkt – HostedMatch braucht eine IGuiGame
 * und kommt erst mit dem menschlichen Sitz.
 */
public final class AiMatch {

    public record Result(String winner, String reason, int turns, boolean turnCapped) { }

    private AiMatch() { }

    /**
     * @param decks    ein Deck pro Sitz, 2–6
     * @param names    Anzeigenamen, gleiche Länge wie decks
     * @param maxTurns Spiel endet nach maxTurns Spieler-Zügen als Unentschieden (Forge zählt jeden
     *                 Spieler-Zug einzeln, nicht pro Runde; KI-Spiele können sich festfahren)
     * @param log      bekommt jede Forge-Logzeile, sobald sie entsteht
     */
    public static Result play(List<Deck> decks, List<String> names, int maxTurns, Consumer<String> log) {
        return play(decks, names, Collections.nCopies(decks.size(), AiConfig.DEFAULT), AiConfig.DEFAULT_TIMEOUT, maxTurns, log);
    }

    /**
     * @param decks    ein Deck pro Sitz, 2–6
     * @param names    Anzeigenamen, gleiche Länge wie decks
     * @param configs  KI-Modus/-Profil je Sitz, gleiche Länge wie decks
     * @param aiTimeout Bedenkzeit je KI-Entscheidung in Sekunden ({@link Game#AI_TIMEOUT})
     * @param maxTurns Spiel endet nach maxTurns Spieler-Zügen als Unentschieden (Forge zählt jeden
     *                 Spieler-Zug einzeln, nicht pro Runde; KI-Spiele können sich festfahren)
     * @param log      bekommt jede Forge-Logzeile, sobald sie entsteht
     */
    public static Result play(List<Deck> decks, List<String> names, List<AiConfig> configs, int aiTimeout,
                               int maxTurns, Consumer<String> log) {
        return play(decks, names, configs, aiTimeout, maxTurns, log, null);
    }

    /**
     * @param sink bekommt den {@link MatchRecord} der Partie, sobald sie vorbei ist - Quelle
     *             {@code "sparring"}, weil KI-gegen-KI ueber diesen Weg das Sparring aus Stueck 3 ist.
     *             {@code null} = nicht erfassen (Bench-Laeufe, Tests).
     */
    @SuppressWarnings("deprecation")
    public static Result play(List<Deck> decks, List<String> names, List<AiConfig> configs, int aiTimeout,
                               int maxTurns, Consumer<String> log, Consumer<MatchRecord> sink) {
        if (maxTurns <= 0) {
            throw new IllegalArgumentException("maxTurns muss > 0 sein");
        }
        if (decks.size() != names.size() || decks.size() != configs.size() || decks.size() < 2 || decks.size() > 6) {
            throw new IllegalArgumentException("2–6 Decks mit gleich vielen Namen");
        }
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(decks.get(i));
            rp.setPlayer(configs.get(i).newLobbyPlayer(names.get(i)));
            players.add(rp);
        }

        GameRules rules = CommanderRules.create();
        Match match = new Match(rules, players, "AI Commander");
        Game game = match.createGame();
        game.AI_TIMEOUT = aiTimeout;

        int[] seen = {0};
        Observer observer = (o, arg) -> {
            List<GameLogEntry> all = game.getGameLog().getAllEntries();
            for (; seen[0] < all.size(); seen[0]++) {
                log.accept(all.get(seen[0]).message());
            }
        };
        game.getGameLog().addObserver(observer);
        // Lokale Variable statt anonymer Anmeldung: der Zugdeckel-Haken unten muss den Recorder erreichen.
        // Vor match.startGame(...), damit Mulligans und der erste Zug schon mitgezaehlt werden; der
        // Recorder schliesst sich selbst ueber GameEventGameFinished ab.
        final MatchRecorder recorder = sink == null ? null : new MatchRecorder(game, "sparring", aiTimeout, sink);

        // Forge selbst kann ein Spiel ebenfalls mit GameEndReason.Draw beenden (gleichzeitiger Verlust,
        // Stack > 999, GameDrawEffect) - dieses Flag markiert nur ein Unentschieden DURCH UNS (Zugdeckel),
        // damit BenchStats.summarize() es nicht mit einem Forge-eigenen Unentschieden verwechselt.
        boolean[] turnCapped = {false};
        game.subscribeToEvents(new Object() {
            @Subscribe
            public void onTurnEnded(GameEventTurnEnded e) {
                if (!game.isGameOver() && game.getPhaseHandler().getTurn() >= maxTurns) {
                    log.accept("[bridge] turn-cap " + maxTurns + " erreicht, breche ab");
                    turnCapped[0] = true;
                    if (recorder != null) {
                        // Abgeschnitten statt ausgespielt: die Partie zaehlt nicht in die Statistik.
                        recorder.markTurnCapped();
                    }
                    // Spieler vorher auf Draw setzen, sonst markiert Player.onGameOver() alle als Gewinner.
                    for (Player p : game.getPlayers()) {
                        p.intentionalDraw();
                    }
                    game.setGameOver(GameEndReason.Draw);
                }
            }
        });

        try {
            match.startGame(game);
        } finally {
            game.getGameLog().deleteObserver(observer);
        }

        GameOutcome outcome = game.getOutcome();
        String winner = outcome == null || outcome.getWinCondition() == GameEndReason.Draw || outcome.getWinningPlayer() == null
                ? null : outcome.getWinningPlayer().getPlayer().getName();
        String reason = outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition());
        int turns = outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber();
        return new Result(winner, reason, turns, turnCapped[0]);
    }
}
