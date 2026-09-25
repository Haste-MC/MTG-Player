package mtgplayer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppModeTest {

    // Ports ab 18100: Kevins laufende Entwicklungs-Bridge haengt an 8080/8081, die duerfen diese
    // Tests nie anfassen.
    private static final int WUNSCHPORT = 18100;

    @TempDir
    Path tmp;

    @Test
    void freePortLiefertWunschportWennErFreiIst() throws IOException {
        int port = AppMode.freePort(WUNSCHPORT);
        assertEquals(WUNSCHPORT, port);
    }

    @Test
    void freePortWeichtAufAnderenFreienPortAusWennWunschportBelegtIst() throws IOException {
        int belegterPort = WUNSCHPORT + 1;
        try (ServerSocket blockiert = new ServerSocket(belegterPort)) {
            int port = AppMode.freePort(belegterPort);
            assertNotEquals(belegterPort, port, "sollte nicht denselben belegten Port zurueckgeben");
            assertTrue(port > 0, "sollte einen echten Port liefern");
        }
    }

    @Test
    void writableOrNullLiefertNullFuerBeschreibbaresVerzeichnis() {
        assertNull(AppMode.writableOrNull(tmp));
    }

    /**
     * Nicht-beschreibbares Verzeichnis wird via POSIX-Rechten simuliert (r-x, kein w). Laeuft der
     * Test als root oder auf einem Nicht-POSIX-Dateisystem, greift der Schreibschutz nicht - dann
     * wird der Test uebersprungen statt mit einem schwaecheren Ersatz weichgeklopft.
     */
    @Test
    void writableOrNullLiefertErklaerendenTextFuerNichtBeschreibbaresVerzeichnis() throws IOException {
        Path gesperrt = tmp.resolve("gesperrt");
        Files.createDirectory(gesperrt);
        Assumptions.assumeTrue(gesperrt.getFileSystem().supportedFileAttributeViews().contains("posix"),
                "kein POSIX-Dateisystem - Schreibschutz nicht simulierbar");
        Files.setPosixFilePermissions(gesperrt, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            boolean schreibschutzWirkt;
            try {
                Files.delete(Files.createTempFile(gesperrt, "probe", null));
                schreibschutzWirkt = false;
            } catch (IOException erwartet) {
                schreibschutzWirkt = true;
            }
            Assumptions.assumeTrue(schreibschutzWirkt,
                    "Verzeichnisrechte wirkungslos auf diesem System (z.B. root) - Test uebersprungen");

            String meldung = AppMode.writableOrNull(gesperrt);
            assertNotNull(meldung, "nicht beschreibbares Verzeichnis sollte einen Text liefern");
            assertTrue(meldung.contains("entpacken"), meldung);
        } finally {
            // @TempDir muss das Verzeichnis nach dem Test wieder loeschen koennen duerfen.
            Files.setPosixFilePermissions(gesperrt, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /**
     * openWindow selbst startet keinen Browser im Test (kein Windows, kein Edge hier) - nur die
     * Kommandozeilen-Bildung wird als reine Funktion geprueft.
     */
    @Test
    void windowCommandBautKommandozeileAusBrowserpfadUndUrl() {
        Path browser = Path.of("C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe");
        List<String> cmd = AppMode.windowCommand(browser, "http://localhost:18100");
        assertEquals(List.of(browser.toString(), "--app=http://localhost:18100"), cmd);
    }
}
