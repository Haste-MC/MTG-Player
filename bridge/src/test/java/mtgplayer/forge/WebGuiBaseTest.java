package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ueber {@code invokeInEdtLater} laeuft Forges gesamte Zustandsverarbeitung, einschliesslich
 * {@code finishGame()}. Bis 2026-10-07 landete eine Ausnahme von dort nur auf stderr - der Tisch im
 * Browser stand, {@code bridge.log} blieb leer. Diese Tests nageln fest, dass sie jetzt ankommt.
 */
class WebGuiBaseTest {

    @TempDir
    Path tmp;

    /** Nie in die echte {@code bridge.log}; der Zaehler loest die Wartezeit auf statt eines sleep. */
    private final CountDownLatch reported = new CountDownLatch(1);
    private volatile String browserLine;

    @BeforeEach
    void redirectCrashLog() {
        CrashLog.setFile(tmp.resolve("logs").resolve("bridge.log"));
        CrashLog.setListener(line -> {
            browserLine = line;
            reported.countDown();
        });
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setListener(null);
        CrashLog.setFile(null);
    }

    private WebGuiBase gui() {
        return new WebGuiBase(tmp.resolve("assets").toString() + "/");
    }

    @Test
    void eineAusnahmeAufDemUiThreadLandetImLogUndImBrowser() throws Exception {
        gui().invokeInEdtLater(() -> {
            throw new IllegalStateException("UI-Thread kaputt");
        });

        assertTrue(reported.await(5, TimeUnit.SECONDS), "nichts gemeldet");
        assertTrue(browserLine.contains("Spiel abgebrochen (uncaught in bridge-ui)"), browserLine);
        String log = Files.readString(CrashLog.file());
        assertTrue(log.contains("UI-Thread kaputt"), log);
        assertTrue(log.contains("at mtgplayer.forge.WebGuiBaseTest"), "Stacktrace fehlt:\n" + log);
    }

    /** {@code Throwable}, nicht {@code Exception}: ein {@code StackOverflowError} aus einer
     *  Forge-Rekursion ist genau der Fall, der nicht lautlos verschwinden darf. */
    @Test
    void auchEinErrorWirdGemeldet() throws Exception {
        gui().invokeInEdtLater(() -> {
            throw new LinkageError("kein Exception, trotzdem weg");
        });

        assertTrue(reported.await(5, TimeUnit.SECONDS), "nichts gemeldet");
        assertTrue(Files.readString(CrashLog.file()).contains("kein Exception, trotzdem weg"));
    }

    /** Der UI-Thread darf an einer Panne nicht sterben - sonst steht der Tisch ab dann wirklich. */
    @Test
    void nachEinerAusnahmeLaeuftDerUiThreadWeiter() throws Exception {
        WebGuiBase gui = gui();
        CountDownLatch zweite = new CountDownLatch(1);
        gui.invokeInEdtLater(() -> {
            throw new IllegalStateException("erste Aufgabe");
        });
        gui.invokeInEdtLater(zweite::countDown);

        assertTrue(zweite.await(5, TimeUnit.SECONDS), "die zweite Aufgabe kam nicht mehr durch");
    }
}
