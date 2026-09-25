package mtgplayer.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 4 (App-Paket): eigene Fassung kennen, Fassungen vergleichen. Reine Funktionen - kein Netz,
 *  kein Schreiben nach {@code ~/.mtg-player}. */
class VersionTest {

    @Test
    void groessereNebenversionIstNeuer() {
        assertTrue(Version.isNewer("1.3.0", "1.2.9"));
        assertFalse(Version.isNewer("1.2.9", "1.3.0"));
    }

    @Test
    void zehnIstNeuerAlsNeunZahlenNichtTextVergleichen() {
        // Als Text waere "1.9.0" > "1.10.0" (Ziffer '9' > '1') - falsch. Es zaehlt die Zahl.
        assertTrue(Version.isNewer("1.10.0", "1.9.0"));
        assertFalse(Version.isNewer("1.9.0", "1.10.0"));
    }

    @Test
    void gleicheFassungIstNichtNeuer() {
        assertFalse(Version.isNewer("1.2.0", "1.2.0"));
        assertFalse(Version.isNewer("1.2", "1.2.0"));
    }

    @Test
    void devIstNieAelterBekommtKeinenUpdateHinweis() {
        // Egal wie hoch der Tag ist: die eigene Entwicklungsfassung "dev" darf nie als veraltet gelten -
        // sonst bietet die Arbeitskopie sich staendig selbst als "Update" an.
        assertFalse(Version.isNewer("1.0.0", Version.DEV));
        assertFalse(Version.isNewer("99.0.0", Version.DEV));
        // Umgekehrt ist "dev" selbst keine vergleichbare Fassung - auch das darf nie "neuer" sein.
        assertFalse(Version.isNewer(Version.DEV, "1.0.0"));
    }

    @Test
    void murksFuehrtZuKeinUpdateNieZuEinerAusnahme() {
        assertFalse(Version.isNewer("", "1.0.0"));
        assertFalse(Version.isNewer("1.0.0", ""));
        assertFalse(Version.isNewer(null, "1.0.0"));
        assertFalse(Version.isNewer("1.0.0", null));
        assertFalse(Version.isNewer(null, null));
        // "v1.2": das "v" macht es zu Text, nicht zu einer Zahl - genau die Form, die GitHub-Tags
        // tragen (UpdateCheck zieht das "v" vorher selbst ab, siehe UpdateCheckTest).
        assertFalse(Version.isNewer("v1.2", "1.0.0"));
        assertFalse(Version.isNewer("1.0.0", "v1.2"));
    }

    @Test
    void currentOhneVersionDateiUndOhneGepackteJarIstDev() {
        // Testumgebung: kein version.txt neben "assets", keine gepackte Jar mit Implementation-Version
        // im Manifest (die Tests laufen aus target/test-classes) - genau der Zustand von Kevins
        // laufender Entwicklungs-Bridge und jedem Worktree.
        assertEquals("dev", Version.current());
    }

    @Test
    void currentLiestVersionTxtAusDemUebergebenenAppOrdner(@TempDir Path appDir) throws IOException {
        Files.writeString(appDir.resolve("version.txt"), "1.4.2\n");
        assertEquals("1.4.2", Version.current(appDir));
    }

    @Test
    void currentOhneVersionTxtFaelltAufDevZurueck(@TempDir Path leererOrdner) {
        assertEquals("dev", Version.current(leererOrdner));
    }

    @Test
    void currentOhneAppOrdnerWirftNicht() {
        assertEquals("dev", Version.current(null));
    }
}
