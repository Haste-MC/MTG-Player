package mtgplayer.app;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aufgabe 5 (App-Paket, Spec §5): Update anwenden - herunterladen, Pruefsumme, entpacken, Skript
 * schreiben. Wie {@link UpdateCheckTest}/{@link mtgplayer.decks.Edhrec}: der Abruf ist eine
 * eingesetzte Funktion (URL -&gt; Bytes), kein Test geht ins Netz. Jeder Zwischenordner entsteht
 * unter einem eigenen {@code tempDir} (nie das echte {@code %TEMP%}) - das ist die einzige Naht, ueber
 * die diese Tests je etwas ausserhalb ihres {@code @TempDir} anfassen wuerden, und sie zeigt hier immer
 * auf ein Unterverzeichnis des {@code @TempDir}.
 */
class UpdateApplyTest {

    private static final String URL = "https://github.com/Haste-MC/MTG-Player/releases/download/v1.3.0/MTG-Player-1.3.0-win.zip";
    private static final String MARKER_FILE = "version.txt";
    private static final String MARKER_TEXT = "1.2.0";

    /** Baut ein ZIP mit bekanntem Inhalt (ein Ordner "app" mit einer Datei drin, wie im echten Paket). */
    private static byte[] zip(String... entryNameAndContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < entryNameAndContent.length; i += 2) {
                zos.putNextEntry(new ZipEntry(entryNameAndContent[i]));
                zos.write(entryNameAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
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

    /** App-Ordner mit einer Markerdatei anlegen, wie ihn Version.current() bei einer echten Installation vorfindet. */
    private static Path appDir(Path root) throws IOException {
        Path dir = root.resolve("MTG-Player");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MARKER_FILE), MARKER_TEXT);
        return dir;
    }

    private static void assertAppDirUnveraendert(Path appDir) throws IOException {
        assertTrue(Files.isDirectory(appDir), "App-Ordner darf nicht verschwinden");
        assertEquals(MARKER_TEXT, Files.readString(appDir.resolve(MARKER_FILE)),
                "App-Ordner darf von run() nie direkt angefasst werden - der Tausch passiert erst im Skript, nach dieser JVM");
        try (var stream = Files.list(appDir)) {
            assertEquals(1, stream.count(), "im App-Ordner darf nichts Neues aufgetaucht sein");
        }
    }

    @Test
    void erfolgEntpacktInZwischenordnerUndSchreibtSkriptMitBeidenOrdnernamen(@TempDir Path root) throws IOException {
        byte[] content = zip("app/bridge.jar", "hallo-welt");
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "Neuigkeiten");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        List<String> zustaende = new ArrayList<>();
        UpdateApply apply = new UpdateApply(url -> {
            assertEquals(URL, url);
            return content;
        }, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, zustaende::add);

        assertEquals(List.of("laden", "pruefen", "entpacken", "neustart"), zustaende);
        assertTrue(result != null, "Erfolg muss das geschriebene Skript liefern");
        Path script = result.script();
        assertTrue(Files.isRegularFile(script), "update.cmd muss neben dem Zwischenordner liegen");
        assertEquals("update.cmd", script.getFileName().toString());
        // "daneben": Skript und der entpackte Zwischenordner teilen sich denselben Elternordner.
        Path zwischenordner = script.getParent();
        assertTrue(Files.list(zwischenordner).anyMatch(p -> !p.equals(script) && Files.isDirectory(p)),
                "der entpackte Zwischenordner muss neben dem Skript liegen");
        // wirklich entpackt, nicht nur ein leerer Ordner
        Path entpackteDatei = zwischenordner.resolve(appDir.getFileName()).resolve("app").resolve("bridge.jar");
        assertTrue(Files.isRegularFile(entpackteDatei), "der Zip-Inhalt muss im Zwischenordner liegen");
        assertEquals("hallo-welt", Files.readString(entpackteDatei));
        // Zwischenordner ist unter dem eingesetzten tempDir, nicht irgendwo sonst
        assertTrue(zwischenordner.toAbsolutePath().normalize().startsWith(tempDir.toAbsolutePath().normalize()));

        String skriptText = Files.readString(script);
        assertTrue(skriptText.contains(appDir.toAbsolutePath().normalize().toString()),
                "das Skript muss den Namen/Pfad des App-Ordners enthalten");
        assertTrue(skriptText.contains(entpackteDatei.getParent().getParent().toAbsolutePath().normalize().toString()),
                "das Skript muss den Namen/Pfad des neuen (entpackten) Ordners enthalten");
        // Warteschleife auf das Prozessende, bevor irgendetwas am App-Ordner angefasst wird
        assertTrue(skriptText.toLowerCase(Locale.ROOT).contains("tasklist"),
                "das Skript muss auf das Ende dieses Prozesses warten, bevor es den App-Ordner anfasst");
        // Rueckweg bei einem Fehlschlag mitten im Tausch
        assertTrue(skriptText.contains(".old"), "das Skript muss den alten Ordner vor dem Tausch beiseite legen");
        long renCount = skriptText.lines().filter(l -> l.trim().toLowerCase(Locale.ROOT).startsWith("ren ")).count();
        assertTrue(renCount >= 2, "es muss sowohl das Beiseitelegen als auch das Zurueckbenennen im Fehlerfall geben");

        assertAppDirUnveraendert(appDir);
    }

    @Test
    void falschePruefsummeBrichtAbUndLaesstKeinenZwischenordnerZurueck(@TempDir Path root) throws IOException {
        byte[] content = zip("app/bridge.jar", "hallo-welt");
        String falscheHash = "0".repeat(64);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, falscheHash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        List<String> zustaende = new ArrayList<>();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, zustaende::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "fehler"), zustaende);
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void werfendeQuelleBrichtGenausoAb(@TempDir Path root) throws IOException {
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, "a".repeat(64), "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        List<String> zustaende = new ArrayList<>();
        UpdateApply apply = new UpdateApply(url -> {
            throw new RuntimeException("Netz kaputt");
        }, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, zustaende::add);

        assertNull(result);
        assertEquals(List.of("laden", "fehler"), zustaende);
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben");
        }
        assertAppDirUnveraendert(appDir);
    }

    @Test
    void zipEintragAusserhalbDesZielordnersWirdAbgelehnt(@TempDir Path root) throws IOException {
        // Zip-Slip: ein Eintrag, der ueber ".." aus dem Zielordner hinaus zeigt, darf nie entpackt
        // werden - genau der Fall, den die Pfadpruefung in der Spec verhindern soll.
        byte[] content = zip("../evil.txt", "ueberschrieben");
        String hash = sha256Hex(content);
        UpdateCheck.Release release = new UpdateCheck.Release("1.3.0", URL, hash, "");
        Path appDir = appDir(root);
        Path tempDir = root.resolve("temp");
        Files.createDirectories(tempDir);
        List<String> zustaende = new ArrayList<>();
        UpdateApply apply = new UpdateApply(url -> content, tempDir);

        UpdateApply.Result result = apply.run(release, appDir, zustaende::add);

        assertNull(result);
        assertEquals(List.of("laden", "pruefen", "entpacken", "fehler"), zustaende);
        try (var stream = Files.list(tempDir)) {
            assertEquals(0, stream.count(), "kein Zwischenordner darf liegen bleiben, auch nicht teilweise entpackt");
        }
        assertFalse(Files.exists(root.resolve("evil.txt")), "der boesartige Eintrag darf nie ausserhalb landen");
        assertAppDirUnveraendert(appDir);
    }
}
