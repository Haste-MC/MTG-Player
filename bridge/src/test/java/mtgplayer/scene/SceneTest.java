package mtgplayer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/** Der Szenen-Harness baut ein Spiel ohne Decks direkt in einer Stellung auf (nach Forges {@code AITest}),
 *  damit Mechanik-Faelle in Sekunden statt ueber ganze Partien pruefbar sind. */
class SceneTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void stellungAufbauenUndBisZumAngriffLaufen() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        assertEquals("A", a.getName());
        assertEquals("B", b.getName());
        assertSame(a, s.game().getPhaseHandler().getPlayerTurn());
        assertTrue(s.game().getPhaseHandler().is(PhaseType.MAIN1));

        s.card("Grizzly Bears", a, ZoneType.Battlefield);
        s.cards("Forest", 3, a, ZoneType.Battlefield);
        s.setPhase(PhaseType.MAIN1, a);

        assertTrue(s.has(a, ZoneType.Battlefield, "Grizzly Bears"));
        assertEquals(3, s.count(a, ZoneType.Battlefield, "Forest"));
        assertFalse(s.has(b, ZoneType.Battlefield, "Grizzly Bears"));

        s.loopUntil(PhaseType.COMBAT_DECLARE_ATTACKERS, a);
        assertTrue(s.game().getPhaseHandler().is(PhaseType.COMBAT_DECLARE_ATTACKERS));
        assertSame(a, s.game().getPhaseHandler().getPlayerTurn());
        assertFalse(s.game().isGameOver());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void loopUntilBrichtMitDeckelAb() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        // Ohne Bibliothek verliert der Spieler beim ersten Ziehen; die Schleife darf dann nicht haengen.
        s.setPhase(PhaseType.END_OF_TURN, s.player(1));
        assertThrows(AssertionError.class, () -> s.loopUntil(PhaseType.MAIN2, s.player(0)));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void dreiSpieler() {
        Scene s = Scene.threePlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT);
        assertEquals(3, s.game().getPlayers().size());
        assertEquals("C", s.player(2).getName());
    }
}
