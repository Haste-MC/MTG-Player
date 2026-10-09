package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.player.Player;
import forge.gui.GuiBase;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.forge.WebGuiBase;
import mtgplayer.gui.WebGuiGame;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Eigene Klasse, damit {@code ForgeBoot} die drei schnellen Faelle in {@link HumanMatchTeamsTest}
 * nicht ausbremst.
 *
 * <p>Warum dieser Test existiert: {@code HumanMatchTeamsTest} ruft {@code applyTeams} direkt auf und
 * bleibt gruen, auch wenn der Aufruf in {@code start}/{@code startSpectator} geloescht wird - die
 * Teams waeren dann still tot. Hier kommen die Nummern an der Engine an einer wirklich ueber
 * {@code HumanMatch} gestarteten Partie an (Zuschauer-Partie, damit keine GUI-Interaktion noetig ist;
 * Aufbau wie in {@code SpectatorEndTest}).
 */
class HumanMatchStartTeamsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void einGestartetesSpielTraegtDieTeamsInSitzreihenfolge() throws Exception {
        List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"),
                Precons.load("Adaptive Enchantment [C18] [2018]"));
        WebGuiGame gui = new WebGuiGame(m -> { });
        ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(() -> gui);
        HumanMatch match = new HumanMatch();
        try {
            match.startSpectator(decks, List.of("KI 1", "KI 2"), Collections.nCopies(2, AiConfig.DEFAULT),
                    List.of(1, 2), 3, gui, null, java.util.Set.of(), null);
            Game game = warteAufSpiel(match);

            // getRegisteredPlayers(): alle Sitze in Sitzreihenfolge, auch ausgeschiedene
            List<Player> sitze = List.copyOf(game.getRegisteredPlayers());
            assertEquals(2, sitze.size(), "zwei Sitze");
            assertEquals(1, sitze.get(0).getTeam(), "Sitz 0 traegt Team 1 - sonst ging der applyTeams-Aufruf in startSpectator verloren");
            assertEquals(2, sitze.get(1).getTeam(), "Sitz 1 traegt Team 2 - sonst ging der applyTeams-Aufruf in startSpectator verloren");
        } finally {
            match.end();
            ((WebGuiBase) GuiBase.getInterface()).setGuiSupplier(null);
        }
    }

    private static Game warteAufSpiel(HumanMatch match) throws InterruptedException {
        long ende = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2);
        while (System.currentTimeMillis() < ende) {
            if (match.isRunning()) {
                return match.laufendesSpiel();
            }
            Thread.sleep(100);
        }
        return fail("Forge hat innerhalb von 2 Minuten kein laufendes Spiel geliefert");
    }
}
