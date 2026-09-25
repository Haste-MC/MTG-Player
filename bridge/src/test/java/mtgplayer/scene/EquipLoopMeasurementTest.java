package mtgplayer.scene;

import forge.game.Game;
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

/**
 * Messexperiment zum Stillstand aus der echten Partie: ein KI-Sitz mit Lightning Greaves
 * (Equip {0}) und drei Kreaturen unterschiedlicher Guete zieht die Ausruestung wiederholt um,
 * bis eine Partie faktisch steht. Zaehlt Traeger-Wechsel je Zug und misst die Zugdauer,
 * einmal mit Standard-KI und einmal mit Voll-Simulation - siehe
 * .superpowers/sdd/equip-loop-report.md fuer die Auswertung. Nur ein Messwerkzeug, keine
 * Verhaltens-Assertion: die Zahlen werden auf stdout ausgegeben (Surefire faengt sie ab).
 *
 * <p>Profil "Experimental" statt "Default": {@code MOVE_EQUIPMENT_TO_BETTER_CREATURES} steht im
 * Default-Profil auf {@code from_useless_only} (AttachAi.java Z. 1364-1369) - die KI zieht die
 * Ausruestung dort nur von einer "nutzlosen" Kreatur ab (dauerhaft nicht enttappend, gesperrt,
 * beschlagnahmt), niemals blos zu einer besseren. Mit drei gewoehnlichen Kreaturen ohne solche
 * Einschraenkung wuerde das Default-Profil den Traeger nie wechseln und das Experiment liefe leer.
 * "Experimental" (wie "Reckless") setzt {@code always} und macht damit den
 * {@code MOVE_EQUIPMENT_CREATURE_EVAL_THRESHOLD}-Pfad (Z. 1387-1391) zur einzigen Bremse -
 * derselbe Pfad, den Kevins Sitz vermutlich nutzt, wenn der Traeger ueber mehrere gewoehnliche
 * Kreaturen wanderte statt nur von einer gesperrten wegzuziehen.
 */
class EquipLoopMeasurementTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void standardKi() {
        messen(AiConfig.Mode.STANDARD);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void simKi() {
        messen(AiConfig.Mode.SIM);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void hybridKi() {
        messen(AiConfig.Mode.HYBRID);
    }

    /** Wie {@link #simKi()}, aber mit einem Gegner, der zurueckschlaegt: B haelt zwei Blocker/
     *  Angreifer, sodass A's Kreaturen im Kampf tappen und Schaden nehmen - die Frage, wer den
     *  Shroud/Haste-Traeger am noetigsten braucht, aendert sich dadurch von Zug zu Zug, anders als
     *  im rein statischen Brett oben. Prueft, ob Kampfdruck den Wechsel ueberhaupt erst ausloest. */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void simKiMitGegnerdruck() {
        messenMitGegnerdruck(AiConfig.Mode.SIM);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void standardKiMitGegnerdruck() {
        messenMitGegnerdruck(AiConfig.Mode.STANDARD);
    }

    private void messen(AiConfig.Mode mode) {
        AiConfig cfg = new AiConfig(mode, "Experimental");
        // aiTimeout=3s wie Scene.twoPlayers(a,b) Standard - begrenzt das Zeitbudget je
        // Entscheidung der Voll-Simulation (deadlineNanos in SpellAbilityPicker.deadlineFor).
        Scene s = Scene.twoPlayers(cfg, AiConfig.DEFAULT, 3);
        Player a = s.player(0), b = s.player(1);

        Card greaves = s.card("Lightning Greaves", a, ZoneType.Battlefield);
        Card bears = s.card("Grizzly Bears", a, ZoneType.Battlefield);   // 2/2
        Card giant = s.card("Hill Giant", a, ZoneType.Battlefield);      // 3/3
        Card wurm = s.card("Craw Wurm", a, ZoneType.Battlefield);        // 6/4
        greaves.attachToEntity(bears, null, true);
        s.cards("Forest", 6, a, ZoneType.Battlefield);
        s.cards("Forest", 30, a, ZoneType.Library);

        s.card("Grizzly Bears", b, ZoneType.Battlefield);
        s.cards("Forest", 4, b, ZoneType.Battlefield);
        s.cards("Forest", 30, b, ZoneType.Library);

        s.setPhase(PhaseType.MAIN1, a);
        auswerten(mode, s, greaves, 4);
    }

    private void messenMitGegnerdruck(AiConfig.Mode mode) {
        AiConfig cfg = new AiConfig(mode, "Experimental");
        Scene s = Scene.twoPlayers(cfg, new AiConfig(AiConfig.Mode.STANDARD, "Experimental"), 3);
        Player a = s.player(0), b = s.player(1);

        Card greaves = s.card("Lightning Greaves", a, ZoneType.Battlefield);
        Card bears = s.card("Grizzly Bears", a, ZoneType.Battlefield);   // 2/2
        Card giant = s.card("Hill Giant", a, ZoneType.Battlefield);      // 3/3
        Card wurm = s.card("Craw Wurm", a, ZoneType.Battlefield);        // 6/4
        greaves.attachToEntity(bears, null, true);
        s.cards("Forest", 6, a, ZoneType.Battlefield);
        s.cards("Forest", 30, a, ZoneType.Library);

        // B kann zurueckschlagen: zwei Kreaturen, die A's Angreifer blocken/toeten koennen, und
        // selbst Angreifer stellen, die A zum Blocken zwingen - das aendert von Zug zu Zug, welche
        // eigene Kreatur gerade am meisten von Shroud (unblockbar durch Entfernung) profitiert.
        s.cards("Forest", 6, b, ZoneType.Battlefield);
        s.card("Hill Giant", b, ZoneType.Battlefield);
        s.card("Craw Wurm", b, ZoneType.Battlefield);
        s.cards("Forest", 30, b, ZoneType.Library);

        s.setPhase(PhaseType.MAIN1, a);
        auswerten(mode, s, greaves, 6);
    }

    private void auswerten(AiConfig.Mode mode, Scene s, Card greaves, int zielZuege) {
        Game game = s.game();
        List<Integer> wechselJeZug = new ArrayList<>();
        List<Long> dauerJeZugMs = new ArrayList<>();
        List<String> traegerFolge = new ArrayList<>();

        Card lastTarget = greaves.getEquipping();
        traegerFolge.add(name(lastTarget));
        int wechselDiesenZug = 0;
        int turn = game.getPhaseHandler().getTurn();
        long turnStart = System.nanoTime();
        int totalSteps = 0;
        final int maxSteps = 8000;      // grosszuegiger Deckel ueber mehrere Zuege

        int zuegeGemessen = 0;
        long testStart = System.nanoTime();
        while (totalSteps < maxSteps && zuegeGemessen < zielZuege && !game.isGameOver()) {
            game.getPhaseHandler().mainLoopStep();
            totalSteps++;

            Card target = greaves.getEquipping();
            if (target != lastTarget) {
                wechselDiesenZug++;
                lastTarget = target;
                traegerFolge.add(name(target));
            }

            int nowTurn = game.getPhaseHandler().getTurn();
            if (nowTurn != turn) {
                wechselJeZug.add(wechselDiesenZug);
                dauerJeZugMs.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - turnStart));
                wechselDiesenZug = 0;
                turn = nowTurn;
                turnStart = System.nanoTime();
                zuegeGemessen++;
            }
        }
        long testDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - testStart);

        System.out.println("=== EquipLoopMeasurement " + mode + " (Profil Experimental) ===");
        System.out.println("Wechsel je Zug (A): " + wechselJeZug);
        System.out.println("Dauer je Zug (A, ms): " + dauerJeZugMs);
        System.out.println("Traeger-Folge: " + traegerFolge);
        System.out.println("Schritte insgesamt: " + totalSteps + " von max " + maxSteps);
        System.out.println("Gemessene Zuege (A): " + zuegeGemessen + " von Ziel " + zielZuege);
        System.out.println("Testdauer gesamt (ms): " + testDurationMs);
        System.out.println("Spiel vorbei: " + game.isGameOver());
    }

    private static String name(Card c) {
        return c == null ? "-" : c.getName();
    }
}
