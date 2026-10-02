package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prueft NUR den Uebernahme-Schritt des Skripts ({@code --nur-uebernehmen}): aus einem erfundenen
 * upstream-Stand muss genau das Richtige im Arbeitsbaum landen - neue Dateien, geaenderte Dateien,
 * und Geloeschtes muss verschwinden. Ohne Forge, ohne Netz.
 */
class SyncSkriptTest {

    private static int lauf(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("bash",
                Path.of("..", "scripts", "kartendaten-sync.sh").toAbsolutePath().toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        return p.waitFor();
    }

    /** Ein Mini-Git-Repo mit einem Zweig "upstream-test", der die Kartendaten anders hat. */
    private static Path baueRepo(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        Files.createDirectories(res);
        Files.writeString(res.resolve("sol_ring.txt"), "Name:Sol Ring\n");
        Files.writeString(res.resolve("alt.txt"), "Name:Alte Karte\n");
        git(repo, "init", "-q", "-b", "mtg-player");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "basis");

        git(repo, "checkout", "-q", "-b", "upstream-test");
        Files.writeString(res.resolve("sol_ring.txt"), "Name:Sol Ring\nOracle:neu\n");
        Files.writeString(res.resolve("neu.txt"), "Name:Neue Karte\n");
        Files.delete(res.resolve("alt.txt"));
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "upstream");
        git(repo, "checkout", "-q", "mtg-player");
        return repo;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Files.createDirectories(repo);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void uebernimmtNeueGeaenderteUndGeloeschteDateien(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        assertTrue(Files.exists(res.resolve("neu.txt")), "neue Datei ist da");
        assertFalse(Files.exists(res.resolve("alt.txt")), "geloeschte Datei ist weg");
        assertTrue(Files.readString(res.resolve("sol_ring.txt")).contains("Oracle:neu"),
                "geaenderte Datei wurde uebernommen");
    }

    @Test
    void ausgeschlosseneKartenKommenGarNichtErstHerein(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);
        Path liste = repo.resolve("ausgeschlossen.txt");
        Files.writeString(liste, "Neue Karte\tnicht baubar\t2026-10-02\n");

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test", "--ausschluss", liste.toString()));

        assertFalse(Files.exists(repo.resolve("forge-gui/res/cardsfolder/s/neu.txt")),
                "die ausgeschlossene Karte wurde nicht uebernommen");
    }

    @Test
    void einAusgeschlossenerNameLaesstDieGuteAlteDateiStehen(@TempDir Path tmp) throws Exception {
        // Eintrag "Kartenname in mehr als einer Datei": der Schluessel haengt auch an der alten, guten Datei.
        Path repo = baueRepo(tmp);
        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        git(repo, "checkout", "-q", "upstream-test");
        Files.writeString(res.resolve("sol_ring_zwei.txt"), "Name:Sol Ring\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "dublette");
        git(repo, "checkout", "-q", "mtg-player");
        Path liste = repo.resolve("ausgeschlossen.txt");
        Files.writeString(liste, "Sol Ring\tKartenname in mehr als einer Datei\t2026-10-02\n");

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test", "--ausschluss", liste.toString()));

        assertTrue(Files.readString(res.resolve("sol_ring.txt")).contains("Oracle:neu"),
                "die alte Datei bleibt, in upstreams Fassung");
        assertFalse(Files.exists(res.resolve("sol_ring_zwei.txt")), "nur die neue Zweitdatei fiel");
    }

    @Test
    void unbekannterStandBrichtAbUndLaesstDenBaumUnberuehrt(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "gibt-es-nicht"));

        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        assertTrue(Files.exists(res.resolve("alt.txt")), "nichts wurde geloescht");
        assertFalse(Files.exists(res.resolve("neu.txt")), "nichts wurde uebernommen");
    }

    @Test
    void nichtEingecheckteHandarbeitWirdNichtUeberschrieben(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);
        Path alt = repo.resolve("forge-gui/res/cardsfolder/s/alt.txt");
        Files.writeString(alt, "Name:Alte Karte\nvon Hand geaendert\n");

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        assertTrue(Files.readString(alt).contains("von Hand geaendert"), "Handarbeit ist noch da");
    }

    @Test
    void fehlendesVerzeichnisInUpstreamBrichtAbStattAlleszuLoeschen(@TempDir Path tmp) throws Exception {
        // Bei uns gibt es editions/, upstream hat es nicht mehr (Umbau): nichts anfassen, laut abbrechen.
        Path repo = baueRepo(tmp);
        Path editions = repo.resolve("forge-gui/res/editions");
        git(repo, "checkout", "-q", "mtg-player");
        Files.createDirectories(editions);
        Files.writeString(editions.resolve("A.txt"), "Code=A\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "editions");

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        assertTrue(Files.exists(editions.resolve("A.txt")), "die Editionen sind noch da");
        assertTrue(Files.exists(repo.resolve("forge-gui/res/cardsfolder/s/alt.txt")), "nichts geloescht");
    }
}
