package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
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

/** Phage the Untouchable: "Whenever Phage deals combat damage to a player, that player loses the game."
 *  Kevins Befund: 4-Spieler-Commander im Browser (Mensch + KI, teils Sim-KI) fror ein, als Phage einen
 *  Spieler aus dem Spiel nahm - der Spielthread starb mit einer Ausnahme. Szenen: A greift mit Phage den
 *  blockerlosen B an, B verliert, das Spiel muss bis zur Hauptphase 1 des naechsten Spielers weiterlaufen.
 *
 *  <p>Befund (Report {@code .superpowers/sdd/phage-report.md}): nur mit Standard-KIs gruen. Sobald irgendeine
 *  Sim-KI am Tisch sitzt, starb die erste Spielkopie nach Bs Ausscheiden mit {@code Couldn't map B}:
 *  {@code GameCopier.makeCopy} mappte nur {@code getPlayers()} (noch im Spiel), der offene {@code Combat}
 *  verwies aber weiter auf B als Verteidiger. Fork-Fix 54afc702: alle registrierten Spieler mappen, verlorene
 *  in der Kopie als verloren nachbilden. Ohne den Fix scheiterte auch die Variante "aktiver Spieler scheidet im
 *  eigenen Zug aus" (Kopie ohne aktiven Spieler, NPE in {@code getPlayerTurn().getCreaturesInPlay()}). */
