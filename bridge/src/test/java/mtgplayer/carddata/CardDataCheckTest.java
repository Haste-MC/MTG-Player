package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
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
     * Geprueft wird hier {@code nichtAuffindbar}: eine Datei mit {@code Name:}-Zeile, deren Karte NICHT in
     * Forges Datenbank landet. Dazu dient die Endung {@code .bak}: Forge liest unter {@code cardsfolder/}
     * nur {@code *.txt} und sieht die Datei nicht, {@code CardFiles} indexiert dagegen jede Datei. Der Test
     * nutzt also bewusst diese Abweichung zwischen beiden. (Ein Skript ohne {@code Types:}-Zeile taugt
     * dafuer NICHT: Forge bricht beim Laden mit einer NullPointerException ab, statt es zu uebergehen -
     * dann kaeme gar kein Befund zustande.)
     */
    @Test
    void eineDateiOhneKarteInDerDatenbankWirdGemeldet(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        kopiereRes(Path.of("assets").toAbsolutePath(), res);
        Files.writeString(res.resolve("cardsfolder").resolve("z").resolve("zzz_kaputt.txt.bak"),
                "Name:Zzz Kaputte Testkarte\nUnbekanntesSchluesselwort:Ja\nOracle:\n");
        Files.writeString(res.resolve("editions").resolve("ZZZ Doppelt.txt"),
                "[metadata]\nCode=LEA\nDate=2027-01-01\nName=ZZZ Doppelt\n\n[cards]\n");

        JsonNode b = befund(tmp, res.getParent());

        assertTrue(b.get("nichtAuffindbar").toString().contains("Zzz Kaputte Testkarte"),
                "die Karte ohne Datenbankeintrag steht im Befund: " + b.get("nichtAuffindbar"));
        assertTrue(b.get("doppelteSetCodes").toString().contains("LEA"),
                "der doppelte Set-Code steht im Befund: " + b.get("doppelteSetCodes"));
    }

    /**
     * Der Pfad, um dessentwillen der Modus existiert: Karten, die Forge LAEDT (sie stehen in der
     * Datenbank), die aber beim Bauen scheitern. In den echten Daten gibt es keine - ohne diesen Test fiele
     * es nicht auf, wenn die Bauschleife nichts taete. Jedes Skript enthaelt ein Schluesselwort, das unsere
     * Forge-Fassung nicht kennt; der Grund muss es nennen, nicht nur die aeussere Ausnahme.
     *
     * <p>Die erste Testkarte heißt bewusst "Zzz Unbekannte Faehigkeit", nicht "Zzz Empower Testkarte":
     * der Name darf das Schluesselwort nicht enthalten, sonst waere die Zusicherung auch gruen, wenn die
     * Ursachenkette gar nicht ausgewertet wurde (Forges Meldung kaeme ueber den KARTENNAMEN, nicht die Cause).
     */
    @Test
    void kartenDieSichNichtBauenLassenStehenMitUrsacheImBefund(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        kopiereRes(Path.of("assets").toAbsolutePath(), res);
        Path ordner = res.resolve("cardsfolder").resolve("z");
        Files.writeString(ordner.resolve("zzz_unbekannte_faehigkeit_testkarte.txt"),
                "Name:Zzz Unbekannte Faehigkeit\nManaCost:1\nTypes:Artifact\n"
                        + "A:AB$ Empower | Cost$ T | SpellDescription$ Testfaehigkeit.\nOracle:{T}: Testfaehigkeit.\n");
        Files.writeString(ordner.resolve("zzz_muenzen_testkarte.txt"),
                "Name:Zzz Muenzen Testkarte\nManaCost:1\nTypes:Artifact\n"
                        + "T:Mode$ FlippedCoinOnce | ValidPlayer$ You | Execute$ TrigDraw | TriggerZones$ Battlefield"
                        + " | TriggerDescription$ Wenn eine Muenze faellt, zieh eine Karte.\n"
                        + "SVar:TrigDraw:DB$ Draw | NumCards$ 1\nOracle:Wenn eine Muenze faellt, zieh eine Karte.\n");
        Files.writeString(ordner.resolve("zzz_strahl_testkarte.txt"),
                "Name:Zzz Strahl Testkarte\nManaCost:1\nTypes:Artifact\n"
                        + "S:Mode$ CantBeBeamedUp | ValidCard$ Card.Self | Description$ Testregel.\nOracle:Testregel.\n");

        JsonNode b = befund(tmp, res.getParent());

        JsonNode probleme = b.get("nichtBaubar");
        assertGrund(probleme, "Zzz Unbekannte Faehigkeit", "crash in raw Ability", "Empower");
        assertGrund(probleme, "Zzz Muenzen Testkarte", "Error in Trigger for Card", "FlippedCoinOnce");
        assertGrund(probleme, "Zzz Strahl Testkarte", "CantBeBeamedUp");
    }

    /**
     * Unter LC_ALL=POSIX ist die Standardkodierung der JVM ASCII; ein Befund ueber System.out machte aus
     * "J\u00f6tun Grunt" ein "J?tun Grunt", und der Schluessel passte zu keiner Datei mehr. Der Befund wird
     * deshalb ausdruecklich als UTF-8 geschrieben - hier im Kindprozess unter POSIX nachgewiesen.
     */
    @Test
    void befundNenntNichtAsciiNamenAuchUnterPosixLocaleUnversehrt(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        kopiereRes(Path.of("assets").toAbsolutePath(), res);
        Files.writeString(res.resolve("cardsfolder").resolve("z").resolve("zzz_joetun.txt.bak"),
                "Name:Zzz J\u00f6tun Grunt\nUnbekanntesSchluesselwort:Ja\nOracle:\n", StandardCharsets.UTF_8);

        String vorherAssets = System.getProperty("mtgplayer.assets");
        String vorherData = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.assets", res.getParent().toString());
        System.setProperty("mtgplayer.data", tmp.resolve("child-data").toString());
        List<String> cmd;
        try {
            cmd = ChildJvm.command(List.of("--kartendaten-pruefen"));
        } finally {
            setze("mtgplayer.assets", vorherAssets);
            setze("mtgplayer.data", vorherData);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("LC_CTYPE");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().put("LC_ALL", "POSIX");
        pb.environment().put("LANG", "POSIX");
        Path out = tmp.resolve("out.txt");
        pb.redirectOutput(out.toFile()).redirectError(tmp.resolve("err.txt").toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(15, TimeUnit.MINUTES), "Kindprozess haengt");
        assertEquals(0, p.exitValue());

        String zeile = Files.readAllLines(out, StandardCharsets.UTF_8).stream()
                .filter(z -> z.startsWith("KARTENDATEN_BEFUND ")).findFirst().orElseThrow();
        JsonNode b = Json.parse(zeile.substring("KARTENDATEN_BEFUND ".length()));
        assertTrue(b.get("nichtAuffindbar").toString().contains("Zzz J\u00f6tun Grunt"),
                "der Name mit Umlaut kommt unversehrt an: " + b.get("nichtAuffindbar"));
    }

    private static void assertGrund(JsonNode probleme, String karte, String... teile) {
        for (JsonNode p : probleme) {
            if (p.get("karte").asText().equals(karte)) {
                String grund = p.get("grund").asText();
                for (String teil : teile) {
                    assertTrue(grund.contains(teil), karte + ": Grund nennt nicht '" + teil + "': " + grund);
                }
                return;
            }
        }
        throw new AssertionError(karte + " steht nicht in nichtBaubar: " + probleme);
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
