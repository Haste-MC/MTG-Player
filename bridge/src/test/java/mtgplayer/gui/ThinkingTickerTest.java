package mtgplayer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.CrashLog;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Der Ticker taktet im Betrieb ueber einen eigenen Daemon-Thread, im Test dagegen ruft der Test
 * {@link ThinkingTicker#tick()} selbst auf und dreht die Uhr ({@code long[] now} hinter dem
 * {@code LongSupplier}) - so wartet kein Test eine Sekunde, geschweige denn 120. Scharf geschaltet
 * wird ueber {@link ThinkingTicker#arm()} statt {@code start()}, damit KEIN zweiter Taktgeber
 * nebenher in dieselbe Liste schreibt.
 */
class ThinkingTickerTest {

    @TempDir
    Path tmp;

    private final List<Object> sent = new ArrayList<>();
    private final long[] now = {0};
    private Integer priority = 2;
    private Thread gameThread;

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} schreiben. */
    @BeforeEach
    void redirectCrashLog() {
        CrashLog.setFile(tmp.resolve("logs").resolve("bridge.log"));
        CrashLog.setListener(null);
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
        CrashLog.setListener(null);
    }

    private ThinkingTicker ticker() {
        return new ThinkingTicker(sent::add, () -> now[0], () -> priority,
                () -> "Prioritaet: KI 2, Phase: MAIN1, Zug 14", () -> gameThread);
    }

    private void advance(int seconds) {
        now[0] += seconds * 1_000_000_000L;
    }

    private List<Messages.Thinking> thinking() {
        return sent.stream().map(Messages.Thinking.class::cast).toList();
    }

    private String log() throws Exception {
        Path f = CrashLog.file();
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- Anzeige

    @Test
    void abDreiSekundenStilleMeldetJederTaktEineWachsendeZahl() {
        ThinkingTicker t = ticker();
        t.arm();

        advance(1);
        t.tick();
        advance(1);
        t.tick();
        assertEquals(List.of(), thinking(), "unter 3 s Stille bleibt es still");

        advance(1);
        t.tick();                                    // 3 s
        advance(1);
        t.tick();                                    // 4 s
        advance(1);
        t.tick();                                    // 5 s

        assertEquals(List.of(new Messages.Thinking(2, 3), new Messages.Thinking(2, 4),
                new Messages.Thinking(2, 5)), thinking());
        assertEquals("thinking", thinking().get(0).type());
    }

    @Test
    void ohnePrioritaetssitzGehtDieMeldungOhneSpielerRaus() {
        priority = null;
        ThinkingTicker t = ticker();
        t.arm();

        advance(3);
        t.tick();

        assertEquals(1, thinking().size());
        assertNull(thinking().get(0).player());
        assertEquals(3, thinking().get(0).seconds());
    }

    @Test
    void touchBeendetDieAnzeigeGenauEinmal() {
        ThinkingTicker t = ticker();
        t.arm();

        advance(4);
        t.tick();                                    // "denkt seit 4 s"
        t.touch();                                   // Log-Zeile / Zustands-Push
        t.tick();
        t.tick();
        t.tick();

        assertEquals(List.of(new Messages.Thinking(2, 4), new Messages.Thinking(null, 0)), thinking(),
                "genau eine Abschlussmeldung, danach nichts bis zur naechsten Stille");

        advance(3);
        t.tick();
        assertEquals(new Messages.Thinking(2, 3), thinking().get(2), "die naechste Stille meldet wieder");
    }

    @Test
    void ohneLaufendeAnzeigeMeldetTouchNichts() {
        ThinkingTicker t = ticker();
        t.arm();

        advance(2);
        t.touch();
        t.tick();
        t.tick();

        assertEquals(List.of(), thinking(), "es lief keine Anzeige, also gibt es auch nichts zu beenden");
    }

    @Test
    void closeBeendetDieLaufendeAnzeige() {
        ThinkingTicker t = ticker();
        t.arm();

        advance(5);
        t.tick();
        t.close();

        assertEquals(List.of(new Messages.Thinking(2, 5), new Messages.Thinking(null, 0)), thinking());

        advance(120);
        t.tick();
        assertEquals(2, thinking().size(), "nach close() taktet nichts mehr");
    }

    // ---------------------------------------------------------------- Wachhund

    @Test
    void wachhundMeldetJeVorfallGenauEinmal() throws Exception {
        gameThread = Thread.currentThread();
        ThinkingTicker t = ticker();
        t.arm();

        advance(119);
        t.tick();
        assertEquals("", log(), "unter 120 s meldet der Wachhund nicht");

        advance(1);
        t.tick();                                    // 120 s
        advance(1);
        t.tick();
        advance(60);
        t.tick();

        String first = log();
        assertEquals(1, count(first, "Wachhund:"), "ein Vorfall, eine Zeile:\n" + first);
        assertTrue(first.contains("s ohne Fortschritt"), first);
        assertTrue(first.contains("Prioritaet: KI 2, Phase: MAIN1, Zug 14"), first);
        assertTrue(first.contains("at mtgplayer.gui.ThinkingTickerTest"), "Stacktrace des Spiel-Threads fehlt:\n" + first);

        t.touch();
        t.tick();
        advance(120);
        t.tick();

        assertEquals(2, count(log(), "Wachhund:"), "nach Aktivitaet zaehlt die Stille neu:\n" + log());
    }

    @Test
    void ohneBekanntenSpielThreadVermerktDerWachhundDas() throws Exception {
        gameThread = null;
        ThinkingTicker t = ticker();
        t.arm();

        advance(120);
        t.tick();

        String log = log();
        assertEquals(1, count(log, "Wachhund:"), log);
        assertTrue(log.contains("(Spiel-Thread unbekannt)"), log);
    }

    // ---------------------------------------------------------------- kein Spiel

    @Test
    void ohneLaufendesSpielPassiertNichts() throws Exception {
        ThinkingTicker t = ticker();                 // weder start() noch arm()

        advance(200);
        t.tick();
        t.touch();
        advance(200);
        t.tick();

        assertEquals(List.of(), thinking(), "ohne laufendes Spiel geht nichts raus");
        assertEquals("", log(), "und der Wachhund schweigt");
    }
}
