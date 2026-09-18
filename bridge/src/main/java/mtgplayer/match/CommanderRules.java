package mtgplayer.match;

import forge.game.GameRules;
import forge.game.GameType;

/**
 * Regeln für ein Commander-Spiel. {@code new GameRules(GameType.Commander)} allein reicht nicht:
 * Forge prüft die Commander-Sonderregeln über {@link GameRules#hasAppliedVariant} (Forges Lobby
 * übergibt die Varianten an {@code HostedMatch.startMatch}). Ohne die angewandte Variante läuft
 * u. a. die State-Based Action CR 903.9a nicht (Commander aus Friedhof/Exil in die Kommandozone,
 * {@code GameAction#stateBasedAction_Commander}) und Commander-Schaden (21) zählt nicht.
 */
public final class CommanderRules {

    private CommanderRules() {}

    public static GameRules create() {
        GameRules rules = new GameRules(GameType.Commander);
        rules.addAppliedVariant(GameType.Commander);
        rules.setGamesPerMatch(1);
        return rules;
    }
}
