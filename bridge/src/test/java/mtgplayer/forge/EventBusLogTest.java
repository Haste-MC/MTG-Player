package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Luecke, die am 2026-10-06 die Aufklaerung verhindert hat: Guava faengt Ausnahmen seiner
 * Abonnenten ab und schreibt sie ueber {@code java.util.logging} weg - in der Voreinstellung nach
 * stderr, also nur ins Terminal. Dieser Test geht nicht am Logger vorbei, sondern nimmt einen echten
 * {@link EventBus} mit einem werfenden Abonnenten: nur so ist belegt, dass der Handler auch an dem
 * Logger haengt, den Guava wirklich benutzt (sein Name enthaelt die Bus-Kennung).
 */
class EventBusLogTest {

    @TempDir
    Path tmp;

    private final List<String> browser = new ArrayList<>();
    private final AtomicInteger crashes = new AtomicInteger();
    private final Runnable crashListener = crashes::incrementAndGet;

    @BeforeEach
    void redirectCrashLog() {
        CrashLog.setFile(tmp.resolve("logs").resolve("bridge.log"));
        CrashLog.setListener(browser::add);
        CrashLog.addCrashListener(crashListener);
        EventBusLog.install();
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.removeCrashListener(crashListener);
        CrashLog.setListener(null);
        CrashLog.setFile(null);
    }

    private String log() throws Exception {
        Path f = CrashLog.file();
        return Files.exists(f) ? Files.readString(f) : "";
    }

    static final class Werfer {
        @Subscribe
        public void onText(String text) {
            throw new IllegalStateException("kaputter Abonnent: " + text);
        }
    }

    @Test
    void eineAusnahmeAusEinemAbonnentenLandetMitStacktraceImLog() throws Exception {
        EventBus bus = new EventBus("test-bus");
        bus.register(new Werfer());
        bus.post("hallo");

        String log = log();
        assertTrue(log.contains("eventbus"), log);
        assertTrue(log.contains("kaputter Abonnent: hallo"), log);
        assertTrue(log.contains("at mtgplayer.forge.EventBusLogTest$Werfer.onText"),
                "der Stacktrace zeigt den Abonnenten:\n" + log);
    }

    /**
     * Der Spieler erfaehrt es - aber die Partie bleibt in der Wertung. Ein werfender Abonnent bricht
     * das Spiel nicht ab: der Bus laeuft weiter, die uebrigen Abonnenten haben das Ereignis bekommen.
     * Ueber {@link CrashLog#report} gemeldet, stuende "Spiel abgebrochen" im Browser und die Partie
     * waere aus der Wertung - beides unwahr. Genau das hat
     * {@code CardRecordingTest.zeichnetNurDasEigeneDeckAufUndOhneSpielsteine} aufgedeckt.
     */
    @Test
    void derSpielerErfaehrtEsAberDiePartieBleibtInDerWertung() {
        EventBus bus = new EventBus("test-bus");
        bus.register(new Werfer());
        bus.post("hallo");

        assertTrue(browser.stream().anyMatch(s -> s.contains("eventbus")), browser.toString());
        assertFalse(browser.stream().anyMatch(s -> s.contains("Spiel abgebrochen")), browser.toString());
        assertEquals(0, crashes.get(), "kein Absturz - das Spiel laeuft weiter");
    }

    /** Ohne Ausnahme ist es kein Vorfall - Guava loggt z. B. auch Ereignisse ohne Empfaenger. */
    @Test
    void eineMeldungOhneAusnahmeErzeugtKeineZeile() throws Exception {
        Logger.getLogger(EventBus.class.getName() + ".ohne-ausnahme")
                .log(Level.SEVERE, "nur eine Meldung, kein Absturz");
        assertFalse(log().contains("nur eine Meldung"), log());
        assertTrue(browser.isEmpty(), browser.toString());
    }
}
