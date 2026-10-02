package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Laeuft im KINDPROZESS wie {@code TrimmedResTest}: der Pruefmodus faehrt Forge hoch, und Forges
 * statische Initialisierung laesst sich in derselben JVM kein zweites Mal auf ein anderes
 * {@code res}-Verzeichnis richten.
 */
class CardDataCheckTest {

    private static JsonNode befund(Path tmp, Path res) {
        String vorherAssets = System.getProperty("mtgplayer.assets");
        String vorherData = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.assets", res.toString());
        System.setProperty("mtgplayer.data", tmp.resolve("child-data").toString());
        try {
            Path stderr = tmp.resolve("pruefen-stderr.log");
            ChildJvm.Result r = ChildJvm.run(List.of("--kartendaten-pruefen"), "KARTENDATEN_BEFUND ",
                    stderr, line -> { }, 15, TimeUnit.MINUTES, null);
            if (!r.ok()) {
                throw new IllegalStateException("Kindprozess scheiterte: " + ChildJvm.failureLine(stderr));
            }
            return Json.parse(r.value());
        } finally {
            setze("mtgplayer.assets", vorherAssets);
            setze("mtgplayer.data", vorherData);
        }
    }

    private static void setze(String name, String wert) {
        if (wert == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, wert);
        }
    }

    /**
     * Unsere heutigen Kartendaten duerfen nichts enthalten, was nicht auch auf der Ausschlussliste
     * steht. Bewusst NICHT "null Befunde": ob unter den 33.000 Karten von 2.0.14 schon vor diesem Plan
     * welche stehen, die sich nicht bauen lassen, hat nie jemand gemessen - der erste Lauf stellt das
     * fest, und sein Ergebnis wird der Ausgangszustand der Liste (siehe Step 6). Ab dann ist dieser
     * Test das Netz: taucht etwas NEUES auf, faellt er.
     */
    @Test
    void jederBefundStehtAufDerAusschlussliste(@TempDir Path tmp) throws Exception {
        JsonNode b = befund(tmp, Path.of("assets").toAbsolutePath());

        assertTrue(b.get("karten").asInt() > 30000, "Kartenzahl: " + b.get("karten"));
        List<String> bekannt = ausschlussliste();
        for (String feld : List.of("nichtAuffindbar", "doppelteSetCodes", "doppelteNamen")) {
            b.get(feld).forEach(n -> assertTrue(bekannt.contains(n.asText()),
                    feld + " enthaelt " + n.asText() + ", was nicht auf der Ausschlussliste steht"));
        }
        b.get("nichtBaubar").forEach(p -> assertTrue(bekannt.contains(p.get("karte").asText()),
                "nicht baubar: " + p.get("karte").asText() + " steht nicht auf der Ausschlussliste"));
    }

    /** Eigener Leser statt {@code Exclusions}: ein Test soll nicht mit demselben Werkzeug pruefen, das
     *  die Datei schreibt. */
    private static List<String> ausschlussliste() throws Exception {
        Path datei = Path.of("..", "docs", "kartendaten-ausgeschlossen.txt");
        if (!Files.isRegularFile(datei)) {
            return List.of();
        }
        return Files.readAllLines(datei).stream()
                .filter(z -> !z.isBlank() && !z.startsWith("#"))
                .map(z -> z.split("\t", 2)[0])
                .toList();
    }

    /**
     * Ohne diesen Test beweist der erste nichts: er waere auch gruen, wenn der Modus gar nichts prueft.
     * Das Kartenskript hier ist absichtlich unlesbar fuer Forge - es traegt einen Namen, landet aber nicht
     * in der Datenbank. Forge liest unter {@code cardsfolder/} nur Dateien auf {@code .txt}; die Endung
     * {@code .bak} macht das Skript fuer Forge unsichtbar, fuer {@code CardFiles} aber nicht. (Ein
     * Skript ohne {@code Types:}-Zeile taugt dafuer NICHT: Forge bricht beim Laden mit einer
     * NullPointerException ab, statt es zu uebergehen - dann kaeme gar kein Befund zustande.)
     */
    @Test
    void einUnlesbaresKartenskriptWirdGemeldet(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        kopiereRes(Path.of("assets").toAbsolutePath(), res);
        Files.writeString(res.resolve("cardsfolder").resolve("z").resolve("zzz_kaputt.txt.bak"),
                "Name:Zzz Kaputte Testkarte\nUnbekanntesSchluesselwort:Ja\nOracle:\n");
        Files.writeString(res.resolve("editions").resolve("ZZZ Doppelt.txt"),
                "[metadata]\nCode=LEA\nDate=2027-01-01\nName=ZZZ Doppelt\n\n[cards]\n");

        JsonNode b = befund(tmp, res.getParent());

        assertTrue(b.get("nichtAuffindbar").toString().contains("Zzz Kaputte Testkarte"),
                "die unlesbare Karte steht im Befund: " + b.get("nichtAuffindbar"));
        assertTrue(b.get("doppelteSetCodes").toString().contains("LEA"),
                "der doppelte Set-Code steht im Befund: " + b.get("doppelteSetCodes"));
    }

    /** Symlink statt Kopie fuer cardsfolder waere schneller, aber der Test MUSS hineinschreiben. */
    private static void kopiereRes(Path quelleAssets, Path zielRes) throws Exception {
        // res ist im Arbeitsbaum ein Symlink: Files.walk folgt Verweisen nicht, also vorher aufloesen.
        Path quelle = quelleAssets.resolve("res").toRealPath();
        try (var pfade = Files.walk(quelle)) {
            for (Path p : (Iterable<Path>) pfade::iterator) {
                Path ziel = zielRes.resolve(quelle.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(ziel);
                } else {
                    Files.createDirectories(ziel.getParent());
                    Files.copy(p, ziel);
                }
            }
        }
        try (var skripte = Files.walk(zielRes.resolve("cardsfolder"))) {
            assertTrue(skripte.filter(Files::isRegularFile).count() > 30000,
                    "kopiert wurde kein vollstaendiges cardsfolder - der Test waere sinnlos");
        }
    }
}
