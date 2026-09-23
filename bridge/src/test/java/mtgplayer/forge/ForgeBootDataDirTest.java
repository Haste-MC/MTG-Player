package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code ForgeBoot.dataDir()} und {@code CrashLog.file()} duerfen sich per {@code -Dmtgplayer.data}
 * umlenken lassen, ohne dass {@link ForgeBoot#init()} laeuft – genau das nutzt {@code bridge/pom.xml},
 * damit Testlaeufe nicht Kevins {@code ~/.mtg-player} anfassen.
 *
 * <p>Wichtig: {@code bridge/pom.xml} setzt {@code mtgplayer.data} selbst fuer jeden Testlauf (auf
 * {@code target/test-data}) - die Property ist hier also so gut wie nie wirklich unbenutzt. Beide Tests
 * merken sich deshalb den beim Start vorgefundenen Wert und stellen genau ihn im {@code finally} wieder
 * her, statt die Property einfach zu loeschen: ein blosses {@code clearProperty} wuerde sonst der ganzen
 * restlichen Testklasse (gleicher, von Surefire wiederverwendeter JVM-Fork) die Isolation wegnehmen -
 * genau das Problem, das dieser Task beheben soll (beobachtet: dadurch schrieb ein spaeter laufender
 * Bench-Subprocess-Test wieder in Kevins echtes {@code ~/.mtg-player/logs/bridge.log}).</p>
 */
class ForgeBootDataDirTest {

    @Test
    void dataDirFolgtSystemProperty(@TempDir Path tmp) {
        String vorher = System.getProperty("mtgplayer.data");
        try {
            System.clearProperty("mtgplayer.data");
            Path erwartet = Paths.get(System.getProperty("user.home"), ".mtg-player");
            assertEquals(erwartet, ForgeBoot.dataDir(), "ohne Property bleibt es beim bisherigen Default");

            System.setProperty("mtgplayer.data", tmp.toString());
            assertEquals(tmp, ForgeBoot.dataDir(), "mit Property zeigt dataDir() auf sie");

            System.clearProperty("mtgplayer.data");
            assertEquals(erwartet, ForgeBoot.dataDir(), "nach dem Entfernen wieder der Default");
        } finally {
            restoreProperty(vorher);
        }
    }

    @Test
    void crashLogFileLiegtUnterGesetztemDatenverzeichnis(@TempDir Path tmp) {
        String vorher = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.data", tmp.toString());
        try {
            assertTrue(CrashLog.file().startsWith(tmp), "CrashLog.file() ohne setFile() muss unter mtgplayer.data liegen: " + CrashLog.file());
        } finally {
            restoreProperty(vorher);
        }
    }

    /** {@code null} = beim Start war keine Property gesetzt, sonst der Wert, den z. B. {@code bridge/pom.xml}
     *  vorgegeben hat - in beiden Faellen der Zustand, in dem die naechsten Tests diesen Fork vorfinden sollen. */
    private static void restoreProperty(String vorher) {
        if (vorher == null) {
            System.clearProperty("mtgplayer.data");
        } else {
            System.setProperty("mtgplayer.data", vorher);
        }
    }
}
