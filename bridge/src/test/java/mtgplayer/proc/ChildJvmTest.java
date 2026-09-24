package mtgplayer.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ChildJvm#failureLine} sucht in stderr eines abgestuerzten Kindes die Zeile mit dem Grund.
 * Frueher stand dort stumpf die letzte Zeile - bei einem Stacktrace also ein Rahmen
 * ("at mtgplayer.Main.main"), der nichts sagt (aufgefallen im Sparring-Probelauf am 24.09.).
 */
class ChildJvmTest {

    @TempDir
    Path dir;

    private Path log(String... lines) throws IOException {
        Path p = dir.resolve("stderr-" + lines.length + "-" + System.nanoTime() + ".log");
        Files.write(p, java.util.List.of(lines));
        return p;
    }

    /** Genau der Aufbau aus dem Probelauf: ueberlebte Ausnahmen, dann die toedliche, dann Rahmen. */
    @Test
    void nimmtDieLetzteAusnahmeUndNichtDenStacktraceRahmen() throws IOException {
        Path p = log(
                "java.util.concurrent.TimeoutException",
                "\tat java.base/java.util.concurrent.FutureTask.get(FutureTask.java:204)",
                "\tat forge.ai.AiController.getSpellAbilityToPlay(AiController.java:1583)",
                "main > java.lang.IllegalArgumentException: count cannot be negative but was: -2147483648",
                "\tat forge.game.GameEntity.setCounters(GameEntity.java:327)",
                "\tat mtgplayer.Main.main(Main.java:71)");
        assertEquals("main > java.lang.IllegalArgumentException: count cannot be negative but was: -2147483648",
                ChildJvm.failureLine(p));
    }

    /** In einer Caused-by-Kette ist die letzte Ausnahme die tiefste Ursache - die will man sehen. */
    @Test
    void inEinerCausedByKetteGewinntDieTiefsteUrsache() throws IOException {
        Path p = log(
                "java.lang.IllegalStateException: Partie lieferte keinen Datensatz",
                "\tat mtgplayer.sparring.SparringRun.playOne(SparringRun.java:176)",
                "Caused by: java.lang.NullPointerException: card is null",
                "\tat forge.game.card.Card.getName(Card.java:1)",
                "\t... 12 more");
        assertEquals("Caused by: java.lang.NullPointerException: card is null", ChildJvm.failureLine(p));
    }

    /** Ohne Ausnahme bleibt es beim alten Verhalten: letzte nicht-leere Zeile. */
    @Test
    void ohneAusnahmeDieLetzteNichtLeereZeile() throws IOException {
        Path p = log("Warning: default (ie. inherited from …", "hidden has neither ManaCost nor Color", "", "   ");
        assertEquals("hidden has neither ManaCost nor Color", ChildJvm.failureLine(p));
    }

    /** "ERROR:" aus einem Logger ist keine Ausnahme - sonst gewaenne jede Warnung vor dem Grund. */
    @Test
    void grossgeschriebenesErrorAusEinemLoggerZaehltNicht() throws IOException {
        Path p = log("ERROR: konnte Bild nicht laden", "Spiel beendet");
        assertEquals("Spiel beendet", ChildJvm.failureLine(p));
    }

    @Test
    void keinLogUndLeeresLog() throws IOException {
        assertEquals("(kein Log)", ChildJvm.failureLine(null));
        assertEquals("(leer)", ChildJvm.failureLine(log("", "  ")));
    }
}
