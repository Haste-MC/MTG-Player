package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
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

    /**
     * Forge liest {@code forge.profile.properties} mit {@code Properties.load(InputStream)}
     * ({@code ForgeProfileProperties.load}) - dort ist der Backslash das FLUCHTZEICHEN, und der Strom wird
     * als ISO-8859-1 mit Unicode-Escapes gelesen. Ein Windows-Pfad, roh in die Datei geschrieben,
     * kommt deshalb zerstoert wieder heraus: aus {@code C:\Users\kevin_\.mtg-player\forge} wurde
     * {@code C:Userskevin_.mtg-playerorge} (jeder Backslash verschluckt, {@code \f} sogar zum
     * Seitenvorschub) - Forge legte sein Profil daraufhin im App-Ordner an, scheiterte und die App
     * startete nie. Ein Benutzername mit Umlaut traf dieselbe Datei ueber die Kodierung (wir schrieben
     * UTF-8, gelesen wird ISO-8859-1).
     *
     * <p>Der Test laeuft auf JEDER Plattform: unter Linux ist der Backslash ein ganz gewoehnliches
     * Zeichen im Dateinamen, der Windows-Pfad hier also eine voellig normale Zeichenkette - genau
     * deshalb faellt der Fehler auch auf dem Entwicklungsrechner auf und nicht erst beim Nutzer.</p>
     */
    @Test
    void profildateiUeberstehtWindowsPfadUndUmlaut(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("forge.profile.properties");
        Path user = Paths.get("C:\\Users\\Kevin M\u00fcller\\.mtg-player\\forge");
        Path cache = Paths.get("C:\\Users\\Kevin M\u00fcller\\.mtg-player\\cache");

        ForgeBoot.writeProfileFile(datei, user, cache);

        Properties gelesen = new Properties();
        try (InputStream in = Files.newInputStream(datei)) {
            gelesen.load(in);   // genau so liest Forge die Datei
        }
        assertEquals(user + "/", gelesen.getProperty("userDir"), "userDir kommt unveraendert zurueck");
        assertEquals(cache + "/", gelesen.getProperty("cacheDir"), "cacheDir kommt unveraendert zurueck");
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
