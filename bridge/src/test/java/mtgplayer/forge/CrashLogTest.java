package mtgplayer.forge;

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
}
