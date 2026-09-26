package mtgplayer.app;

import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aufgabe 5 (App-Paket, Spec §5): Update anwenden - herunterladen, Pruefsumme, entpacken, Skript
 * schreiben. Wie {@link UpdateCheckTest}/{@link mtgplayer.decks.Edhrec}: der Abruf ist eine eingesetzte
 * Funktion (URL -&gt; Bytes), kein Test geht ins Netz. Jeder Zwischenordner entsteht unter einem
 * eigenen {@code tempDir} (nie das echte {@code %TEMP%}) - das ist die einzige Naht, ueber die diese
 * Tests je etwas ausserhalb ihres {@code @TempDir} anfassen wuerden, und sie zeigt hier immer auf ein
 * Unterverzeichnis des {@code @TempDir}.
 *
 * <p>Review-Nachtrag: das echte Release-ZIP traegt selbst einen einzigen obersten Ordner (Aufgabe 7
 * baut es so) - jede Fixture hier bildet das nach ({@link #zip} nimmt den obersten Ordnernamen als
 * ersten Teil jedes Eintragspfads).</p>
 */
class UpdateApplyTest {

    private static final String URL = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/MTG-Player-1.3.0-win.zip";
    private static final String OLD_VERSION_TEXT = "1.2.0";

    /** Baut ein ZIP mit einem einzigen obersten Ordner {@code topDir} und den angegebenen
     *  Eintragen darunter (Name -&gt; Inhalt) - wie ein echtes Release-ZIP (Aufgabe 7). */
    private static byte[] zip(String topDir, String... entryNameAndContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < entryNameAndContent.length; i += 2) {
                zos.putNextEntry(new ZipEntry(topDir + "/" + entryNameAndContent[i]));
                zos.write(entryNameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** ZIP mit genau EINEM rohen Eintragsnamen (ohne den ueblichen {@code topDir}-Zusammenbau) - fuer
     *  die Zip-Slip-Faelle, die absichtlich KEINEN normalen obersten Ordner haben. */
    private static byte[] zipRaw(String entryName, String content) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /** Ein vollstaendiges, gueltiges Paket: alle vier Merkmale aus Spec §2 (version.txt, Starter,
     *  runtime/, app/) liegen im obersten Ordner, wie {@link UpdateApply#validateAppContent} es
     *  verlangt (2. Review-Nachtrag: dieselben vier wie fuer den ALTEN Ordner, siehe {@link #appDir}). */
    private static byte[] gueltigesPaket(String topDir) throws IOException {
        return zip(topDir,
                "version.txt", "1.3.0",
                UpdateApply.EXE_NAME, "starter-binaer",
                "runtime/release", "java-laufzeit",
                "app/bridge.jar", "hallo-welt");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** Ein echter App-Ordner mit allen vier Merkmalen aus Spec §2 (version.txt, Starter, runtime/,
     *  app/), wie ihn eine echte Installation vorfindet - Name mit Leerzeichen (Review-Befund: muss
     *  auch dann funktionieren). 2. Review-Nachtrag: eine blosse version.txt reicht der Wache nicht
     *  mehr - sonst waere ein Downloads-Ordner mit einer zufaelligen version.txt darin schon "der
     *  App-Ordner" (siehe {@link UpdateApply}-Klassenkommentar zur Wache). */
    private static Path appDir(Path root) throws IOException {
        Path dir = root.resolve("MTG Player App");
        Files.createDirectories(dir.resolve("runtime"));
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("version.txt"), OLD_VERSION_TEXT);
        Files.writeString(dir.resolve(UpdateApply.EXE_NAME), "alter-starter");
        return dir;
    }

    private static void assertAppDirUnveraendert(Path appDir) throws IOException {
        assertTrue(Files.isDirectory(appDir), "App-Ordner darf nicht verschwinden");
        assertEquals(OLD_VERSION_TEXT, Files.readString(appDir.resolve("version.txt")),
                "App-Ordner darf von run() nie direkt angefasst werden - der Tausch passiert erst im Skript, nach dieser JVM");
        try (var stream = Files.list(appDir)) {
            assertEquals(4, stream.count(), "im App-Ordner darf nichts Neues aufgetaucht sein (version.txt, Starter, runtime/, app/)");
        }
    }

    /** Sammelt beide Argumente von {@code zustand} getrennt, wie ein Aufrufer (Bridge) es taete. */
    private static final class Protokoll {
        final List<String> zustaende = new ArrayList<>();
        final List<String> texte = new ArrayList<>();
        void add(String state, String text) {
            zustaende.add(state);
            texte.add(text);
        }
    }

    @Test
    void erfolgEntpacktInZwischenordnerUndSchreibtSkriptMitBeidenOrdnernamen(@TempDir Path root) throws IOException {
        byte[] content = gueltigesPaket("MTG-Player");
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "Neuigkeiten");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            assertEquals(URL, url);
            return content;
        }, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertEquals(List.of("laden", "pruefen", "entpacken", "neustart"), protokoll.zustaende);
        assertTrue(result != null, "Erfolg muss das geschriebene Skript liefern");
        Path script = result.script();
        assertTrue(Files.isRegularFile(script), "update.cmd muss neben dem Zwischenordner liegen");
        assertEquals("update.cmd", script.getFileName().toString());
        // "daneben": Skript und der entpackte Zwischenordner (der ZIP-eigene oberste Ordner) teilen
        // sich denselben Elternordner.
        Path zwischenordner = script.getParent();
        List<Path> geschwister;
        try (var stream = Files.list(zwischenordner)) {
            geschwister = stream.toList();
        }
        Path entpackterOrdner = geschwister.stream()
                .filter(p -> !p.equals(script) && Files.isDirectory(p))
                .findFirst().orElseThrow(() -> new AssertionError("der entpackte Zwischenordner muss neben dem Skript liegen"));
        Path entpackteDatei = entpackterOrdner.resolve("app").resolve("bridge.jar");
        assertTrue(Files.isRegularFile(entpackteDatei), "der Zip-Inhalt muss im Zwischenordner liegen");
        assertEquals("hallo-welt", Files.readString(entpackteDatei));
        // Zwischenordner ist unter dem eingesetzten tempDir, nicht irgendwo sonst
        assertTrue(zwischenordner.toAbsolutePath().normalize().startsWith(tempDir.toAbsolutePath().normalize()));

        String skriptText = Files.readString(script);
        assertTrue(skriptText.contains(appDir.toAbsolutePath().normalize().toString()),
                "das Skript muss den Namen/Pfad des App-Ordners enthalten");
        assertTrue(skriptText.contains(entpackterOrdner.toAbsolutePath().normalize().toString()),
                "das Skript muss den Namen/Pfad des neuen (entpackten) Ordners enthalten");
        // CRLF (Review-Befund): cmd.exe braucht Windows-Zeilenenden
        assertTrue(skriptText.contains("\r\n"), "das Skript muss CRLF-Zeilenenden haben");
        // Codepage (Review-Befund)
        assertTrue(skriptText.contains("chcp 65001"));
        // Arbeitsverzeichnis (Review-Befund, Blocker 1)
        assertTrue(skriptText.contains("cd /d"));
        // Warteschleife auf das Prozessende, bevor irgendetwas am App-Ordner angefasst wird
        assertTrue(skriptText.toLowerCase(Locale.ROOT).contains("tasklist"),
                "das Skript muss auf das Ende dieses Prozesses warten, bevor es den App-Ordner anfasst");
        assertFalse(skriptText.contains("timeout /t"), "timeout mit umgeleitetem stdin funktioniert unter ProcessBuilder nicht (Review-Befund)");
        assertTrue(skriptText.contains("ping -n"), "die Wartepausen muessen ueber ping laufen, nicht timeout");
        // Rueckweg bei einem Fehlschlag mitten im Tausch: zurueckbenennen UND die alte Fassung erneut starten
        assertTrue(skriptText.contains(".old"), "das Skript muss den alten Ordner vor dem Tausch beiseite legen");
        assertTrue(skriptText.contains(":restore_old"), "es muss einen Rueckweg geben");
        long startCount = skriptText.lines().filter(l -> l.trim().toLowerCase(Locale.ROOT).startsWith("start \"\"")).count();
        assertTrue(startCount >= 3, "die alte Fassung muss in JEDEM Fehlerfall erneut gestartet werden (ren/move/start), nicht nur die neue im Erfolgsfall");
        // Arbeitsverzeichnis fuer die gestartete App (2. Review-Nachtrag): ohne /d erbt "start" das
        // Arbeitsverzeichnis DIESES Skripts (ein Ordner unter %TEMP%), nicht den App-Ordner.
        assertTrue(skriptText.contains("start \"\" /d"), "start muss das Arbeitsverzeichnis der gestarteten App explizit setzen");
        // Aufraeumen des Zwischenordners (Review-Befund)
        assertTrue(skriptText.contains(":cleanup"));
        // 2. Review-Nachtrag, Befund 1: beide Wartezeiten muessen bei Ablauf abbrechen, nicht in den
        // Tausch hineinfallen.
        assertTrue(skriptText.contains(":confirmtimeout"), "abgelaufene Bestaetigung muss abbrechen");
        assertTrue(skriptText.contains(":waittimeout"), "abgelaufenes Warten muss abbrechen");
        // 2. Review-Nachtrag, Befund 3: die Fehlermeldungen des Skripts muessen in eine Datei gehen,
        // sonst sieht sie niemand (die Pipes sterben mit der bereits beendeten JVM).
        assertTrue(skriptText.contains(":log"), "es muss eine Protokoll-Routine geben");
        assertTrue(skriptText.contains("update.log"), "das Protokoll muss in eine eigene Datei gehen");
        // 3. Review-Nachtrag: der FRISCHE .old (OLDDIR, gerade erst durch DIESEN Tausch entstanden)
        // wird von KEINER Stelle im Skript geloescht - weder vor noch nach dem Tausch. Ein VORHANDENES
        // .old aus einem frueheren Update wird nur beiseitegeschoben (ren nach OLDPREV) und erst NACH
        // erfolgreichem Start der neuen Fassung geloescht - sonst waere die Sicherung schon weg, obwohl
        // DIESER Tausch nie stattfand (das war genau der Fehler, den dieser Nachtrag behebt).
        long oldDirRmdirCount = skriptText.lines()
                .filter(l -> l.contains("rmdir") && l.contains("\"%OLDDIR%\"")).count();
        assertEquals(0, oldDirRmdirCount,
                "OLDDIR (der frische alte Ordner) darf von keiner Stelle im Skript geloescht werden");
        assertTrue(skriptText.contains("OLDPREV"), "ein vorhandenes .old muss beiseitegeschoben werden koennen");
        assertTrue(skriptText.contains(".old.previous"), "der Zwischenname fuer ein vorhandenes .old muss im Skript auftauchen");
        // Das Loeschen von OLDPREV darf nur NACH dem erfolgreichen Start der neuen Fassung stehen -
        // ueber Zeilen-Indizes geprueft statt ueber einen bruechigen Gesamttext-Vergleich: es muss
        // (mindestens) eine "rmdir OLDPREV"-Zeile geben, auf die (nach etwaigen Leerzeilen) direkt die
        // "Update erfolgreich"-Meldung folgt.
        List<String> zeilen = skriptText.lines().toList();
        boolean loeschtOldprevErstNachErfolg = false;
        for (int i = 0; i < zeilen.size(); i++) {
            String zeile = zeilen.get(i).trim();
            if (zeile.contains("rmdir") && zeile.contains("\"%OLDPREV%\"")) {
                for (int j = i + 1; j < zeilen.size(); j++) {
                    String naechste = zeilen.get(j).trim();
                    if (naechste.isEmpty()) continue;
                    if (naechste.contains("Update erfolgreich")) loeschtOldprevErstNachErfolg = true;
                    break;
                }
            }
        }
        assertTrue(loeschtOldprevErstNachErfolg,
                "OLDPREV darf erst geloescht werden, wenn direkt danach der Erfolg protokolliert wird");
        assertTrue(skriptText.contains("kann von Hand geloescht werden"),
                "das Protokoll muss dem Nutzer sagen, dass .old von Hand geloescht werden kann");

        assertAppDirUnveraendert(appDir);
    }

    @Test
    void falschePruefsummeBrichtAbMitGrundUndLaesstKeinenZwischenordnerZurueck(@TempDir Path root) throws IOException {
        byte[] content = gueltigesPaket("MTG-Player");
        String falscheHash = "0".repeat(64);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, falscheHash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "fehler"), protokoll.zustaende);
        assertNotNull(protokoll.texte.get(2), "der Grund darf nicht verloren gehen (Review-Befund)");
        assertTrue(protokoll.texte.get(2).contains(falscheHash), "der Grund soll die erwartete Pruefsumme nennen");
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void werfendeQuelleBrichtGenausoAbMitGrund(@TempDir Path root) throws IOException {
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            throw new RuntimeException("Netz kaputt");
        }, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("laden", "fehler"), protokoll.zustaende);
        assertEquals("Netz kaputt", protokoll.texte.get(1));
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void unvollstaendigesPaketOhneStarterWirdVorErfolgsmeldungAbgelehnt(@TempDir Path root) throws IOException {
        // Kein Starter im obersten Ordner - ein kaputtes/unvollstaendiges ZIP darf nie als Erfolg gelten.
        byte[] content = zip("MTG-Player", "version.txt", "1.3.0");
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "entpacken", "fehler"), protokoll.zustaende);
        assertTrue(protokoll.texte.get(3).contains(UpdateApply.EXE_NAME));
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void paketOhneErkennbarenOberstenOrdnerWirdAbgelehnt(@TempDir Path root) throws IOException {
        // Kein einzelner oberster Ordner (zwei Eintraege direkt auf oberster Ebene) - unerwartetes Paket.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("eins.txt"));
            zos.write("a".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("zwei.txt"));
            zos.write("b".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] content = bos.toByteArray();
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "entpacken", "fehler"), protokoll.zustaende);
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count());
        }
        assertAppDirUnveraendert(appDir);
    }

    /** Review-Befund: der reine Aufloesungscheck erkennt nur ".."-Segmente, die Java als Pfad deutet -
     *  auf Linux (Testsystem) ist "..\evil" nur ein Dateiname, kein Verzeichniswechsel. Die textuelle
     *  Vorpruefung muss deshalb JEDEN dieser Vektoren ablehnen, unabhaengig vom Testsystem-Betriebssystem. */
    @Test
    void zipEintraegeMitGefaehrlichenNamenWerdenTextuellAbgelehnt(@TempDir Path root) throws IOException {
        String[] boesartigeNamen = {
                "../evil.txt",
                "..\\evil.txt",
                "C:\\evil.txt",
                "\\evil.txt",
                "\\\\server\\share\\evil.txt",
        };
        for (String name : boesartigeNamen) {
            byte[] content = zipRaw(name, "ueberschrieben");
            String hash = sha256Hex(content);
            UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "");
            Path appDir = appDir(root.resolve("appdir-" + Math.abs(name.hashCode())));
            Path tempDir = root.resolve("temp-" + Math.abs(name.hashCode()));
            Files.createDirectories(tempDir);
            Protokoll protokoll = new Protokoll();
            UpdateApply apply = new UpdateApply(url -> content, tempDir);

            UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

            assertNull(result, "abgelehnt werden muss: " + name);
            assertEquals(List.of("laden", "pruefen", "entpacken", "fehler"), protokoll.zustaende, "Zustandsfolge fuer: " + name);
            try (var stream = Files.list(tempDir)) {
                assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben fuer: " + name);
            }
            // Review-Befund: die alte Zusicherung prüfte "root/evil.txt" - ein Pfad, an dem selbst ein
            // erfolgreicher Ausbruch dieser Vektoren nie landen wuerde (der Zwischenordner liegt tiefer
            // als root), die Pruefung konnte also nie fehlschlagen. Stattdessen den GANZEN Baum unter
            // root durchsuchen - so faellt ein Ausbruch unabhaengig von der genauen Verschachtelungstiefe auf.
            try (var walk = Files.walk(root)) {
                assertTrue(walk.noneMatch(p -> "evil.txt".equals(p.getFileName().toString())),
                        "der Eintrag darf nie irgendwo im Baum landen: " + name);
            }
            assertAppDirUnveraendert(appDir);
        }
    }

    /** Review-Befund (Punkt 14): ein Eintrag mit leerem Namen oder "." loest auf den Zielordner selbst
     *  auf und wuerde ihn durch eine Datei ersetzen - muss abgelehnt werden wie die anderen gefaehrlichen
     *  Namen oben. */
    @Test
    void zipEintragMitEigenemPfadWirdAbgelehnt(@TempDir Path root) throws IOException {
        byte[] content = zipRaw("./", "ueberschrieben");
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "entpacken", "fehler"), protokoll.zustaende);
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void appOrdnerOhneVersionTxtWirdVorJedemNetzzugriffAbgelehnt(@TempDir Path root) throws IOException {
        Path appDir = root.resolve("Kein App-Ordner");
        Files.createDirectories(appDir);
        // absichtlich KEIN version.txt
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            throw new AssertionError("appDir-Wache muss VOR jedem Netzzugriff greifen");
        }, root.resolve("temp"));

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("fehler"), protokoll.zustaende);
        assertNotNull(protokoll.texte.get(0));
        assertTrue(protokoll.texte.get(0).contains("version.txt"));
    }

    @Test
    void appOrdnerUnterDemDatenverzeichnisWirdAbgelehnt(@TempDir Path root) throws IOException {
        // ForgeBoot.dataDir() zeigt im Testlauf auf target/test-data (siehe bridge/pom.xml,
        // systemPropertyVariables) - ein appDir darunter waere katastrophal (Kevins echte Daten unter
        // ~/.mtg-player im Betrieb) und muss VOR jedem Netzzugriff abgelehnt werden.
        Path appDir = ForgeBoot.dataDir().resolve("MTG-Player-Attrappe");
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            throw new AssertionError("appDir-Wache muss VOR jedem Netzzugriff greifen");
        }, root.resolve("temp"));

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("fehler"), protokoll.zustaende);
        assertTrue(protokoll.texte.get(0).contains("Datenverzeichnis"));
        assertFalse(Files.exists(appDir), "die Wache darf appDir nicht einmal anlegen");
    }

    /** 2. Review-Nachtrag, der neue kritische Befund: das Datenverzeichnis kann auch UNTERHALB eines
     *  appDir liegen (nicht nur umgekehrt) - z.B. wenn appDir versehentlich ein sehr hoher Ordner ist,
     *  der zufaellig ~/.mtg-player enthaelt. Beide Richtungen muessen abgelehnt werden. Kein appDir wird
     *  hier wirklich angelegt/berueht (die Pruefung ist ein reiner Pfadvergleich, kein Dateizugriff). */
    @Test
    void datenverzeichnisUnterhalbDesAppOrdnersWirdAbgelehnt() throws IOException {
        Path data = ForgeBoot.dataDir().toAbsolutePath().normalize();
        Path appDir = data.getParent();
        assertNotNull(appDir, "target/test-data muss einen Elternordner haben");
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            throw new AssertionError("appDir-Wache muss VOR jedem Netzzugriff greifen");
        }, appDir.resolve("wird-nie-gebraucht"));

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("fehler"), protokoll.zustaende);
        assertTrue(protokoll.texte.get(0).contains("Datenverzeichnis"));
    }

    /** 2. Review-Nachtrag, der neue kritische Befund (Entscheidung A): eine blosse version.txt reichte
     *  bisher als Beweis fuer "das ist der App-Ordner" - entpackt jemand das Paket mit "hier entpacken"
     *  z.B. nach Downloads, waere GENAU DAS der App-Ordner, und das Skript wuerde Downloads umbenennen.
     *  Die Wache muss deshalb dieselben vier Merkmale wie fuer den NEUEN Ordner verlangen, nicht nur
     *  version.txt. */
    @Test
    void appOrdnerOhneRuntimeUndAppVerzeichnisWirdAbgelehnt(@TempDir Path root) throws IOException {
        // wie ein Downloads-Ordner, in den jemand nur die lose version.txt (z.B. aus einem alten Zip)
        // kopiert hat - kein runtime/, kein app/, kein Starter.
        Path appDir = root.resolve("Downloads");
        Files.createDirectories(appDir);
        Files.writeString(appDir.resolve("version.txt"), OLD_VERSION_TEXT);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Protokoll protokoll = new Protokoll();
        UpdateApply apply = new UpdateApply(url -> {
            throw new AssertionError("appDir-Wache muss VOR jedem Netzzugriff greifen");
        }, root.resolve("temp"));

        UpdateApply.Result result = apply.run(release, appDir, protokoll::add);

        assertNull(result);
        assertEquals(List.of("fehler"), protokoll.zustaende);
        assertTrue(protokoll.texte.get(0).contains("Starter"),
                "version.txt ist da, aber der Starter fehlt - genau das muss die Wache jetzt auch pruefen");
        // Der Downloads-Ordner selbst darf nicht angefasst worden sein.
        assertTrue(Files.isDirectory(appDir));
        try (var stream = Files.list(appDir)) {
            assertEquals(1, stream.count(), "ausser der urspruenglichen version.txt darf nichts entstanden sein");
        }
    }
}
