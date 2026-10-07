package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Der Abzug ist ein Beweismittel - er muss auch dann etwas sagen, wenn der interessanteste Thread
 * schon tot ist. Genau daran scheiterte der Wachhund am 2026-10-06 (siehe {@link ThreadDump}).
 *
 * <p>Zusicherungen ueber Reihenfolge und Budget laufen ueber
 * {@link ThreadDump#of(Thread, Map)} mit selbst gebauter Thread-Liste: in der laufenden Test-JVM leben
 * fremde Threads mit (Forges {@code Game-*}-Pool, ein {@code bridge-ui} aus
 * {@link WebGuiBaseTest}), und gegen {@link Thread#getAllStackTraces()} waere jede Aussage ueber "wer
 * faellt aus dem Budget" vom Testlauf abhaengig. Dieser Fehler ist in der vollen Suite wirklich
 * passiert, nicht ausgedacht.</p>
 *
 * <p>Threads werden an einem {@link CountDownLatch} geparkt statt mit {@code sleep} - {@link #parked}
 * wartet, bis der Thread wirklich wartet, und gibt ihn im {@code finally} frei.</p>
 */
class ThreadDumpTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final List<Thread> parked = new ArrayList<>();

    /** Startet einen Thread mit diesem Namen und kehrt erst zurueck, wenn er wirklich wartet. */
    private Thread parked(String name) {
        Thread t = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        t.setDaemon(true);
        t.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, t.getState(), "Thread " + name + " wartet nicht");
        parked.add(t);
        return t;
    }

    private void freeAll() throws InterruptedException {
        release.countDown();
        for (Thread t : parked) {
            t.join(5000);
        }
    }

    /** Genau die Threads, die der Testfall gestartet hat - plus optional der gemerkte. */
    private Map<Thread, StackTraceElement[]> onlyMine(Thread... extra) {
        Map<Thread, StackTraceElement[]> map = new LinkedHashMap<>();
        for (Thread t : extra) {
            map.put(t, t.getStackTrace());
        }
        for (Thread t : parked) {
            map.put(t, t.getStackTrace());
        }
        return map;
    }

    // ---------------------------------------------------------------- gegen die echte JVM

    @Test
    void ohneBekanntenSpielThreadStehtDerVermerkTrotzdemDa() {
        assertTrue(ThreadDump.of(null).contains("(Spiel-Thread unbekannt)"));
    }

    @Test
    void derGemerkteThreadKommtMitNamenZustandUndRahmen() {
        String dump = ThreadDump.of(Thread.currentThread());
        assertTrue(dump.startsWith("Spiel-Thread \"" + Thread.currentThread().getName() + "\" ("), dump);
        // Ohne "at " davor: aus Thread.getAllStackTraces() tragen die Rahmen den Namen des
        // Klassenladers ("at app//mtgplayer..."), aus Thread.getStackTrace() nicht.
        assertTrue(dump.contains("mtgplayer.forge.ThreadDumpTest"), "eigene Rahmen fehlen:\n" + dump);
    }

    @Test
    void ohneVerklemmungStehtKeineImAbzug() {
        assertFalse(ThreadDump.of(null).contains("VERKLEMMUNG"), ThreadDump.of(null));
    }

    /**
     * Der Fall vom 2026-10-06: der Spiel-Thread ist tot, hat also keinen Stack. Die Kopfzeile mit
     * {@code TERMINATED} IST die Nachricht - und der Grund, warum der Abzug noch andere Threads braucht.
     */
    @Test
    void einToterThreadLiefertSeinenZustandUndKeineRahmen() throws Exception {
        Thread dead = new Thread(() -> { }, "Game-tot");
        dead.start();
        dead.join(5000);
        String dump = ThreadDump.of(dead, onlyMine());
        assertEquals("Spiel-Thread \"Game-tot\" (TERMINATED)", dump);
    }

    // ---------------------------------------------------------------- mit eigener Thread-Liste

    @Test
    void derUiThreadKommtMitAuchWennErNichtDerGemerkteIst() throws Exception {
        try {
            parked("bridge-ui");
            String dump = ThreadDump.of(Thread.currentThread(), onlyMine(Thread.currentThread()));
            assertTrue(dump.contains("Thread \"bridge-ui\" (WAITING)"), dump);
            assertTrue(dump.contains("ThreadDumpTest.lambda"), "Stack des UI-Threads fehlt:\n" + dump);
        } finally {
            freeAll();
        }
    }

    @Test
    void auchDerSpielThreadPoolUndHintergrundAufgabenKommenMit() throws Exception {
        try {
            parked("Game-7");
            parked("bridge-bg");
            String dump = ThreadDump.of(null, onlyMine());
            assertTrue(dump.contains("Thread \"Game-7\""), dump);
            assertTrue(dump.contains("Thread \"bridge-bg\""), dump);
        } finally {
            freeAll();
        }
    }

    /** Die Rechen-Threads der KI sind Dutzende und stehen im Stack des Spiel-Threads selbst. */
    @Test
    void rechenThreadsDerKiBleibenDraussen() throws Exception {
        try {
            Thread worker = parked("ForkJoinPool.commonPool-worker-3");
            assertFalse(ThreadDump.interesting(worker), "Rechen-Thread gehoert nicht in den Abzug");
            String dump = ThreadDump.of(null, onlyMine());
            assertFalse(dump.contains("commonPool-worker-3"), dump);
        } finally {
            freeAll();
        }
    }

    @Test
    void mehrAlsDasThreadBudgetWirdVermerktStattAbgeschnitten() throws Exception {
        try {
            for (int i = 0; i < ThreadDump.MAX_THREADS + 2; i++) {
                parked("Game-" + i);
            }
            String dump = ThreadDump.of(null, onlyMine());
            assertTrue(dump.contains("weitere Threads)"), dump);
            assertEquals(ThreadDump.MAX_THREADS - 1, count(dump, "\nThread \""),
                    "hoechstens " + (ThreadDump.MAX_THREADS - 1) + " neben dem gemerkten:\n" + dump);
        } finally {
            freeAll();
        }
    }

    /**
     * Der Fehler, den die volle Suite gefunden hat: bei alphabetischer Ordnung fiel ausgerechnet
     * {@code bridge-ui} aus dem Budget, sobald Forges Pool genug {@code Game-*}-Threden hielt - der
     * Thread, dessen Stack die Frage beantwortet, warum der Tisch steht.
     */
    @Test
    void beiVollemBudgetBleibtDerUiThreadDrin() throws Exception {
        try {
            for (int i = 0; i < ThreadDump.MAX_THREADS + 2; i++) {
                parked("Game-" + i);
            }
            parked("bridge-ui");
            String dump = ThreadDump.of(null, onlyMine());
            assertTrue(dump.contains("Thread \"bridge-ui\""), dump);
            assertTrue(dump.indexOf("bridge-ui") < dump.indexOf("Game-"), "und zwar zuerst:\n" + dump);
        } finally {
            freeAll();
        }
    }

    /** Eine Endlosrekursion darf das Log nicht fluten - der Rest wird gezaehlt, nicht geschrieben. */
    @Test
    void tiefeStacksWerdenGekuerzt() {
        String dump = dumpAtDepth(ThreadDump.MAX_FRAMES + 5);
        assertTrue(dump.contains("weitere Rahmen)"), dump);
        assertEquals(ThreadDump.MAX_FRAMES, count(dump, "\n\tat "),
                "genau " + ThreadDump.MAX_FRAMES + " Rahmen, dann der Vermerk:\n" + dump);
    }

    private String dumpAtDepth(int depth) {
        if (depth <= 0) {
            return ThreadDump.of(Thread.currentThread(), onlyMine(Thread.currentThread()));
        }
        return dumpAtDepth(depth - 1);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}
