package mtgplayer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Die Schonfrist ist hier absichtlich winzig (Millisekunden statt Sekunden) - geprueft wird die REGEL,
 * nicht die im Betrieb gewaehlte Dauer.
 */
class IdleExitTest {

    /** Frist fuers erste Fenster - in den Tests, die sie nicht pruefen, absichtlich unerreichbar lang. */
    private static final Duration EGAL = Duration.ofHours(1);

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stopTimer() {
        timer.shutdownNow();
    }

    @Test
    void ohneRueckkehrWirdBeendet() throws Exception {
        CountDownLatch beendet = new CountDownLatch(1);
        IdleExit idle = new IdleExit(Duration.ofMillis(30), EGAL, beendet::countDown, timer);

        idle.clientGone();

        assertTrue(beendet.await(5, TimeUnit.SECONDS), "nach der Schonfrist ohne Klient wird beendet");
    }

    @Test
    void werZurueckkommtSagtDenAbbruchAb() throws Exception {
        AtomicInteger beendet = new AtomicInteger();
        IdleExit idle = new IdleExit(Duration.ofMillis(60), EGAL, beendet::incrementAndGet, timer);

        idle.clientGone();
        idle.clientHere();   // Seite neu geladen, bevor die Schonfrist um ist

        Thread.sleep(300);   // deutlich laenger als die Schonfrist
        assertEquals(0, beendet.get(), "ein zurueckgekehrter Klient verhindert das Beenden");
    }

    /**
     * Mehrere Trennungen hintereinander (mehrere Tabs offen gewesen) duerfen sich nicht zu mehreren
     * Abbruechen stapeln - sonst laeuft der Abbruch schon, waehrend laengst wieder jemand da ist.
     */
    @Test
    void mehrfachesTrennenStapeltNicht() throws Exception {
        AtomicInteger beendet = new AtomicInteger();
        IdleExit idle = new IdleExit(Duration.ofMillis(60), EGAL, beendet::incrementAndGet, timer);

        idle.clientGone();
        idle.clientGone();
        idle.clientGone();
        idle.clientHere();

        Thread.sleep(300);
        assertEquals(0, beendet.get(), "die letzte Ansage gilt, nicht die erste");

        idle.clientGone();
        Thread.sleep(300);
        assertEquals(1, beendet.get(), "danach beendet es genau einmal");
    }

    /**
     * Kommt nach dem Start ueberhaupt kein Fenster (Browser ging nicht auf, oder die Seite sprach mit
     * einer fremden Bridge), soll die App trotzdem nicht ewig im Hintergrund stehen bleiben.
     */
    @Test
    void ohneErstenKlientenWirdAuchBeendet() throws Exception {
        CountDownLatch beendet = new CountDownLatch(1);
        IdleExit idle = new IdleExit(Duration.ofHours(1), Duration.ofMillis(30), beendet::countDown, timer);

        idle.awaitFirstClient();

        assertTrue(beendet.await(5, TimeUnit.SECONDS), "ohne erstes Fenster beendet sich die App");
    }

    /** Kommt das Fenster rechtzeitig, gilt die Start-Frist nicht mehr. */
    @Test
    void ersterKlientSagtDieStartFristAb() throws Exception {
        AtomicInteger beendet = new AtomicInteger();
        IdleExit idle = new IdleExit(Duration.ofHours(1), Duration.ofMillis(60), beendet::incrementAndGet, timer);

        idle.awaitFirstClient();
        idle.clientHere();

        Thread.sleep(300);
        assertEquals(0, beendet.get(), "ein verbundenes Fenster verhindert das Beenden");
    }

    @Test
    void clientHereOhneVorherigesTrennenIstHarmlos() {
        AtomicInteger beendet = new AtomicInteger();
        IdleExit idle = new IdleExit(Duration.ofMillis(30), EGAL, beendet::incrementAndGet, timer);

        idle.clientHere();

        assertFalse(beendet.get() > 0, "ohne vorheriges Trennen passiert nichts");
    }
}
