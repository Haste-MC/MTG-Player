package mtgplayer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.ai.simulation.GameStateEvaluator;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Commander, der beim Hereinkommen <em>jedem</em> Spieler einen Spielstein gibt und alle davon
 *  aergert ("goad"). Forges Simulations-Bewertung ({@code GameStateEvaluator}) zog jedes gegnerische
 *  Permanent mit vollem Wert ab und kannte den Goad nicht: am Vierertisch standen +276 (Koerper) und
 *  +145 (eigener Vogel) gegen 3x -145 (Vogel je Gegner), also -14 - und die KI liess den Commander
 *  in den Modi Hybrid und Simulation dauerhaft in der Kommandozone stehen. Ein von der KI geaergerter
 *  Gegner-Spielstein kann aber nicht blocken und darf, solange ein anderer Spieler angreifbar ist,
 *  gar nicht auf die KI zeigen. */
class GoadedTokenSceneTest {

    private static final String COMMANDER = "Rendmaw, Creaking Nest";
    private static final String TOKEN = "Bird Token";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Tisch mit {@code seats} KI-Sitzen; Sitz 0 hat 5 Wald + 4 Sumpf und damit sicher die 3BG. */
    private static Scene table(AiConfig seat0, int seats) {
        List<AiConfig> cfgs = new ArrayList<>();
        cfgs.add(seat0);
        for (int i = 1; i < seats; i++) {
            cfgs.add(AiConfig.DEFAULT);
        }
        Scene s = Scene.of(cfgs, 10);
        Player a = s.player(0);
        s.cards("Forest", 5, a, ZoneType.Battlefield);
        s.cards("Swamp", 4, a, ZoneType.Battlefield);
        s.cards("Forest", 20, a, ZoneType.Library);
        for (int i = 1; i < seats; i++) {
            s.cards("Island", 20, s.player(i), ZoneType.Library);
            s.card("Island", s.player(i), ZoneType.Battlefield);
        }
        return s;
    }

    /** Wirkt die KI den Commander im eigenen Zug? Die Entscheidung faellt in Main 2, deshalb laeuft
     *  die Szene bis zum Zugende - {@code loopUntil(MAIN2, ...)} wuerde vor der Prioritaet anhalten. */
    private static void castsCommander(AiConfig mode, int seats) {
        Scene s = table(mode, seats);
        Player a = s.player(0);
        s.commander(COMMANDER, a);
        s.setPhase(PhaseType.MAIN1, a);
        s.loopUntil(PhaseType.END_OF_TURN, a);

        assertTrue(s.has(a, ZoneType.Battlefield, COMMANDER),
                mode.mode() + " am " + seats + "er-Tisch: Commander blieb in der Kommandozone\n" + s.state());
        for (int i = 0; i < seats; i++) {
            assertEquals(1, s.count(s.player(i), ZoneType.Battlefield, TOKEN),
                    "jeder Spieler bekommt genau einen Spielstein\n" + s.state());
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void standardWirftDenCommanderAmVierertisch() {
        castsCommander(AiConfig.DEFAULT, 4);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void hybridWirftDenCommanderAmVierertisch() {
        castsCommander(new AiConfig(AiConfig.Mode.HYBRID, "Default"), 4);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void simulationWirftDenCommanderAmVierertisch() {
        castsCommander(new AiConfig(AiConfig.Mode.SIM, "Default"), 4);
    }

    /** Der Kern der Korrektur, ohne KI-Lauf: die Stellung nach dem Wirken muss fuer die Simulations-
     *  Bewertung besser sein als die davor - und zwar bei jeder Tischgroesse. Vor der Korrektur
     *  kippte genau das am Vierertisch ins Minus. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void stellungNachDemWirkenIstBeiJederTischgroesseBesser() {
        for (int seats = 2; seats <= 4; seats++) { // mehr Sitze gibt der Harness nicht her
            Scene s = table(AiConfig.DEFAULT, seats);
            Player a = s.player(0);
            s.commander(COMMANDER, a);
            s.setPhase(PhaseType.MAIN2, a);

            GameStateEvaluator ev = new GameStateEvaluator();
            int before = ev.getScoreForGameState(s.game(), a).value;

            s.card(COMMANDER, a, ZoneType.Battlefield, true);
            long ts = s.game().getNextTimestamp();
            for (int i = 0; i < seats; i++) {
                Card bird = s.token("b_2_2_bird_flying", s.player(i), ZoneType.Battlefield);
                bird.setTapped(true);
                bird.addGoad(ts + i, a);
            }
            int after = ev.getScoreForGameState(s.game(), a).value;

            assertTrue(after > before, seats + " Spieler: Bewertung faellt von " + before + " auf " + after);
        }
    }
}
