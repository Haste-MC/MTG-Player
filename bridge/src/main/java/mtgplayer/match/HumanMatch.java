package mtgplayer.match;

import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.interfaces.IGuiGame;
import forge.player.LobbyPlayerHuman;
import mtgplayer.gui.WebGuiGame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ein Commander-Spiel mit genau einem menschlichen Sitz (Browser) und 1–5 KIs über Forges
 * HostedMatch. startMatch kehrt sofort zurück; das Spiel läuft auf Forges Game-Thread und
 * spricht über die WebGuiGame.
 */
public final class HumanMatch {

    private HostedMatch hosted;

    public void start(String humanName, Deck humanDeck, List<Deck> aiDecks, List<String> aiNames, WebGuiGame gui) {
        if (aiDecks.size() != aiNames.size() || aiDecks.isEmpty() || aiDecks.size() > 5) {
            throw new IllegalArgumentException("1–5 KI-Decks mit gleich vielen Namen");
        }
        end();
        List<RegisteredPlayer> players = new ArrayList<>();
        RegisteredPlayer human = RegisteredPlayer.forCommander(humanDeck);
        human.setPlayer(new LobbyPlayerHuman(humanName));
        players.add(human);
        for (int i = 0; i < aiDecks.size(); i++) {
            RegisteredPlayer rp = RegisteredPlayer.forCommander(aiDecks.get(i));
            rp.setPlayer(new LobbyPlayerAi(aiNames.get(i), null));
            players.add(rp);
        }
        Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        guis.put(human, gui);

        GameRules rules = CommanderRules.create();
        // Forge zeigt sonst beim Start eine Liste von Karten, die die KI nicht spielen kann – Rauschen für den menschlichen Sitz
        rules.setWarnAboutAICards(false);
        hosted = new HostedMatch();
        gui.resetForNewMatch(); // sonst haengt Auswahl/Prompt-Zustand aus dem vorigen Spiel noch dran
        hosted.startMatch(rules, null, players, guis, null);
    }

    public boolean isRunning() {
        return hosted != null && hosted.getGame() != null && !hosted.getGame().isGameOver();
    }

    public void end() {
        if (hosted != null) {
            hosted.endCurrentGame();
            hosted = null;
        }
    }
}