class PhagePlayerLossTest {
    private static final String PHAGE = "Phage the Untouchable";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** A kontrolliert Phage (ohne Einsatzverzoegerung, direkt ins Spiel gelegt - der ETB-Trigger "if you didn't
     *  cast it from your hand, you lose" feuert dabei nicht, siehe {@link #phageDirektImSpielLoestKeinenEtbAus}).
     *  B hat 5 Leben, alle anderen 40, niemand hat Blocker (die Standard-KI waehlt den Verteidiger nach
     *  Bedrohung + Lebensdefizit, {@code AiAttackController.choosePreferredDefenderPlayer}; ein Gegner mit
     *  grossem Brett wuerde zum bevorzugten Ziel). Bibliotheken gefuellt, damit niemand beim Ziehen verliert. */
    static Scene phageScene(List<AiConfig> configs) {
        Scene s = Scene.of(configs, 3);
        Player a = s.player(0), b = s.player(1);
        s.card(PHAGE, a, ZoneType.Battlefield);
        s.cards("Swamp", 7, a, ZoneType.Battlefield);
        b.setLife(5, null);
        for (int i = 0; i < configs.size(); i++) {
            Player p = s.player(i);
            s.cards("Swamp", 10, p, ZoneType.Library);
        }
        s.setPhase(PhaseType.MAIN1, a);
        return s;
    }

    private static String why(Scene s) {
        return "\n" + s.state() + "\nLog:\n" + s.log(30);
    }

    private static void runAndAssertBLost(Scene s) {
        Player a = s.player(0), b = s.player(1), c = s.player(2);
        s.loopUntil(PhaseType.MAIN1, c);
        assertTrue(b.hasLost(), "B haette verlieren muessen" + why(s));
        assertFalse(s.game().getPlayers().contains(b), "B noch im Spiel" + why(s));
        assertFalse(a.hasLost(), "A hat verloren" + why(s));
        assertFalse(s.game().isGameOver(), "Spiel vorbei" + why(s));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void phageDirektImSpielLoestKeinenEtbAus() {
        Scene s = phageScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT));
        s.step(1);
        assertFalse(s.player(0).hasLost(), "ETB-Trigger hat gefeuert" + why(s));
        assertTrue(s.has(s.player(0), ZoneType.Battlefield, PHAGE));
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void dreiStandardKis() {
        runAndAssertBLost(phageScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void angreiferSimKi() {
        runAndAssertBLost(phageScene(List.of(AiConfig.parse("sim"), AiConfig.DEFAULT, AiConfig.DEFAULT)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void opferSimKi() {
        runAndAssertBLost(phageScene(List.of(AiConfig.DEFAULT, AiConfig.parse("sim"), AiConfig.DEFAULT)));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void mitspielerSimKi() {
        runAndAssertBLost(phageScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.parse("sim"))));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void vierSpielerGemischt() {
        runAndAssertBLost(phageScene(List.of(AiConfig.DEFAULT, AiConfig.parse("sim"), AiConfig.parse("sim"), AiConfig.DEFAULT)));
    }

    /** Verwandter Fall ohne Phage: der aktive Spieler B scheidet im eigenen Zug aus (0 Leben, SBA bei der
     *  ersten Prioritaet im Ziehsegment) und die Sim-KI C entscheidet danach noch in diesem Zug - die Kopie
     *  muss den ausgeschiedenen Spieler als aktiven Spieler kennen. Damit C wirklich handelt (und der Test
     *  nicht leer besteht), hat C vier Shock + acht Mountain und A zwei Baeren als lohnende Ziele; die Szene
     *  beginnt erst in Bs Zug, jede Aktion von C liegt also nach Bs Verlust. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void aktiverSpielerScheidetImEigenenZugAusSimKiEntscheidetDanach() {
        Scene s = Scene.threePlayers(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.parse("sim"));
        Player a = s.player(0), b = s.player(1), c = s.player(2);
        s.cards("Grizzly Bears", 2, a, ZoneType.Battlefield);
        s.cards("Shock", 4, c, ZoneType.Hand);
        s.cards("Mountain", 8, c, ZoneType.Battlefield);
        s.cards("Swamp", 10, a, ZoneType.Library);
        s.cards("Swamp", 10, c, ZoneType.Library);
        b.setLife(0, null);
        s.setPhase(PhaseType.DRAW, b);
        s.loopUntil(PhaseType.END_OF_TURN, b);
        assertTrue(b.hasLost(), "B haette mit 0 Leben verlieren muessen" + why(s));
        assertFalse(s.game().isGameOver(), "Spiel vorbei" + why(s));
        assertTrue(s.count(c, ZoneType.Hand, "Shock") < 4, "Sim-KI C hat nach Bs Verlust nicht gehandelt" + why(s));
    }

    /** Commander-Fall (Review-Befund, Fix-Runde 1): B hat einen Commander in der Kommandozone. Beim Verlust
     *  laesst {@code Game.onPlayerLost} alle Karten von B aufhoeren zu existieren, {@code Player.commanders}
     *  bleibt aber gesetzt - die Sim-Kopie darf daran nicht scheitern. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void opferMitCommanderInDerKommandozone() {
        Scene s = phageScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.parse("sim")));
        Player b = s.player(1);
        b.addCommander(s.card("Grizzly Bears", b, ZoneType.Command));
        runAndAssertBLost(s);
    }

    /** Commander-Fall 2: Bs Commander (getappt im Spiel) hat A vorher Kampfschaden zugefuegt
     *  ({@code A.commanderDamage} hat Bs Commander als Schluessel, hier direkt gesetzt); nach Bs Verlust muss
     *  die Sim-Kopie des ueberlebenden A diesen Eintrag ueberspringen. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void opferCommanderHatteAngreiferSchadenZugefuegt() {
        Scene s = phageScene(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.parse("sim")));
        Player a = s.player(0), b = s.player(1);
        Card commander = s.card("Grizzly Bears", b, ZoneType.Battlefield, true);
        commander.setTapped(true); // hat angegriffen, kann Phage also nicht blocken
        b.addCommander(commander);
        a.addCommanderDamage(commander, 2);
        runAndAssertBLost(s);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void vierSpielerAlleSim() {
        runAndAssertBLost(phageScene(List.of(AiConfig.parse("sim"), AiConfig.parse("sim"), AiConfig.parse("sim"), AiConfig.parse("sim"))));
    }
}
