package mtgplayer.match;

import com.google.common.eventbus.Subscribe;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventTurnEnded;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Observer;
import java.util.function.Consumer;

/**
 * Ein Commander-Spiel nur mit KI-Spielern, synchron auf dem aufrufenden Thread.
 * Benutzt Match/Game aus forge-game direkt – HostedMatch braucht eine IGuiGame
 * und kommt erst mit dem menschlichen Sitz.
 */
public final class AiMatch {

    public record Result(String winner, String reason, int turns) { }

    private AiMatch() { }

    /**
     * @param decks    ein Deck pro Sitz, 2–6
     * @param names    Anzeigenamen, gleiche Länge wie decks
     * @param maxTurns Spiel endet nach maxTurns Spieler-Zügen als Unentschieden (Forge zählt jeden
     *                 Spieler-Zug einzeln, nicht pro Runde; KI-Spiele können sich festfahren)
     * @param log      bekommt jede Forge-Logzeile, sobald sie entsteht
     */
    @SuppressWarnings("deprecation")
    public static Result play(List<Deck> decks, List<String> names, int maxTurns, Consumer<String> log) {
        if (decks.size() != names.size() || decks.size() < 2 || decks.size() > 6) {
            throw new IllegalArgumentException("2–6 Decks mit gleich vielen Namen");
        }
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(decks.get(i));
            rp.setPlayer(new LobbyPlayerAi(names.get(i), null));
            players.add(rp);
        }

        GameRules rules = new GameRules(GameType.Commander);
        rules.setGamesPerMatch(1);
        Match match = new Match(rules, players, "AI Commander");
        Game game = match.createGame();

        int[] seen = {0};
        Observer observer = (o, arg) -> {
            List<GameLogEntry> all = game.getGameLog().getAllEntries();
            for (; seen[0] < all.size(); seen[0]++) {
                log.accept(all.get(seen[0]).message());
            }
        };
        game.getGameLog().addObserver(observer);

        game.subscribeToEvents(new Object() {
            @Subscribe
            public void onTurnEnded(GameEventTurnEnded e) {
                if (!game.isGameOver() && game.getPhaseHandler().getTurn() >= maxTurns) {
                    log.accept("[bridge] turn-cap " + maxTurns + " erreicht, breche ab");
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
        // Spieler vorher auf Draw setzen, sonst markiert Player.onGameOver() alle als Gewinner.
        String winner = outcome == null || outcome.getWinCondition() == GameEndReason.Draw || outcome.getWinningPlayer() == null
                ? null : outcome.getWinningPlayer().getPlayer().getName();
        String reason = outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition());
        int turns = outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber();
        return new Result(winner, reason, turns);
    }
}
