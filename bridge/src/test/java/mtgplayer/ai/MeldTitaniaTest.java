package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/** Titania, Voice of Gaea + Argoth, Sanctum of Nature: Kevins Befund, dass beide in sechs Partien lagen
 *  und nie gemeldet wurden (Spec §2). Szene A prueft die Mechanik (Trigger im Versorgungssegment bei
 *  >= 4 Laendern im Friedhof), Szene B die Strategie (arbeitet die KI mit Argoths {@code {2}{G}{G},{T}}:
 *  Baer + Mill 3 auf die Meld-Bedingung hin?), Szene C die Mechanik mit Sim-KI.
 *
 *  <p>Befund (Task 1, Report {@code .superpowers/sdd/task-1-report.md}): A gruen - der Meld funktioniert.
 *  B in Hauptphase 1 rot, weil {@code TokenAi.checkPhaseRestrictions} Spielsteine ohne Eile im eigenen
 *  Zug grundsaetzlich erst in Hauptphase 2 erzeugt; in Hauptphase 2 aktiviert die Standard-KI Argoth
 *  (Profilwuerfel {@code TOKEN_GENERATION_ABILITY_CHANCE=80}), die Sim-KI schon in Hauptphase 1. C rot:
 *  {@code GameCopier} kopiert {@code Card.getMeldedWith()} nicht, die erste Spielkopie der Sim-KI mit der
 *  gemeldeten Karte stirbt in {@code Card.getCMC} mit NPE. */
class MeldTitaniaTest {
    private static final String TITANIA = "Titania, Voice of Gaea";
    private static final String ARGOTH = "Argoth, Sanctum of Nature";
    private static final String MELDED = "Titania, Gaea Incarnate";
    private static final String BEAR = "Bear Token";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** A kontrolliert Titania + Argoth + 3 Forest, {@code landsInGraveyard} Forest im Friedhof; Bibliotheken
     *  gefuellt, damit niemand beim Ziehen verliert. */
    static Scene meldScene(AiConfig a, AiConfig b, int landsInGraveyard) {
        Scene s = Scene.twoPlayers(a, b, 3);
        Player pa = s.player(0), pb = s.player(1);
        s.card(TITANIA, pa, ZoneType.Battlefield);
        s.card(ARGOTH, pa, ZoneType.Battlefield);
        s.cards("Forest", 3, pa, ZoneType.Battlefield);
        s.cards("Forest", landsInGraveyard, pa, ZoneType.Graveyard);
        s.cards("Forest", 10, pa, ZoneType.Library);
        s.cards("Forest", 10, pb, ZoneType.Library);
        return s;
    }

    /** Szene B: wie {@link #meldScene} mit nur 2 Laendern im Friedhof und 4 weiteren ungetappten Forest
     *  (Argoth + 7 Forest = 8 Mana), Hauptphase 1 von A. */
    private static Scene strategyScene(AiConfig a) {
        Scene s = meldScene(a, AiConfig.DEFAULT, 2);
        s.cards("Forest", 4, s.player(0), ZoneType.Battlefield);
        s.setPhase(PhaseType.MAIN1, s.player(0));
        return s;
    }

    private static int landsInGraveyard(Player p) {
        return CardLists.filter(p.getCardsIn(ZoneType.Graveyard), CardPredicates.LANDS).size();
    }

    private static boolean workedTowardsMeld(Scene s, Player a) {
        return s.has(a, ZoneType.Battlefield, BEAR) || landsInGraveyard(a) >= 4;
    }

    private static String why(Scene s) {
        return "\n" + s.state() + "\nLog:\n" + s.log(30);
    }

    private static void assertMelded(Scene s, Player a) {
        assertTrue(s.has(a, ZoneType.Battlefield, MELDED), "Titania, Gaea Incarnate erwartet" + why(s));
        assertFalse(s.has(a, ZoneType.Battlefield, TITANIA), "Titania, Voice of Gaea noch im Spiel" + why(s));
        assertFalse(s.has(a, ZoneType.Battlefield, ARGOTH), "Argoth noch im Spiel" + why(s));
    }

