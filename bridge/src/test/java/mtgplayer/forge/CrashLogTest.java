package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashLogTest {

    @TempDir
    Path tmp;

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} des Nutzers schreiben. */
    @BeforeEach
    void redirect() {
        CrashLog.setFile(tmp.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void reset() {
        CrashLog.setListener(null);
        CrashLog.setFile(null);
    }

    @Test
    void schreibtDateiUndMeldetDemListener() throws Exception {
        List<String> seen = new ArrayList<>();
        CrashLog.setListener(seen::add);
        CrashLog.report("uncaught in Game-1", null, new IllegalStateException("Phage-Test"));
        String log = Files.readString(CrashLog.file(), StandardCharsets.UTF_8);
        assertTrue(log.contains("uncaught in Game-1"), log);
        assertTrue(log.contains("IllegalStateException: Phage-Test"), log);
        assertTrue(log.contains("at mtgplayer.forge.CrashLogTest"), "stacktrace fehlt: " + log);
        assertTrue(seen.size() == 1 && seen.get(0).startsWith("Spiel abgebrochen (uncaught in Game-1): IllegalStateException: Phage-Test"), seen.toString());
    }

    /**
     * Der Unterschied, auf den es ankommt: {@code warn} meldet dem Browser, {@code note} nur der Datei.
     * Der Wachhund der Denk-Anzeige schreibt mit {@code note} - eine lange KI-Rechnung ist normal und
     * darf keine Warnung im Browser-Log erzeugen.
     */
    @Test
    void noteSchreibtNurInsLogWarnMeldetZusaetzlichDemListener() throws Exception {
        List<String> seen = new ArrayList<>();
        CrashLog.setListener(seen::add);

        CrashLog.note("Wachhund", "130 s ohne Fortschritt");
        assertEquals(List.of(), seen, "note() darf den Browser nicht behelligen");
        assertTrue(Files.readString(CrashLog.file(), StandardCharsets.UTF_8).contains("Wachhund: 130 s ohne Fortschritt"),
                "note() muss trotzdem im Log stehen");

        CrashLog.warn("matches.json", "kaputt");
        assertEquals(1, seen.size(), seen.toString());
        assertTrue(seen.get(0).startsWith("matches.json: kaputt"), seen.toString());
    }
}
