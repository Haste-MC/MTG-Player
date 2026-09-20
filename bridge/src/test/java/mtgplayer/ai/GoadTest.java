package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Goad (Spec §3), Kevins Fall Agitator Ant: "At the beginning of your end step, each player may put two
 *  +1/+1 counters on a creature they control. Goad each creature that had counters put on it this way."
 *  Skript {@code a/agitator_ant.txt}: {@code ChooseCard} mit {@code MinAmount$ 0} (darf ablehnen), dann
 *  {@code PutCounterAll} + {@code Goad}. Sitz A = KI unter Test, B kontrolliert die Ameise, C dritter Spieler.
 *
 *  <p>Die Szenen starten in Bs Hauptphase 2, nicht im Endsegment: {@code PhaseHandler.devModeSet} ruft
 *  {@code onPhaseBegin} nicht auf, ein direkt gesetztes Endsegment wuerde den Phasen-Trigger nie feuern.
 *  Szene (d) goadet den Baeren direkt ({@code Card.addGoad}), weil die KI nach dem Fix in dieser Stellung
 *  keine Marken mehr nimmt - geprueft wird dort nur die Angriffspflicht. */
class GoadTest {
    private static final String ANT = "Agitator Ant";
    private static final String BEARS = "Grizzly Bears";
    private static final String DREADMAW = "Colossal Dreadmaw";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Sitz 0 (A) hat Grizzly Bears, Sitz 1 (B) Agitator Ant + Colossal Dreadmaw (6/6, ungetappt), weitere
     *  Sitze nichts; Bibliotheken gefuellt. Start: Hauptphase 2 von B. */
    private static Scene antScene(List<AiConfig> configs) {
        Scene s = Scene.of(configs, 3);
        Player a = s.player(0), b = s.player(1);
        s.card(BEARS, a, ZoneType.Battlefield);
        s.card(ANT, b, ZoneType.Battlefield);
        s.card(DREADMAW, b, ZoneType.Battlefield);
        for (int i = 0; i < configs.size(); i++) {
            s.cards("Forest", 10, s.player(i), ZoneType.Library);
        }
        s.setPhase(PhaseType.MAIN2, b);
        return s;
    }