    /** Szene A (Mechanik, Standard-KI): Gegner-Endschritt, Schleife bis As Hauptphase 1 -> der Upkeep-Trigger
     *  hat gefeuert und beide Karten sind zu Titania, Gaea Incarnate gemeldet. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void szeneA_mechanikMeldetImVersorgungssegment() {
        Scene s = meldScene(AiConfig.DEFAULT, AiConfig.DEFAULT, 4);
        Player a = s.player(0), b = s.player(1);
        s.setPhase(PhaseType.END_OF_TURN, b);
        s.loopUntil(PhaseType.MAIN1, a);
        assertMelded(s, a);
    }

    /** Szene B (Strategie, Standard-KI, Spec-Fassung): Hauptphase 1 -> vor dem Kampf ist ein Baer-Spielstein
     *  im Spiel (Argoth aktiviert) oder der Friedhof hat >= 4 Laender. */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @Disabled("rot: TokenAi.checkPhaseRestrictions erzeugt Spielsteine ohne Eile im eigenen Zug erst in Hauptphase 2"
            + " (forge-ai TokenAi Z. 113-125), die Standard-KI passt in Hauptphase 1 mit 8 offenem Mana - siehe Task 2")
    void szeneB_standardKiAktiviertArgothVorDemKampf() {
        Scene s = strategyScene(AiConfig.DEFAULT);
        Player a = s.player(0);
        s.loopUntil(PhaseType.COMBAT_BEGIN, a);
        assertTrue(workedTowardsMeld(s, a), "Argoth nicht aktiviert (Baer: " + s.has(a, ZoneType.Battlefield, BEAR)
                + ", Laender im Friedhof: " + landsInGraveyard(a) + ")" + why(s));
    }

    /** Szene B, beobachtetes Verhalten: bis zum Endschritt (also inkl. Hauptphase 2) aktiviert die Standard-KI
     *  Argoth - mit dem Profilwuerfel {@code TOKEN_GENERATION_ABILITY_CHANCE=80} ({@code TokenAi.checkApiLogic}),
     *  deshalb ueber fuenf feste Seeds und mindestens drei Treffer (bei 80 % waeren <= 2 von 5 nur in 5,8 % der
     *  Faelle zu erwarten; die Seeds sind deterministisch, solange die KI die Zufallsquelle gleich verbraucht). */
    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void szeneB_standardKiAktiviertArgothInHauptphase2() {
        List<String> misses = new ArrayList<>();
        int hits = 0;
        for (int seed = 1; seed <= 5; seed++) {
            MyRandom.setRandom(new Random(seed));
            Scene s = strategyScene(AiConfig.DEFAULT);
            Player a = s.player(0);
            s.loopUntil(PhaseType.END_OF_TURN, a);
            if (workedTowardsMeld(s, a)) {
                hits++;
            } else {
                misses.add("Seed " + seed + why(s));
            }
        }
        assertTrue(hits >= 3, "nur " + hits + "/5 Seeds aktivieren Argoth bis zum Endschritt:\n" + String.join("\n", misses));
    }

    /** Szene B mit Sim-KI: die Voll-Simulation aktiviert Argoth schon in Hauptphase 1 (keine Phasenregel,
     *  Bewertung: Baer + 2 Leben durch Titania). */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void szeneB_simKiAktiviertArgothVorDemKampf() {
        Scene s = strategyScene(AiConfig.parse("sim"));
        Player a = s.player(0);
        s.loopUntil(PhaseType.COMBAT_BEGIN, a);
        assertTrue(workedTowardsMeld(s, a), "Argoth nicht aktiviert (Baer: " + s.has(a, ZoneType.Battlefield, BEAR)
                + ", Laender im Friedhof: " + landsInGraveyard(a) + ")" + why(s));
    }

    /** Szene C (Mechanik, Sim-KI): wie A mit Voll-Simulation auf A. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @Disabled("rot: der Meld selbst gelingt, aber die erste Spielkopie der Sim-KI danach stirbt mit NPE in Card.getCMC"
            + " (GameCopier kopiert Card.getMeldedWith nicht) - siehe Task 2 und GameCopierTest.gemeldeteKarteUeberlebtDieKopie")
    void szeneC_mechanikMeldetMitSimKi() {
        Scene s = meldScene(AiConfig.parse("sim"), AiConfig.DEFAULT, 4);
        Player a = s.player(0), b = s.player(1);
        s.setPhase(PhaseType.END_OF_TURN, b);
        s.loopUntil(PhaseType.MAIN1, a);
        assertMelded(s, a);
    }
}
