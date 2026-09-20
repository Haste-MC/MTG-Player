package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.PlayerZoneBattlefield;
import forge.game.zone.ZoneType;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/** Deterministische Geschwister der replay-basierten {@link SimCopierTest}-Faelle: dieselben drei
 *  Stellungen, die im Bench den {@code GameCopier} der Voll-Simulation mit {@code Couldn't map ...}
 *  abstuerzen liessen, hier direkt per {@link Scene} aufgebaut und mit {@code makeCopy()} kopiert -
 *  ohne Zeitbudget, ohne Spielverlauf. Jeder Fall prueft vorher die Vorbedingung, an der der
 *  urspruengliche Fehler hing (sonst wuerde der Test auch ohne den Fork-Fix bestehen). */
class GameCopierTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** A wird Monarch, dann B: As Effektkarte "The Monarch" wird aus der Kommandozone entfernt, meldet
     *  aber weiter die Kommandozone als ihre Zone ("Zombie"). Die Kopie darf daran nicht scheitern und
     *  hat genau eine Monarch-Karte, bei B. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void zombieMonarchEffektkarteUeberlebtDieKopie() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Game game = s.game();

        game.getAction().becomeMonarch(a, "LCC");
        assertSame(a, game.getMonarch());
        assertEquals(1, s.count(a, ZoneType.Command, "The Monarch"));
        game.getAction().becomeMonarch(b, "LCC");
        assertSame(b, game.getMonarch());
        assertEquals(0, s.count(a, ZoneType.Command, "The Monarch"));
        assertEquals(1, s.count(b, ZoneType.Command, "The Monarch"));

        Game copy = assertDoesNotThrow(() -> new GameCopier(game).makeCopy());

        Player ca = copy.getPlayer(a.getId()), cb = copy.getPlayer(b.getId());
        assertSame(cb, copy.getMonarch());
        assertEquals(0, CardLists.filter(ca.getCardsIn(ZoneType.Command), CardPredicates.nameEquals("The Monarch")).size());
        assertEquals(1, CardLists.filter(cb.getCardsIn(ZoneType.Command), CardPredicates.nameEquals("The Monarch")).size());
        assertEquals("LCC", cb.getMonarchSet());
        // Die Kopie behaelt die Regeltexte der Effektkarte (Endschritt-Ziehen, Kampfschaden-Trigger).
        Card copiedMonarch = CardLists.filter(cb.getCardsIn(ZoneType.Command), CardPredicates.nameEquals("The Monarch")).getFirst();
        assertEquals(2, copiedMonarch.getTriggers().size());
    }

    /** A greift mit Grizzly Bears Bs Planeswalker Jace Beleren an; Jace stirbt waehrend des Kampfes.
     *  {@code Combat.removeFromCombat} ersetzt ihn durch den Platzhalter-Verteidiger {@code <Nothing>}
     *  (id -1, keine Zone). Die Kopie darf daran nicht scheitern; der Baer bleibt Angreifer. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nothingPlatzhalterImKampfUeberlebtDieKopie() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Game game = s.game();
        Card bears = s.card("Grizzly Bears", a, ZoneType.Battlefield);
        Card jace = s.card("Jace Beleren", b, ZoneType.Battlefield);

        s.setPhase(PhaseType.COMBAT_DECLARE_ATTACKERS, a);
        Combat combat = new Combat(a);
        game.getPhaseHandler().setCombat(combat);
        combat.addAttacker(bears, jace);
        assertSame(jace, combat.getDefenderByAttacker(bears));

        game.getAction().moveToGraveyard(jace, null);
        assertTrue(s.has(b, ZoneType.Graveyard, "Jace Beleren"));
        GameEntity placeholder = combat.getDefenderByAttacker(bears);
        assertNotNull(placeholder);
        assertTrue(placeholder instanceof Card c && c.getId() == -1 && "<Nothing>".equals(c.getName()),
                "Platzhalter erwartet, war: " + placeholder);

        Game copy = assertDoesNotThrow(() -> new GameCopier(game).makeCopy());

        Combat copied = copy.getPhaseHandler().getCombat();
        assertNotNull(copied);
        assertEquals(1, copied.getAttackers().size());
        Card copiedBears = copied.getAttackers().getFirst();
        assertEquals("Grizzly Bears", copiedBears.getName());
        GameEntity copiedDefender = copied.getDefenderByAttacker(copiedBears);
        assertTrue(copiedDefender instanceof Card c && c.getId() == -1 && "<Nothing>".equals(c.getName()),
                "Platzhalter in der Kopie erwartet, war: " + copiedDefender);
    }

    /** Ein Treasure-Spielstein von B geht vom Spiel ins Exil (Spielsteine werden ausserhalb des Spiels
     *  in keiner Zone gelistet, {@code Card.getZone()} bleibt aber gesetzt); eine Karte von A erinnert
     *  ihn (wie Hostage Takers Effektkarte im Bench). Die Kopie darf daran nicht scheitern; die
     *  Erinnerung an den nicht kopierten Spielstein wird weggelassen. */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void erinnerterSpielsteinImExilUeberlebtDieKopie() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        Player a = s.player(0), b = s.player(1);
        Game game = s.game();
        Card treasure = s.token("c_a_treasure_sac", b, ZoneType.Battlefield);
        assertTrue(treasure.isToken());
        assertTrue(s.has(b, ZoneType.Battlefield, "Treasure Token"), s.state());
        Card taker = s.card("Hostage Taker", a, ZoneType.Battlefield);

        Card exiled = game.getAction().exile(treasure, null, null);
        assertNotNull(exiled);
        taker.addRemembered(exiled);
        // Vorbedingung des Bench-Absturzes: Zone gesetzt, aber in keiner Zonenliste
        assertNotNull(exiled.getZone());
        assertEquals(ZoneType.Exile, exiled.getZone().getZoneType());
        assertFalse(game.getCardsIn(ZoneType.Exile).contains(exiled));
        assertFalse(s.has(b, ZoneType.Battlefield, "Treasure Token"));

        Game copy = assertDoesNotThrow(() -> new GameCopier(game).makeCopy());

        Card copiedTaker = CardLists.filter(copy.getPlayer(a.getId()).getCardsIn(ZoneType.Battlefield),
                CardPredicates.nameEquals("Hostage Taker")).getFirst();
        assertFalse(copiedTaker.hasRemembered(), "Erinnerung an nicht kopierten Spielstein muss entfallen");
    }

    /** Gemeldete Karte (Titania, Gaea Incarnate nach {@link MeldTitaniaTest} Szene A): die Kopie muss
     *  {@code getMeldedWith()} behalten, sonst stirbt {@code Card.getCMC} im Zustand {@code Meld} mit NPE -
     *  genau das passiert in der ersten Spielkopie der Sim-KI nach dem Meld (MeldTitaniaTest Szene C). */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void gemeldeteKarteUeberlebtDieKopie() {
        Scene s = MeldTitaniaTest.meldScene(AiConfig.DEFAULT, AiConfig.DEFAULT, 4);
        Player a = s.player(0), b = s.player(1);
        s.setPhase(PhaseType.END_OF_TURN, b);
        s.loopUntil(PhaseType.MAIN1, a);
        Card melded = CardLists.filter(a.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Titania, Gaea Incarnate")).getFirst();
        assertNotNull(melded.getMeldedWith());
        assertEquals(3, melded.getCMC());

        Game copy = assertDoesNotThrow(() -> new GameCopier(s.game()).makeCopy());

        Card copied = CardLists.filter(copy.getPlayer(a.getId()).getCardsIn(ZoneType.Battlefield),
                CardPredicates.nameEquals("Titania, Gaea Incarnate")).getFirst();
        assertNotNull(copied.getMeldedWith(), "meldedWith fehlt in der Kopie");
        int cmc = assertDoesNotThrow(() -> copied.getCMC());
        assertEquals(3, cmc);
        // das Gegenstueck liegt wie im Original in der Melded-Liste des Schlachtfelds, nicht als eigene bleibende Karte
        Player copyA = copy.getPlayer(a.getId());
        assertTrue(((PlayerZoneBattlefield) copyA.getZone(ZoneType.Battlefield)).getMeldedCards().contains(copied.getMeldedWith()),
                "Argoth fehlt in der Melded-Liste der Kopie");
        assertTrue(CardLists.filter(copyA.getCardsIn(ZoneType.Battlefield), CardPredicates.nameEquals("Argoth, Sanctum of Nature")).isEmpty(),
                "Argoth liegt in der Kopie als eigene bleibende Karte im Spiel");
    }
}
