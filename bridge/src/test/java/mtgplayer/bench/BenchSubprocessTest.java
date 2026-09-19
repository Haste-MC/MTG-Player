package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deckt {@link SubprocessRunner} ab: ein echtes Spiel in einem JVM-Kindprozess (Classpath- und
 * java-Binary-Aufloesung laufen hier unter dem normalen Surefire-Testklassenpfad, nicht unter
 * {@code exec:java} - siehe {@code .superpowers/sdd/bench-subprocess-report.md} fuer beide Faelle) und
 * den Absturzpfad, wenn der Kindprozess selbst scheitert.
 */
class BenchSubprocessTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void spielImKindprozessLiefertErgebnis(@TempDir Path tmp) {
        BenchArgs args = new BenchArgs(1, AiConfig.parse("std:Default"), AiConfig.parse("std:Default"),
                "precon:Abzan Armor [TDC] [2025]", "precon:Adaptive Enchantment [C18] [2018]",
                10, 2, System.currentTimeMillis(), tmp, 3, false);
        SubprocessRunner runner = new SubprocessRunner(new PrintStream(OutputStream.nullOutputStream()),
                args.gameTimeoutMinutes());

        GameRecord record = runner.play(args, 0);

        assertFalse(record.crashed(), "erwarte kein Absturz, war: " + record.reason());
        assertTrue(record.turns() > 0, "erwarte mindestens einen Zug, war: " + record.turns());
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void kindprozessAbsturzBeiUnbekanntemDeckWirdAlsCrashGemeldet(@TempDir Path tmp) {
        BenchArgs args = new BenchArgs(1, AiConfig.parse("std:Default"), AiConfig.parse("std:Default"),
                "precon:Gibt Es Nicht", "precon:Adaptive Enchantment [C18] [2018]",
                10, 2, 1, tmp, 3, false);
        SubprocessRunner runner = new SubprocessRunner(new PrintStream(OutputStream.nullOutputStream()),
                args.gameTimeoutMinutes());

        GameRecord record = runner.play(args, 0);

        assertTrue(record.crashed(), "erwarte Absturz, war: " + record);
        assertTrue(record.reason().contains("Kindprozess"),
                "Grund sollte 'Kindprozess' enthalten, war: " + record.reason());
    }
}
