package mtgplayer.match;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
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
     * @param maxTurns Spiel wird bei Überschreiten als Unentschieden beendet (KI-Spiele können sich festfahren)
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

        Thread watchdog = new Thread(() -> {
            try {
                while (!game.isGameOver()) {
                    // 50ms statt 1000ms: getTurn() zaehlt jeden Spieler-Zug einzeln (PhaseHandler.turn++
                    // bei jedem Zugwechsel), bei 4 Spielern mit duennem Board schaffen KIs mehrere Zuege
                    // pro Sekunde. Mit 1000ms-Poll wurde der turn-cap im Test (60) reproduzierbar
                    // ueberschritten (beobachtet: 62). 50ms haelt den Cap zuverlaessig ein.
                    Thread.sleep(50);
                    if (game.getPhaseHandler().getTurn() > maxTurns) {
                        log.accept("[bridge] turn-cap " + maxTurns + " erreicht, breche ab");
                        game.setGameOver(GameEndReason.Draw);
                    }
                }
            } catch (InterruptedException ignored) {
                // Spiel ist fertig
            }
        }, "ai-match-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            match.startGame(game);
        } finally {
            watchdog.interrupt();
            game.getGameLog().deleteObserver(observer);
        }

        GameOutcome outcome = game.getOutcome();
        String winner = outcome == null || outcome.isDraw() || outcome.getWinningPlayer() == null
                ? null : outcome.getWinningPlayer().getPlayer().getName();
        String reason = outcome == null ? "unbekannt" : String.valueOf(outcome.getWinCondition());
        int turns = outcome == null ? game.getPhaseHandler().getTurn() : outcome.getLastTurnNumber();
        return new Result(winner, reason, turns);
    }
}
