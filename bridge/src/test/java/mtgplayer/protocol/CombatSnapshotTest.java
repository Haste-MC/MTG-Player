package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Kampfdaten im Snapshot (Spec 2026-09-24-kampfanzeige, §1/§2): wer greift wen an und wer blockt.
 *  Die Szene setzt den Kampf direkt ueber {@link Combat} statt ihn auszuspielen - so steht die Stellung
 *  deterministisch, ohne von KI-Entscheidungen abzuhaengen. {@code Game.updateCombatForView()} baut aus
 *  dem Combat die {@code CombatView}, die der Serializer liest. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CombatSnapshotTest {
    private static final String BEAR = "Grizzly Bears";
    private static final String WALL = "Wall of Wood";
    private static final String WALKER = "Jace Beleren";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Sitz 0 greift mit einem Baeren Sitz 1 an; Sitz 1 hat eine Mauer als moeglichen Blocker. */
    private static Scene attackScene() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        s.card(BEAR, s.player(0), ZoneType.Battlefield);
        s.card(WALL, s.player(1), ZoneType.Battlefield);
        s.cards("Forest", 10, s.player(0), ZoneType.Library);
        s.cards("Forest", 10, s.player(1), ZoneType.Library);
        s.setPhase(PhaseType.COMBAT_DECLARE_BLOCKERS, s.player(0));
        return s;
    }

    private static Card only(Scene s, int seat, String name) {
        for (Card c : s.player(seat).getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " nicht im Spiel von Sitz " + seat);
    }

    private static Snapshot snapshotOf(Scene s) {
        return StateSerializer.snapshot(s.game().getView(), ViewContext.plain(s.player(0).getView()));
    }

    @Test
    void ohneKampfKeineKampfdaten() {
        Scene s = attackScene();
        assertNull(snapshotOf(s).combat());
    }

    @Test
    void angreiferTraegtSeinZiel() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        List<Snapshot.AttackSnap> attacks = snapshotOf(s).combat();
        assertNotNull(attacks);
        assertEquals(1, attacks.size());
        Snapshot.AttackSnap a = attacks.get(0);
        assertEquals(bear.getId(), a.attacker());
        assertEquals(s.player(1).getId(), a.defenderPlayer());
        assertNull(a.defenderCard());
        assertNull(a.blockers());
        assertTrue(snapshotOf(s).cards().containsKey(bear.getId()), "Angreifer fehlt in der Kartentabelle");
    }

    @Test
    void geblockterAngreiferTraegtSeineBlocker() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Card wall = only(s, 1, WALL);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        combat.addBlocker(bear, wall);
        combat.setBlocked(bear, true);
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        Snapshot.AttackSnap a = snapshotOf(s).combat().get(0);
        assertEquals(List.of(wall.getId()), a.blockers());
    }

    /** Greift der Baer statt eines Spielers einen Planeswalker an (in Commander alltaeglich), traegt die
     *  Zeile {@code defenderCard} statt {@code defenderPlayer} - und der Planeswalker landet trotzdem in
     *  der Kartentabelle, obwohl {@link #attackScene()} ihn nicht kennt. */
    @Test
    void angreiferGegenPlaneswalkerTraegtDieKarteAlsZiel() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Card walker = s.card(WALKER, s.player(1), ZoneType.Battlefield);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, walker);
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        Snapshot snap = snapshotOf(s);
        Snapshot.AttackSnap a = snap.combat().get(0);
        assertEquals(walker.getId(), a.defenderCard());
        assertNull(a.defenderPlayer());
        assertTrue(snap.cards().containsKey(walker.getId()), "Planeswalker fehlt in der Kartentabelle");
    }

    /** Solange der Mensch noch zuteilt, ist das Band nicht als "geblockt" markiert: Forge fuehrt die
     *  Zuordnung dann nur als geplante Blocker (GameView.updateCombat uebergibt sie separat). Genau in
     *  diesem Moment ist die Anzeige am nuetzlichsten, also muss der Serializer darauf zurueckfallen. */
    @Test
    void geplanteBlockerZaehlenAuch() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Card wall = only(s, 1, WALL);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        combat.addBlocker(bear, wall);
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        Snapshot.AttackSnap a = snapshotOf(s).combat().get(0);
        assertEquals(List.of(wall.getId()), a.blockers());
    }
}