    private static Card bears(Scene s) {
        for (Card c : s.player(0).getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(BEARS)) {
                return c;
            }
        }
        return null;
    }

    private static String why(Scene s) {
        return "\n" + s.state() + "\nLog:\n" + s.log(30);
    }

    private static void assertTriggerFired(Scene s) {
        // The Ant's controller B always has a creature to put counters on; if neither of B's creatures got
        // counters, the end-step trigger did not resolve and the scene would test nothing.
        boolean bTookCounters = false;
        for (Card c : s.player(1).getCardsIn(ZoneType.Battlefield)) {
            if (c.getCounters(CounterEnumType.P1P1) > 0) {
                bTookCounters = true;
            }
        }
        assertTrue(bTookCounters, "Trigger der Ameise hat nicht gefeuert (B ohne Marken)" + why(s));
    }

    /** (a) Marken-Entscheidung, guenstig: drei Spieler, C hat keinen Blocker - die gegoadeten 4/4-Baeren
     *  koennen C frei angreifen. Erwartung: A nimmt die Marken. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void a_markenGuenstigDreiSpieler() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0), b = s.player(1);
        s.loopUntil(PhaseType.UPKEEP, a);
        assertTriggerFired(s);
        Card bears = bears(s);
        assertNotNull(bears, "Baeren weg" + why(s));
        assertEquals(2, bears.getCounters(CounterEnumType.P1P1), "A haette die Marken nehmen sollen" + why(s));
        assertEquals(4, bears.getNetPower(), "Baeren nicht 4/4" + why(s));
        assertTrue(bears.isGoadedBy(b), "Baeren nicht von B gegoadet" + why(s));
    }

    /** (b) Marken-Entscheidung, unguenstig: nur A und B - die gegoadeten 4/4-Baeren muessten in Bs
     *  6/6 laufen. Erwartung: A nimmt keine Marken (und wird damit auch nicht gegoadet). */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void b_markenUnguenstigZweiSpieler() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0);
        s.loopUntil(PhaseType.UPKEEP, a);
        assertTriggerFired(s);
        Card bears = bears(s);
        assertNotNull(bears, "Baeren weg" + why(s));
        assertEquals(0, bears.getCounters(CounterEnumType.P1P1), "A haette die Marken ablehnen sollen" + why(s));
        assertFalse(bears.isGoaded(), "Baeren gegoadet" + why(s));
    }

    /** Gegenprobe zu (b): zwei Spieler, aber B hat keinen Blocker (nur die Ameise, 2/2, die die 4/4-Baeren nicht
     *  profitabel blockt) - der Goad kostet nichts, A soll die Marken nehmen. Schuetzt vor einem
     *  "im Zweikampf nie"-Fix. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void b2_markenGuenstigZweiSpielerOhneBlocker() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0), b = s.player(1);
        for (Card c : List.copyOf(b.getCardsIn(ZoneType.Battlefield))) {
            if (c.getName().equals(DREADMAW)) {
                b.getZone(ZoneType.Battlefield).remove(c);
            }
        }
        s.loopUntil(PhaseType.UPKEEP, a);
        assertTriggerFired(s);
        Card bears = bears(s);
        assertNotNull(bears, "Baeren weg" + why(s));
        assertEquals(2, bears.getCounters(CounterEnumType.P1P1), "A haette die Marken nehmen sollen" + why(s));
        assertTrue(bears.isGoadedBy(b), "Baeren nicht von B gegoadet" + why(s));
    }

    /** Gegenprobe zu (b): As einzige Kreatur kann nicht angreifen (Verteidiger) - der Goad ist wirkungslos,
     *  die Marken sind geschenkt. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void b3_markenAufVerteidigerSindGeschenkt() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0), b = s.player(1);
        a.getZone(ZoneType.Battlefield).remove(bears(s));
        Card wall = s.card("Wall of Stone", a, ZoneType.Battlefield);
        s.loopUntil(PhaseType.UPKEEP, a);
        assertTriggerFired(s);
        assertEquals(2, wall.getCounters(CounterEnumType.P1P1), "A haette die Marken nehmen sollen" + why(s));
        assertTrue(wall.isGoadedBy(b), "Mauer nicht von B gegoadet" + why(s));
    }

    /** (c) Angriff: wie (a), weiter bis zu As Blocker-Segment - die gegoadeten Baeren greifen an, und zwar
     *  C (nicht den Goader B). */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void c_angriffAufDenDrittenSpieler() {
        assertAttacksThirdPlayer(antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT)));
    }

    /** (e) wie (c) mit Sim-KI auf A. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void e_angriffAufDenDrittenSpielerSimKi() {
        assertAttacksThirdPlayer(antScene(List.of(AiConfig.parse("sim"), AiConfig.DEFAULT, AiConfig.DEFAULT)));
    }

    private static void assertAttacksThirdPlayer(Scene s) {
        Player a = s.player(0), b = s.player(1), c = s.player(2);
        s.loopUntil(PhaseType.UPKEEP, a);
        assertTriggerFired(s);
        Card bears = bears(s);
        assertNotNull(bears, "Baeren weg" + why(s));
        assertTrue(bears.isGoadedBy(b), "Baeren nicht von B gegoadet - Szene prueft nichts" + why(s));
        s.loopUntil(PhaseType.COMBAT_DECLARE_BLOCKERS, a);
        Combat combat = s.game().getCombat();
        assertNotNull(combat, "kein Kampf" + why(s));
        assertTrue(combat.isAttacking(bears), "Baeren greifen nicht an (Pflicht)" + why(s));
        assertSame(c, combat.getDefenderByAttacker(bears), "Baeren greifen nicht C an" + why(s));
    }

    /** (d) Angriff, nur der Goader angreifbar: zwei Spieler, Baeren direkt mit zwei Marken und Goad von B
     *  versehen - sie muessen B angreifen (einziger Spieler), auch in den 6/6 hinein; kein Haenger, keine
     *  Ausnahme, keine ungueltige Angriffserklaerung. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void d_angriffAufDenGoaderWennNiemandSonstAngreifbar() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0), b = s.player(1);
        Card bears = bears(s);
        bears.addCounterInternal(CounterEnumType.P1P1, 2, b, false, null, null);
        bears.addGoad(s.game().getNextTimestamp(), b);
        s.setPhase(PhaseType.END_OF_TURN, b);
        s.loopUntil(PhaseType.COMBAT_DECLARE_BLOCKERS, a);
        assertTrue(bears.isGoadedBy(b), "Goad verloren" + why(s));
        Combat combat = s.game().getCombat();
        assertNotNull(combat, "kein Kampf" + why(s));
        assertTrue(combat.isAttacking(bears), "Baeren greifen nicht an (Pflicht)" + why(s));
        assertSame(b, combat.getDefenderByAttacker(bears), "Baeren greifen nicht B an" + why(s));
    }

    /** Sim-Bewertung: die Spielkopie der Voll-Simulation muss den Goad-Zustand tragen, sonst zaehlt die
     *  Simulation die gegoadete Kreatur als frei (Spec §3). Kopie nach dem Trigger aus (a). */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void kopieBehaeltGoad() {
        Scene s = antScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT));
        Player a = s.player(0), b = s.player(1);
        s.loopUntil(PhaseType.UPKEEP, a);
        Card bears = bears(s);
        assertNotNull(bears, "Baeren weg" + why(s));
        assertTrue(bears.isGoadedBy(b), "Baeren nicht von B gegoadet - Szene prueft nichts" + why(s));
        Game copy = new GameCopier(s.game()).makeCopy();
        Player cb = copy.getPlayer(b.getId());
        Card copied = null;
        for (Card c : copy.getPlayer(a.getId()).getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(BEARS)) {
                copied = c;
            }
        }
        assertNotNull(copied, "Baeren nicht kopiert");
        assertTrue(copied.isGoaded(), "Kopie hat den Goad verloren");
        assertTrue(copied.isGoadedBy(cb), "Kopie kennt den Goader nicht");
    }
}
