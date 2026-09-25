package mtgplayer.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mtgplayer.proc.ChildJvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Beweis fuer {@code scripts/trim-res.sh}: das ausgeduennte {@code res}-Verzeichnis (siehe dort fuer die
 * Ordnerliste und die Begruendung, was draussen bleibt) muss fuer eine ECHTE KI-Partie reichen - gleiche
 * Kartenzahl wie mit vollstaendigem {@code res}, jedes Precon laedt, ein Spiel laeuft bis zum Ende
 * (Zugdeckel als Ausstieg) durch. Faellt einer dieser Schritte aus, weil ein Pfad im ausgeduennten
 * {@code res} fehlt, steckt die genaue Meldung ({@code assets/res/cardsfolder fehlt unter ...}) in der
 * Assertion-Nachricht - dann gehoert der fehlende Ordner in {@code scripts/trim-res.sh}.
 *
 * <p>Das ausgeduennte {@code res} kommt in einem eigenen Kindprozess zum Einsatz ({@link ChildJvm}), weil
 * {@link ForgeBoot#init()} je JVM nur einmal laeuft (siehe dort) - diese Test-JVM selbst laedt VORHER
 * bereits das vollstaendige {@code res} fuer den Vergleichswert und darf dafuer nicht "vergiftet"
 * werden. {@code mtgplayer.data} zeigt laut {@code bridge/pom.xml} fuer jeden Testlauf auf
 * {@code target/test-data} - weder diese Test-JVM noch der Kindprozess schreiben also in Kevins echtes
 * {@code ~/.mtg-player}. Das Original {@code forge/forge-gui/res} wird von {@code trim-res.sh} nur
 * gelesen und landet hier ausschliesslich in einem {@code @TempDir}.</p>
 *
 * <p><b>Falle, die ein Review aufgedeckt hat:</b> {@code ForgeBoot.init()} legt bei gesetztem
 * {@code mtgplayer.data} einen Symlink {@code <data>/assets/res -> <realAssets>/res} an und laesst
 * ihn stehen, falls er schon existiert ({@code isolatedAssetsDir}). Bekaeme der Kindprozess DASSELBE
 * {@code mtgplayer.data} wie diese Test-JVM (target/test-data, von der ersten {@link ForgeBoot#init()}
 * hier oben schon mit einem Symlink aufs VOLLE res belegt), faende sein eigener {@code init()}-Aufruf
 * diesen Symlink bereits vor und liesse ihn stehen - das ausgeduennte {@code res} liefe komplett ins
 * Leere, der Kindprozess laede unbemerkt weiter das volle res (Kartenzahl waere dann immer gleich,
 * ganz unabhaengig vom Inhalt des ausgeduennten Verzeichnisses - genau das ist beim ersten Anlauf
 * passiert). Deshalb bekommt der Kindprozess hier ein EIGENES, frisches {@code mtgplayer.data}
 * ({@code tmp.resolve("child-data")}), unter dem garantiert noch kein Symlink liegt.</p>
 */
class TrimmedResTest {

    private static final Pattern ERGEBNIS = Pattern.compile(
            "cards=(\\d+) precons=(\\d+) turns=(\\d+) capped=(true|false)");

    @Test
    @Timeout(600)
    void ausgeduenntesResReichtFuerVolleKiPartie(@TempDir Path tmp) throws IOException, InterruptedException {
        // Vergleichswert im selben Lauf ermitteln (nicht fest eintragen): volles res, wie es
        // bridge/pom.xml per mtgplayer.assets fuer jeden Testlauf vorgibt.
        Path volleAssets = ForgeBoot.assetsDir();
        ForgeBoot.init();
        int vollstaendigeKartenzahl = ForgeBoot.cardCount();
        assertTrue(vollstaendigeKartenzahl > 20000, "erwartet > 20000 Karten, war " + vollstaendigeKartenzahl);

        Path script = volleAssets.getParent().getParent().resolve("scripts").resolve("trim-res.sh");
        assertTrue(Files.isRegularFile(script), "scripts/trim-res.sh nicht gefunden unter " + script);

        Path ziel = tmp.resolve("trimmed-assets");
        long t0 = System.currentTimeMillis();
        runTrimScript(script, volleAssets.resolve("res"), ziel);
        long kopierMillis = System.currentTimeMillis() - t0;
        // trim-res.sh kopiert echt (kein Hardlink, siehe dort) - die gemessene Zeit gehoert in den Bericht.
        System.out.println("[TrimmedResTest] trim-res.sh: " + kopierMillis + " ms zum Ausduennen");

        ChildJvm.Result result = trimmedCheckImKindprozess(tmp, ziel);

        assertTrue(result.ok(), "Kindprozess mit ausgeduenntem res sollte sauber durchlaufen (timedOut="
                + result.timedOut() + ", exit=" + result.exit() + ", value=" + result.value() + ")");

        Matcher m = ERGEBNIS.matcher(result.value());
        assertTrue(m.matches(), "unerwartete Ergebniszeile vom Kindprozess: " + result.value());

        int ausgeduennteKartenzahl = Integer.parseInt(m.group(1));
        int preconCount = Integer.parseInt(m.group(2));
        int turns = Integer.parseInt(m.group(3));

        assertEquals(vollstaendigeKartenzahl, ausgeduennteKartenzahl,
                "ausgeduenntes res muss dieselbe Kartenzahl liefern wie das vollstaendige (" + vollstaendigeKartenzahl + ")");
        assertTrue(preconCount > 0, "erwarte mindestens ein geladenes Precon");
        assertTrue(turns > 0, "erwarte mindestens einen gespielten Zug, die Partie muss bis zum Ende (oder Zugdeckel) laufen");
    }

    /** Ruft {@code scripts/trim-res.sh <quelle> <ziel>} auf und laesst den Test fehlschlagen, wenn es
     *  nicht sauber (Exitcode 0) durchlaeuft - die Ausgabe des Skripts (inkl. eines etwaigen "Ordner
     *  fehlt"-Hinweises mit dem gesuchten Pfad) steht dann in der Fehlermeldung. */
    private static void runTrimScript(Path script, Path quelle, Path ziel) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString(), quelle.toString(), ziel.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean fertig = p.waitFor(2, TimeUnit.MINUTES);
        if (!fertig) {
            p.destroyForcibly();
            fail("trim-res.sh lief laenger als 2 Minuten, Ausgabe bisher:\n" + ausgabe);
        }
        System.out.println("[TrimmedResTest] trim-res.sh Ausgabe:\n" + ausgabe);
        if (p.exitValue() != 0) {
            fail("trim-res.sh endete mit Exitcode " + p.exitValue() + ":\n" + ausgabe);
        }
    }

    /**
     * Startet {@code mtgplayer.Main --trimmed-check} in einem Kindprozess mit {@code mtgplayer.assets}
     * auf {@code ziel} - dazu {@code mtgplayer.assets} in DIESER JVM kurz umbiegen: {@link ChildJvm#command}
     * baut die Kommandozeile des Kindes aus {@link ForgeBoot#assetsDir()} DIESER JVM (liest die
     * System-Property jedesmal frisch, kein Caching), das ist der einzige Hebel dafuer. Wie in
     * {@code ForgeBootDataDirTest}: den vorgefundenen Wert merken und im {@code finally} exakt wiederherstellen,
     * sonst verliert der Rest dieses Testlaufs (ein Fork fuer die ganze Klasse) die Isolation.
     *
     * <p>Ebenso {@code mtgplayer.data} umbiegen, auf ein FRISCHES Verzeichnis ({@code tmp.resolve("child-data")},
     * garantiert noch nie von {@link ForgeBoot#init()} angefasst) statt auf denselben Wert wie diese
     * Test-JVM - siehe Klassenkommentar ("Falle, die ein Review aufgedeckt hat") fuer den Grund: sonst
     * findet der Kindprozess dort schon einen Symlink auf das VOLLE res vor und laesst ihn stehen.</p>
     */
    private static ChildJvm.Result trimmedCheckImKindprozess(Path tmp, Path ziel) {
        String vorherAssets = System.getProperty("mtgplayer.assets");
        String vorherData = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.assets", ziel.toString());
        System.setProperty("mtgplayer.data", tmp.resolve("child-data").toString());
        try {
            Path stderrFile = tmp.resolve("trimmed-check-stderr.log");
            ChildJvm.Result r = ChildJvm.run(List.of("--trimmed-check"), "TRIMMED_RESULT ", stderrFile,
                    line -> System.out.println("[Kind] " + line), 8, TimeUnit.MINUTES, null);
            if (!r.ok()) {
                System.out.println("[TrimmedResTest] letzte stderr-Zeile des Kindprozesses: " + ChildJvm.failureLine(stderrFile));
            }
            return r;
        } finally {
            restoreProperty("mtgplayer.assets", vorherAssets);
            restoreProperty("mtgplayer.data", vorherData);
        }
    }

    /** {@code null} = beim Start war keine Property gesetzt, sonst der vorgefundene Wert - siehe
     *  {@code ForgeBootDataDirTest} fuer denselben Grund (kein blosses {@code clearProperty}, das
     *  wuerde bei gesetzt gewesener Property die Isolation fuer den Rest dieses Forks wegnehmen). */
    private static void restoreProperty(String key, String vorher) {
        if (vorher == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, vorher);
        }
    }
}
