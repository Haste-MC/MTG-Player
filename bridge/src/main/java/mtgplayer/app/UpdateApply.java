package mtgplayer.app;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Wendet ein erkanntes Update an (Spec 2026-09-26-app-paket-design.md §5, Aufgabe 5 nach der reinen
 * Erkennung aus Aufgabe 4/{@link UpdateCheck}): herunterladen, Pruefsumme rechnen und vergleichen, in
 * einen Zwischenordner entpacken, ein {@code update.cmd} daneben schreiben. Wie {@link UpdateCheck}/
 * {@link mtgplayer.decks.Edhrec}: der Abruf ist eine einsetzbare Funktion (URL -&gt; Bytes statt Text,
 * das ZIP ist binaer), damit kein Test ins Netz geht.
 *
 * <p><b>Was hier bewusst NICHT passiert:</b> das eigentliche Vertauschen der Ordner. Waehrend diese JVM
 * noch laeuft, haelt Windows ihre eigenen Dateien (Jars, die {@code .exe}) fest - ein Tausch koennte gar
 * nicht klappen. Deshalb steckt die ganze Tausch-Logik im geschriebenen Skript (siehe
 * {@link #writeScript}), das erst NACH dem Ende dieses Prozesses laeuft, und {@link #run} startet dieses
 * Skript selbst nicht und beendet auch nicht die JVM - beides waere hier ohnehin nicht ehrlich testbar
 * (kein Windows, kein {@code cmd.exe}) und bleibt Sache des Aufrufers (Bridge, nach einer erfolgreichen
 * "neustart"-Rueckmeldung).</p>
 */
public final class UpdateApply {

    /** Endung des beiseitegelegten alten Ordners, siehe {@link #writeScript}. */
    private static final String OLD_SUFFIX = ".old";

    private final Function<String, byte[]> source;
    /** Wo der Zwischenordner entsteht - im Betrieb {@code %TEMP%}, im Test ein eigenes Verzeichnis
     *  (siehe UpdateApplyTest: nie das echte System-Temp, damit ein Test nie ausserhalb seines
     *  {@code @TempDir} schreibt oder aufraeumt). */
    private final Path tempDir;

    /** @param source liefert die Bytes einer URL (im Betrieb der echte Download, im Test eine
     *                eingesetzte Funktion); wirft, wenn der Abruf scheitert. */
    public UpdateApply(Function<String, byte[]> source) {
        this(source, Paths.get(System.getProperty("java.io.tmpdir")));
    }

    public UpdateApply() {
        this(UpdateApply::download);
    }

    /** Fuer Tests: eigener Zwischenordner statt des echten {@code %TEMP%}. */
    UpdateApply(Function<String, byte[]> source, Path tempDir) {
        this.source = source;
        this.tempDir = tempDir;
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Echter Abruf, wie {@link UpdateCheck#fetch} - nur Bytes statt Text, das ZIP ist binaer. Eine
     *  laengere Zeitgrenze als bei der reinen Fassungspruefung: hier laedt ein mehrere Megabyte
     *  grosses Paket, nicht nur ein kurzes JSON. */
    private static byte[] download(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                .timeout(Duration.ofSeconds(120)).GET().build();
        try {
            HttpResponse<byte[]> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new RuntimeException("HTTP " + res.statusCode());
            }
            return res.body();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Ergebnis eines erfolgreichen {@link #run}: {@code script} zeigt auf das geschriebene
     *  {@code update.cmd}. Bei einem Fehlschlag liefert {@link #run} {@code null} - {@code zustand}
     *  hat dann bereits "fehler" gemeldet. */
    public record Result(Path script) { }

    /**
     * Fuehrt Aufgabe 5 aus dem Design (Spec §5) durch. {@code appDir} ist der aktuelle, laufende
     * App-Ordner - er bleibt bei JEDEM Fehlschlag unveraendert, und auch im Erfolgsfall fasst diese
     * Methode ihn nicht an (das macht erst das Skript). Jeder Schritt meldet seinen Zustand ueber
     * {@code zustand} in der Reihenfolge "laden", "pruefen", "entpacken", "neustart" (Protokoll §6);
     * ein Fehlschlag an jeder Stelle meldet "fehler" und raeumt einen bereits angelegten
     * Zwischenordner wieder vollstaendig ab, statt ihn liegen zu lassen - ein halb entpacktes
     * Verzeichnis waere beim naechsten Versuch nur Verwirrung.
     */
    public Result run(UpdateCheck.Release release, Path appDir, Consumer<String> zustand) {
        Path stagingRoot = null;
        try {
            zustand.accept("laden");
            byte[] zip = source.apply(release.url());

            zustand.accept("pruefen");
            String actual = sha256Hex(zip);
            if (!actual.equalsIgnoreCase(release.sha256())) {
                zustand.accept("fehler");
                return null;
            }

            zustand.accept("entpacken");
            String name = appDir.getFileName() != null ? appDir.getFileName().toString() : "MTG-Player";
            Files.createDirectories(tempDir);
            stagingRoot = Files.createTempDirectory(tempDir, "mtg-player-update-");
            Path newDir = stagingRoot.resolve(name);
            Files.createDirectories(newDir);
            unzip(zip, newDir);

            Path script = writeScript(stagingRoot, appDir, newDir, name);

            zustand.accept("neustart");
            return new Result(script);
        } catch (IOException | RuntimeException scheitert) {
            zustand.accept("fehler");
            if (stagingRoot != null) {
                deleteTree(stagingRoot);
            }
            return null;
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 ist ein Pflichtalgorithmus jeder JVM (java.security.MessageDigest-Doku) - kann nicht passieren.
            throw new RuntimeException(e);
        }
    }

    /**
     * Entpackt {@code zip} nach {@code targetDir}. Jeder Eintragspfad wird gegen {@code targetDir}
     * geprueft (aufgeloest UND normalisiert): ein ZIP aus dem Netz wird nie blind entpackt, ein
     * Eintrag wie {@code ../../evil.txt} darf niemals ausserhalb des Zielordners landen (Zip-Slip).
     * Das ist keine theoretische Vorsicht - genau dafuer steht diese Pruefung in der Spec.
     */
    private static void unzip(byte[] zip, Path targetDir) throws IOException {
        Path targetAbs = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = targetAbs.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetAbs)) {
                    throw new IOException("ZIP-Eintrag ausserhalb des Zielordners: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Path parent = out.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Schreibt {@code update.cmd} neben den entpackten Zwischenordner (beide teilen sich
     * {@code stagingRoot}). Reihenfolge im Skript ist die Sicherheit (Spec §5): erst wartet es, bis
     * dieser Prozess (per PID) wirklich beendet ist - vorher haelt Windows die eigenen Dateien fest -,
     * dann legt es den alten App-Ordner als {@code <Name>.old} beiseite, schiebt den neuen an seine
     * Stelle, startet die neue Fassung und loescht {@code .old} erst NACH dem erfolgreichen Start.
     * Scheitert ein Schritt, benennt es zurueck statt einen halb ausgetauschten Ordner zu hinterlassen.
     */
    private static Path writeScript(Path stagingRoot, Path appDir, Path newDir, String name) throws IOException {
        Path script = stagingRoot.resolve("update.cmd");
        long pid = ProcessHandle.current().pid();
        String appAbs = appDir.toAbsolutePath().normalize().toString();
        String newAbs = newDir.toAbsolutePath().normalize().toString();
        // ren benennt IMMER innerhalb desselben Elternordners um - der volle Pfad des beiseitegelegten
        // Ordners ist deshalb einfach der Nachbar von appDir mit der Endung ".old". Das hier in Java
        // auszurechnen (statt im Batch mit %~dp/%~nx zu hantieren) haelt das Skript selbst simpel:
        // reines ren/move mit fest eingesetzten Namen, ohne Batch-Pfadmagie, die sich hier nicht testen liesse.
        String oldAbs = appDir.resolveSibling(name + OLD_SUFFIX).toAbsolutePath().normalize().toString();

        String content = """
                @echo off
                setlocal

                rem Aufgabe 5 (App-Paket): dieses Skript liegt bewusst in %%TEMP%%, nicht im App-Ordner -
                rem sonst wuerde es den Ordner tauschen wollen, in dem es selbst liegt, und Windows haelt
                rem seine eigene .cmd-Datei fest, solange sie laeuft.

                set "PID=%d"
                set "NAME=%s"
                set "APPDIR=%s"
                set "NEWDIR=%s"
                set "OLDDIR=%s"

                rem Erst warten, bis die alte MTG-Player.exe wirklich beendet ist - vorher haelt Windows
                rem ihre Jars/die exe selbst fest, ein Umbenennen wuerde fehlschlagen.
                :waitloop
                tasklist /fi "PID eq %%PID%%" 2>nul | find "%%PID%%" >nul
                if not errorlevel 1 (
                    timeout /t 1 /nobreak >nul
                    goto waitloop
                )

                rem alten Ordner beiseite legen, bevor irgendetwas Neues an seine Stelle kommt
                ren "%%APPDIR%%" "%%NAME%%.old"
                if errorlevel 1 (
                    echo Update fehlgeschlagen: alter Ordner liess sich nicht umbenennen.
                    exit /b 1
                )

                rem den entpackten neuen Stand an die Stelle des alten schieben
                move /y "%%NEWDIR%%" "%%APPDIR%%"
                if errorlevel 1 (
                    echo Update fehlgeschlagen: neuer Ordner liess sich nicht verschieben - alter Stand kommt zurueck.
                    ren "%%OLDDIR%%" "%%NAME%%"
                    exit /b 1
                )

                rem die neue Fassung starten
                start "" "%%APPDIR%%\\MTG-Player.exe"
                if errorlevel 1 (
                    echo Update fehlgeschlagen: neue Fassung startet nicht - alter Stand kommt zurueck.
                    rmdir /s /q "%%APPDIR%%" 2>nul
                    ren "%%OLDDIR%%" "%%NAME%%"
                    exit /b 1
                )

                rem Erfolg: der alte Stand wird nicht mehr gebraucht
                rmdir /s /q "%%OLDDIR%%" 2>nul

                exit /b 0
                """.formatted(pid, name, appAbs, newAbs, oldAbs);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        return script;
    }

    /** Raeumt einen bereits angelegten Zwischenordner nach einem Fehlschlag vollstaendig ab - er
     *  liegt IMMER unter {@link #tempDir} (siehe {@link #run}), nie irgendwo sonst. */
    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // bestmoegliches Aufraeumen - ein einzelner nicht loeschbarer Rest (z.B. Virenscanner
                    // haelt eine Datei kurz fest) darf den Rest des Abraeumens nicht verhindern
                }
            });
        } catch (IOException ignore) {
            // dir existiert nicht (mehr) oder ist nicht lesbar - nichts abzuraeumen
        }
    }
}
