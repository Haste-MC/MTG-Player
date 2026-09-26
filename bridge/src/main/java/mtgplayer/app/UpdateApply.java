package mtgplayer.app;

import mtgplayer.forge.ForgeBoot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
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
 * (kein Windows, kein {@code cmd.exe}) und bleibt Sache des Aufrufers (Bridge, ueber die eingesetzte
 * {@code UpdateLauncher}-Naht dort, nach einer erfolgreichen "neustart"-Rueckmeldung).</p>
 *
 * <p><b>Review-Nachtrag:</b> das Release-ZIP traegt selbst einen einzigen obersten Ordner (Aufgabe 7
 * baut es so - fuer einen Menschen beim Handentpacken das Richtige). {@link #run} entpackt deshalb roh
 * in einen Zwischenordner und nimmt danach GENAU diesen einen obersten Ordner als neuen App-Ordner -
 * und prueft ihn vor jeder Erfolgsmeldung ({@link #validateAppContent}), waehrend die alte Fassung noch
 * unveraendert laeuft.</p>
 */
public final class UpdateApply {

    /** Name des von jpackage erzeugten Starters im Paket (Spec §2) - fuer die Inhaltspruefung nach dem
     *  Entpacken UND fuer den Start-Befehl im Skript. */
    static final String EXE_NAME = "MTG-Player.exe";
    private static final String VERSION_FILE = "version.txt";
    private static final String OLD_SUFFIX = ".old";

    private final Function<String, byte[]> source;
    /** Wo der Zwischenordner BEVORZUGT entsteht - im Betrieb {@code %TEMP%}, im Test ein eigenes
     *  Verzeichnis (siehe UpdateApplyTest/BridgeApplyUpdateTest: nie das echte System-Temp, damit ein
     *  Test nie ausserhalb seines {@code @TempDir} schreibt oder aufraeumt). {@link #stagingParent}
     *  weicht davon ab, wenn es auf einem anderen Laufwerk als {@code appDir} liegt. */
    private final Path tempDir;

    /** @param source liefert die Bytes einer URL (im Betrieb der echte Download, im Test eine
     *                eingesetzte Funktion); wirft, wenn der Abruf scheitert. */
    public UpdateApply(Function<String, byte[]> source) {
        this(source, Paths.get(System.getProperty("java.io.tmpdir")));
    }

    public UpdateApply() {
        this(UpdateApply::download);
    }

    /** Fuer Tests (auch ausserhalb dieses Pakets, siehe BridgeApplyUpdateTest): eigener Zwischenordner
     *  statt des echten {@code %TEMP%} - oeffentlich wie {@code Edhrec(Function, Path)}, dieselbe
     *  DI-Naht fuer denselben Zweck. */
    public UpdateApply(Function<String, byte[]> source, Path tempDir) {
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
     *  hat dann bereits "fehler" (mit einem Grund) gemeldet. */
    public record Result(Path script) { }

    /**
     * Fuehrt Aufgabe 5 aus dem Design (Spec §5) durch. {@code appDir} ist der aktuelle, laufende
     * App-Ordner - er bleibt bei JEDEM Fehlschlag unveraendert, und auch im Erfolgsfall fasst diese
     * Methode ihn nicht an (das macht erst das Skript). Jeder Schritt meldet seinen Zustand ueber
     * {@code zustand} in der Reihenfolge "laden", "pruefen", "entpacken", "neustart" (Protokoll §6);
     * ein Fehlschlag an jeder Stelle meldet "fehler" MIT einem Klartextgrund (Review-Befund: falsche
     * Pruefsumme, totes Netz und ein bösartiges ZIP muessen fuer den Nutzer unterscheidbar sein) und
     * raeumt einen bereits angelegten Zwischenordner wieder vollstaendig ab, statt ihn liegen zu lassen.
     */
    public Result run(UpdateCheck.Release release, Path appDir, BiConsumer<String, String> zustand) {
        // Wache VOR allem anderen (Review-Befund, kritisch): appDir landet gleich in einem Skript, das
        // diesen Ordner umbenennt und ersetzt - ein falsch abgeleiteter Pfad waere katastrophal.
        String appDirProblem = appDirProblem(appDir);
        if (appDirProblem != null) {
            zustand.accept("fehler", appDirProblem);
            return null;
        }

        Path stagingRoot = null;
        try {
            zustand.accept("laden", null);
            byte[] zip = source.apply(release.url());

            zustand.accept("pruefen", null);
            String actual = sha256Hex(zip);
            if (!actual.equalsIgnoreCase(release.sha256())) {
                zustand.accept("fehler", "Pruefsumme stimmt nicht: erwartet " + release.sha256()
                        + ", erhalten " + actual);
                return null;
            }

            zustand.accept("entpacken", null);
            Path stagingParent = stagingParent(appDir);
            Files.createDirectories(stagingParent);
            stagingRoot = Files.createTempDirectory(stagingParent, "mtg-player-update-");
            unzip(zip, stagingRoot);
            // Das ZIP traegt selbst einen obersten Ordner (Aufgabe 7) - genau der ist der neue App-Ordner.
            Path newDir = singleTopLevelDir(stagingRoot);
            // Vor jeder Erfolgsmeldung pruefen, dass darin wirklich eine App steckt - waehrend die alte
            // Fassung noch unveraendert laeuft, nicht erst wenn das Skript den alten Ordner schon
            // umbenannt hat.
            validateAppContent(newDir);

            String name = appDir.getFileName() != null ? appDir.getFileName().toString() : "MTG-Player";
            Path script = writeScript(stagingRoot, appDir, newDir, name);

            zustand.accept("neustart", null);
            return new Result(script);
        } catch (IOException | RuntimeException | OutOfMemoryError scheitert) {
            // OutOfMemoryError extra (Review-Befund): das ZIP liegt komplett im Speicher, ein zu grosser
            // Download darf den Hintergrund-Thread nicht wortlos sterben lassen - die Lobby wuerde sonst
            // fuer immer auf "laden" haengen.
            zustand.accept("fehler", reasonOf(scheitert));
            if (stagingRoot != null) {
                deleteTree(stagingRoot);
            }
            return null;
        }
    }

    /** Klartextgrund fuer {@code zustand}, statt ihn (Review-Befund) stillschweigend wegzuwerfen. */
    private static String reasonOf(Throwable t) {
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }

    /**
     * BEVOR ueberhaupt etwas passiert (2. Review-Nachtrag, der neue kritische Befund): eine
     * {@code version.txt} allein reichte bisher als Beweis fuer "das ist der App-Ordner" - entpackt
     * jemand das Paket aber mit "hier entpacken" direkt nach {@code C:\Users\...\Downloads}, waere
     * GENAU DAS der App-Ordner, und das Skript wuerde {@code Downloads} umbenennen und (nach einem
     * spaeteren Update) loeschen. Datenverlust auf dem Rechner eines Mitspielers. Diese Wache verlangt
     * deshalb dieselben vier Merkmale wie {@link #validateAppContent} fuer den NEUEN Ordner (Starter,
     * {@code runtime/}, {@code app/}, {@code version.txt}) VOLLSTAENDIG, nicht nur {@code version.txt}.
     * Zusaetzlich in BEIDE Richtungen geprueft gegen das Datenverzeichnis ({@code ~/.mtg-player}, siehe
     * {@link ForgeBoot#dataDir()}): weder darf appDir darunter liegen, noch darf das Datenverzeichnis
     * darunter liegen (die erste Pruefung allein deckt nur eine Richtung ab). Heute schuetzt uns
     * zusaetzlich nur der Zufall, dass {@link Version#current()} in einer Entwicklungsumgebung
     * {@value Version#DEV} liefert und {@code Bridge.checkVersion()} dann gar nicht erst fragt - diese
     * Wache gilt unabhaengig davon.
     *
     * @return eine Fehlermeldung, oder {@code null} wenn appDir in Ordnung ist
     */
    private static String appDirProblem(Path appDir) {
        Path abs = appDir.toAbsolutePath().normalize();
        Path data = ForgeBoot.dataDir().toAbsolutePath().normalize();
        if (abs.startsWith(data) || data.startsWith(abs)) {
            return "App-Ordner " + abs + " ueberschneidet sich mit dem Datenverzeichnis " + data
                    + " - Abbruch vor jeder Aenderung.";
        }
        String contentProblem = appContentProblem(abs);
        if (contentProblem != null) {
            return "App-Ordner " + abs + " " + contentProblem + " - sieht nicht wie eine echte Installation aus.";
        }
        return null;
    }

    /**
     * Wo der Zwischenordner entsteht: bevorzugt {@link #tempDir} (im Betrieb {@code %TEMP%}), aber nur
     * wenn er auf demselben Laufwerk/Dateisystem liegt wie {@code appDir} - sonst scheitert das
     * {@code move} im Skript IMMER (Review-Befund: App auf {@code D:}, TEMP auf {@code C:}, Windows
     * kann Ordner nicht laufwerksuebergreifend umbenennen/verschieben). Dann stattdessen der
     * Elternordner von {@code appDir} selbst - immer noch NICHT der App-Ordner selbst (das Skript liegt
     * nie darin, siehe {@link #writeScript}), aber garantiert dasselbe Laufwerk.
     */
    private Path stagingParent(Path appDir) throws IOException {
        try {
            Files.createDirectories(tempDir);
            FileStore tempStore = Files.getFileStore(tempDir);
            FileStore appStore = Files.getFileStore(appDir);
            if (tempStore.equals(appStore)) {
                return tempDir;
            }
        } catch (IOException ignore) {
            // Vergleich nicht moeglich (z.B. tempDir liess sich nicht anlegen) - im Zweifel die
            // Variante unten, die garantiert auf demselben Laufwerk wie appDir liegt.
        }
        Path parent = appDir.getParent();
        if (parent == null) {
            throw new IOException("App-Ordner " + appDir + " hat keinen Elternordner");
        }
        return parent;
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
     * Entpackt {@code zip} roh nach {@code targetDir} (die eigentliche Ordnersuche/-pruefung passiert
     * danach in {@link #singleTopLevelDir}/{@link #validateAppContent}). Jeder Eintragspfad wird ZWEI
     * Mal geprueft: zuerst textuell ({@link #validateEntryName}, plattformunabhaengig), dann nach der
     * Aufloesung gegen {@code targetDir} - ein ZIP aus dem Netz wird nie blind entpackt, ein Eintrag wie
     * {@code ../../evil.txt} darf niemals ausserhalb des Zielordners landen (Zip-Slip). Das ist keine
     * theoretische Vorsicht - genau dafuer steht diese Pruefung in der Spec.
     */
    private static void unzip(byte[] zip, Path targetDir) throws IOException {
        Path targetAbs = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String rawName = entry.getName();
                validateEntryName(rawName);
                Path out = targetAbs.resolve(rawName).normalize();
                if (!out.startsWith(targetAbs)) {
                    throw new IOException("ZIP-Eintrag ausserhalb des Zielordners: " + rawName);
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
     * Textuelle Vorpruefung VOR jeder Pfadaufloesung (Review-Befund): die reine Aufloesungspruefung in
     * {@link #unzip} erkennt nur ".."-Segmente, die Java als Pfad DEUTET - auf Linux (Testsystem) ist
     * {@code ..\evil} nur ein Dateiname mit Backslash darin, kein Verzeichniswechsel, waehrend genau
     * dieser Eintrag auf Windows (Produktivsystem) sehr wohl aus dem Zielordner fuehrt. Lehnt deshalb
     * JEDEN Eintrag ab, der einen Backslash, einen Doppelpunkt (Laufwerksbuchstabe wie {@code C:}), einen
     * fuehrenden Slash (absoluter Pfad) oder ein ".."-Segment enthaelt - unabhaengig vom
     * Testsystem-Betriebssystem. Ein leerer Name oder {@code "."} wuerde sonst auf den Zielordner selbst
     * aufloesen und ihn durch eine Datei ersetzen.
     */
    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("./")) {
            throw new IOException("ZIP-Eintrag mit leerem/eigenem Pfad: \"" + name + "\"");
        }
        if (name.startsWith("/")) {
            throw new IOException("ZIP-Eintrag mit absolutem Pfad: " + name);
        }
        if (name.contains("\\")) {
            throw new IOException("ZIP-Eintrag mit Backslash im Namen: " + name);
        }
        if (name.contains(":")) {
            throw new IOException("ZIP-Eintrag mit Laufwerksangabe: " + name);
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..")) {
                throw new IOException("ZIP-Eintrag mit \"..\"-Segment: " + name);
            }
        }
    }

    /**
     * Das Release-ZIP enthaelt selbst einen einzelnen obersten Ordner (Aufgabe 7 baut es so - fuer
     * einen Menschen beim Handentpacken das Richtige). Genau dieser Ordner ist der neue App-Ordner;
     * alles andere (kein Ordner, mehrere, eine lose Datei obendrauf) ist ein unerwartetes Paket.
     */
    private static Path singleTopLevelDir(Path stagingRoot) throws IOException {
        List<Path> entries;
        try (var stream = Files.list(stagingRoot)) {
            entries = stream.toList();
        }
        if (entries.size() != 1 || !Files.isDirectory(entries.get(0))) {
            throw new IOException("ZIP enthaelt nicht genau einen obersten Ordner (" + entries.size() + " Eintraege oben)");
        }
        return entries.get(0);
    }

    /**
     * Vor jeder Erfolgsmeldung (Review-Befund, kritisch): steckt im entpackten Ordner ueberhaupt eine
     * App? Ein kaputtes/unvollstaendiges Release-ZIP soll VOR dem Schreiben des Tauschskripts als
     * "fehler" enden, waehrend die alte Fassung noch unveraendert laeuft - nicht erst beim Tausch
     * selbst auffallen, wenn der alte Ordner schon umbenannt ist. Dieselben vier Merkmale wie
     * {@link #appDirProblem} fuer den ALTEN Ordner (2. Review-Nachtrag: beide muessen gleich streng
     * sein, sonst waere z.B. ein Downloads-Ordner ohne {@code runtime/}/{@code app/} als "alt" akzeptabel
     * gewesen, aber nicht als "neu" - das ergibt keinen Sinn, es geht um dieselbe Frage).
     */
    private static void validateAppContent(Path newDir) throws IOException {
        String problem = appContentProblem(newDir);
        if (problem != null) {
            throw new IOException("entpackter Ordner " + problem);
        }
    }

    /**
     * Die vier Merkmale einer echten Paket-Installation (Spec §2: {@code MTG-Player.exe}, {@code
     * runtime/}, {@code app/}, {@code version.txt} liegen nebeneinander auf oberster Ebene) - gemeinsam
     * genutzt von {@link #appDirProblem} (fuer den ALTEN Ordner) und {@link #validateAppContent} (fuer
     * den NEUEN, entpackten Ordner). Absichtlich dieselbe, vollstaendige Pruefung fuer beide (2.
     * Review-Nachtrag): eine einzelne {@code version.txt} reicht nicht als Beweis - die koennte auch in
     * einem Downloads-Ordner liegen, in den jemand das ZIP nur ausgepackt, aber nie an seinen Zielort
     * verschoben hat.
     *
     * @return eine Kurzbeschreibung des fehlenden Merkmals, oder {@code null} wenn alle vier da sind
     */
    private static String appContentProblem(Path dir) {
        if (!Files.isRegularFile(dir.resolve(VERSION_FILE))) {
            return "enthaelt kein " + VERSION_FILE;
        }
        if (!Files.isRegularFile(dir.resolve(EXE_NAME))) {
            return "enthaelt keinen Starter (" + EXE_NAME + ")";
        }
        if (!Files.isDirectory(dir.resolve("runtime"))) {
            return "enthaelt kein runtime/-Verzeichnis";
        }
        if (!Files.isDirectory(dir.resolve("app"))) {
            return "enthaelt kein app/-Verzeichnis";
        }
        return null;
    }

    /**
     * Schreibt {@code update.cmd} neben den entpackten Zwischenordner (beide teilen sich
     * {@code stagingRoot}). Reihenfolge im Skript ist die Sicherheit (Spec §5): erst bestaetigt es,
     * dass dieser Prozess (per PID) wirklich lief und dann beendet ist - vorher haelt Windows die
     * eigenen Dateien fest -, dann legt es den alten App-Ordner als {@code <Name>.old} beiseite,
     * schiebt den neuen an seine Stelle und startet die neue Fassung. Scheitert ein Schritt, benennt
     * es zurueck UND startet die alte Fassung erneut (2. Review-Nachtrag: das galt bisher nicht fuer
     * den wahrscheinlichsten Fehlschlag, den fehlgeschlagenen {@code ren}) - nie beide Ordner
     * gleichzeitig stehen lassen, nie die App einfach verschwinden lassen.
     *
     * <p><b>{@code <Name>.old} wird NICHT automatisch geloescht</b> (2. Review-Nachtrag, Entscheidung
     * B): ein {@code rmdir} auf einen Ordner, dessen Inhalt wir nur vermuten, ist das Risiko nicht
     * wert. Er bleibt liegen; das Protokoll ({@code update.log}, siehe unten) nennt seinen Pfad, damit
     * der Nutzer ihn von Hand loeschen kann.</p>
     *
     * <p><b>3. Review-Nachtrag:</b> ein liegengebliebenes {@code .old} aus einem FRUEHEREN Update wurde
     * bisher schon VOR diesem Tausch geloescht, um Platz fuer das neue {@code .old} zu machen - loescht
     * danach {@code ren}/{@code move}/{@code start} nicht, ist die alte Sicherungskopie schon weg,
     * obwohl DIESER Tausch nie stattfand (widerspricht Entscheidung B: die Sicherung darf erst weg,
     * wenn ein erfolgreicher Lauf der NEUEN Fassung das belegt). Ein vorhandenes {@code .old} wird
     * deshalb jetzt nur beiseitegeschoben (nach {@code <Name>.old.previous}, siehe {@code oldPrevAbs}
     * unten) statt geloescht - endgueltig weg ist es erst NACH dem erfolgreichen Start der neuen
     * Fassung; scheitert irgendetwas davor, kommt es an genau dieser Stelle zurueck.</p>
     */
    private static Path writeScript(Path stagingRoot, Path appDir, Path newDir, String name) throws IOException {
        Path script = stagingRoot.resolve("update.cmd");
        long pid = ProcessHandle.current().pid();
        String appAbs = appDir.toAbsolutePath().normalize().toString();
        String newAbs = newDir.toAbsolutePath().normalize().toString();
        String stagingAbs = stagingRoot.toAbsolutePath().normalize().toString();
        // ren benennt IMMER innerhalb desselben Elternordners um - der volle Pfad des beiseitegelegten
        // Ordners ist deshalb einfach der Nachbar von appDir mit der Endung ".old". Das hier in Java
        // auszurechnen (statt im Batch mit %~dp/%~nx zu hantieren) haelt das Skript selbst simpel.
        String oldAbs = appDir.resolveSibling(name + OLD_SUFFIX).toAbsolutePath().normalize().toString();
        // Zwischenname fuer ein bereits vorhandenes .old (3. Review-Nachtrag) - derselbe Nachbar-Trick
        // wie bei oldAbs.
        String oldPrevAbs = appDir.resolveSibling(name + OLD_SUFFIX + ".previous").toAbsolutePath().normalize().toString();
        // update.log liegt im Datenverzeichnis (2. Review-Nachtrag, Befund 3): das Skript laeuft NACH
        // dem Ende der JVM, seine echo-Ausgaben gehen an niemanden (die Pipes sterben mit dem Prozess,
        // der sie angelegt hat) - ohne eigene Datei waere ein gescheitertes Update nie nachvollziehbar.
        String dataDirAbs = ForgeBoot.dataDir().toAbsolutePath().normalize().toString();
        String logAbs = ForgeBoot.dataDir().resolve("update.log").toAbsolutePath().normalize().toString();

        String content = """
                @echo off
                rem UTF-8-Codepage (Review-Befund): sonst verstuemmelt ein Pfad mit Umlaut (z.B. "Buero")
                rem die Ordnervariablen unten und jeder Vergleich/ren/move darauf scheitert.
                chcp 65001 >nul
                setlocal
                rem Arbeitsverzeichnis auf den eigenen Ordner setzen (Review-Befund, Blocker 1): ohne dieses
                rem cd erbt cmd.exe sonst den App-Ordner als Arbeitsverzeichnis (ProcessBuilder setzt keins),
                rem und Windows kann ein Verzeichnis nicht umbenennen, das das Arbeitsverzeichnis eines
                rem laufenden Prozesses ist - "ren" wuerde scheitern, ohne dass jemand sieht warum.
                cd /d "%%~dp0"

                set "PID=%d"
                set "NAME=%s"
                set "APPDIR=%s"
                set "NEWDIR=%s"
                set "OLDDIR=%s"
                set "OLDPREV=%s"
                set "STAGING=%s"
                set "DATADIR=%s"
                set "LOG=%s"

                if not exist "%%DATADIR%%" mkdir "%%DATADIR%%" 2>nul
                call :log "Update-Tausch gestartet (PID %%PID%%): %%NEWDIR%% -> %%APPDIR%%"

                rem Erst bestaetigen, dass die alte MTG-Player.exe wirklich gesehen wurde (tasklist koennte
                rem beim allerersten Versuch aus Zeitgruenden noch nichts liefern) - erst DANACH gilt "nicht
                rem mehr gefunden" als Beweis, dass der Prozess wirklich beendet ist. 2. Review-Nachtrag,
                rem Befund 1: BEIDE Schleifen brechen bei Ablauf jetzt wirklich ab (exit /b 1, nichts
                rem angefasst) statt stillschweigend in den Tausch hineinzufallen - haengt die App beim
                rem Beenden oder liefert tasklist nie einen Treffer, waere sonst bei LAUFENDER App getauscht
                rem worden.
                set "TRIES=0"
                :confirmloop
                tasklist /fi "PID eq %%PID%%" 2>nul | find "%%PID%%" >nul
                if errorlevel 1 goto confirmnotfound
                goto waitstart
                :confirmnotfound
                set /a TRIES=%%TRIES%%+1
                rem 4. Review-Nachtrag, Blocker 5: mehr Versuche (30 statt 5 - dieselbe knapp
                rem einsekuendige ping-Wartezeit je Versuch, also rund 30s statt 5s) - der
                rem wahrscheinlichste Grund fuer einen fruehen Fehlschlag ist reine Verzoegerung
                rem (tasklist braucht selbst einen Moment, um anzulaufen), nicht ein wirklich schon
                rem verschwundener Prozess.
                if %%TRIES%% GEQ 30 goto confirmtimeout
                ping -n 2 127.0.0.1 >nul
                goto confirmloop
                :confirmtimeout
                rem 4. Review-Nachtrag, Blocker 5 (kritisch): verliert diese Schleife das Rennen (die
                rem alte Fassung war schon weg, bevor tasklist sie auch nur einmal gesehen hat), ist
                rem appDir noch vollkommen unangetastet - die alte Fassung ist aber trotzdem beendet,
                rem also MUSS sie hier erneut gestartet werden. Ohne diese Zeile stand hier bisher
                rem nichts, das die App wieder oeffnet - sie war nach einem verlorenen Rennen einfach zu.
                call :log "Abbruch: alte Fassung (PID %%PID%%) konnte nicht bestaetigt werden - alte Fassung wird erneut gestartet, nichts geaendert."
                start "" /d "%%APPDIR%%" "%%APPDIR%%\\%10$s"
                call :cleanup
                exit /b 1

                :waitstart
                set "TRIES=0"
                :waitloop
                tasklist /fi "PID eq %%PID%%" 2>nul | find "%%PID%%" >nul
                if errorlevel 1 goto waitdone
                set /a TRIES=%%TRIES%%+1
                if %%TRIES%% GEQ 300 goto waittimeout
                ping -n 2 127.0.0.1 >nul
                goto waitloop
                :waittimeout
                call :log "Abbruch: Zeitlimit beim Warten auf das Ende der alten Fassung (PID %%PID%%) - nichts geaendert."
                call :cleanup
                exit /b 1
                :waitdone

                rem Ein liegengebliebener .old-Ordner aus einem FRUEHEREN Update wird beiseitegeschoben,
                rem NICHT geloescht (3. Review-Nachtrag): er bleibt die Rueckfallebene, bis DIESER neue
                rem Tausch nachweislich geklappt hat (Entscheidung B) - macht aber Platz, damit gleich
                rem "%%APPDIR%%" nach ".old" umbenannt werden kann. Ein ganz altes OLDPREV (von einem noch
                rem frueheren, abgebrochenen Versuch) darf hier weichen - es ist laengst durch OLDDIR ersetzt.
                if exist "%%OLDDIR%%" (
                    call :log "Vorhandenes %%OLDDIR%% wird beiseitegeschoben, bis der neue Tausch bestaetigt ist."
                    if exist "%%OLDPREV%%" rmdir /s /q "%%OLDPREV%%" 2>nul
                    ren "%%OLDDIR%%" "%%NAME%%.old.previous"
                )

                rem alten Ordner beiseite legen, bevor irgendetwas Neues an seine Stelle kommt
                ren "%%APPDIR%%" "%%NAME%%.old"
                rem 4. Review-Nachtrag, Blocker 6 (kritisch): errorlevel nach "ren" ist NICHT
                rem zuverlaessig genug, um sich allein darauf zu verlassen - bleibt es in manchen
                rem Umgebungen bei 0, obwohl ren in Wahrheit nichts getan hat, faellt der Tausch
                rem unten geradewegs in "move" - und move verschiebt den neuen Ordner dann HINEIN in
                rem den immer noch vorhandenen alten (Windows-Semantik "Ziel existiert bereits als
                rem Ordner"), meldet selbst wieder Erfolg, und der Nutzer hat ein stilles Nicht-Update
                rem plus verschachtelten Muell. Der einzige verlaessliche Beweis: existiert %%APPDIR%%
                rem immer noch unter seinem alten Namen, hat ren nicht gewirkt - unabhaengig davon, was
                rem errorlevel sagt.
                if exist "%%APPDIR%%" (
                    rem 2. Review-Nachtrag, Befund 2: der wahrscheinlichste Fehlschlag ueberhaupt - hier
                    rem stand bisher NICHTS, das die App wieder startet. %%APPDIR%% hat sich nicht
                    rem geaendert (ren ist fehlgeschlagen), die alte Fassung liegt also unveraendert dort.
                    call :log "Update fehlgeschlagen: alter Ordner liess sich nicht umbenennen - alte Fassung wird erneut gestartet."
                    rem das beiseitegeschobene .old zurueckholen - an diesem Tausch hat sich sonst nichts geaendert.
                    if exist "%%OLDPREV%%" ren "%%OLDPREV%%" "%%NAME%%.old"
                    start "" /d "%%APPDIR%%" "%%APPDIR%%\\%10$s"
                    call :cleanup
                    exit /b 1
                )

                rem den entpackten neuen Stand an die Stelle des alten schieben
                move /y "%%NEWDIR%%" "%%APPDIR%%"
                rem 4. Review-Nachtrag, Blocker 6 (kritisch): auch hier nicht auf errorlevel verlassen -
                rem existiert %%APPDIR%% zum Zeitpunkt des move schon als Ordner (siehe Kommentar oben:
                rem genau der Fall, wenn der ren-Schritt in Wahrheit nicht gewirkt hatte), verschiebt
                rem move %%NEWDIR%% HINEIN statt es zu ersetzen - der neue Starter liegt danach eine Ebene
                rem zu tief (unter %%APPDIR%%\\Name-von-NEWDIR\\, nicht direkt unter %%APPDIR%%), und
                rem errorlevel bleibt trotzdem 0. Der einzige verlaessliche Beweis fuer einen ECHTEN
                rem Tausch: der neue Starter liegt DIREKT unter %%APPDIR%%.
                if not exist "%%APPDIR%%\\%10$s" (
                    call :log "Update fehlgeschlagen: neuer Ordner liess sich nicht an die richtige Stelle verschieben - alter Stand kommt zurueck."
                    rem 2. Review-Nachtrag, Befund 4: ein gescheitertes/verschachteltes move kann trotzdem
                    rem einen Teilstand unter APPDIR hinterlassen - genau wie im start-Fehlerzweig unten
                    rem erst wegraeumen, sonst scheitert der Rueckweg am belegten Namen und es stehen am
                    rem Ende BEIDE Ordner da.
                    if exist "%%APPDIR%%" rmdir /s /q "%%APPDIR%%" 2>nul
                    goto restore_old
                )

                rem die neue Fassung starten - /d setzt ihr Arbeitsverzeichnis explizit auf APPDIR (ohne
                rem /d wuerde start das Arbeitsverzeichnis DIESES Skripts vererben, also einen Ordner
                rem unter %%TEMP%%, siehe cd /d oben).
                start "" /d "%%APPDIR%%" "%%APPDIR%%\\%s"
                if errorlevel 1 (
                    call :log "Update fehlgeschlagen: neue Fassung startet nicht - alter Stand kommt zurueck."
                    if exist "%%APPDIR%%" rmdir /s /q "%%APPDIR%%" 2>nul
                    goto restore_old
                )

                rem Erfolg: der FRISCHE alte Stand (OLDDIR, gerade erst entstanden) wird NICHT geloescht
                rem (Entscheidung B) - er bleibt liegen, bis der Nutzer ihn von Hand entfernt oder das
                rem naechste Update ihn ersetzt. Die AELTERE, beiseitegeschobene Sicherung (OLDPREV) darf
                rem jetzt aber wirklich weg - die neue Fassung hat gerade erfolgreich gestartet, genau der
                rem Beweis, den Entscheidung B verlangt (3. Review-Nachtrag).
                if exist "%%OLDPREV%%" rmdir /s /q "%%OLDPREV%%" 2>nul
                call :log "Update erfolgreich. Alter Ordner bleibt erhalten unter %%OLDDIR%% - kann von Hand geloescht werden."
                call :cleanup
                exit /b 0

                :restore_old
                rem Bestmoegliche Wiederherstellung (Blocker 2): scheitert das Zurueckbenennen ebenfalls,
                rem existieren NICHT beide Ordner gleichzeitig weiter, sondern das Skript bricht mit
                rem einer klaren Meldung ab, statt den verbotenen Zustand still zu hinterlassen.
                if exist "%%APPDIR%%" (
                    call :log "Update fehlgeschlagen: %%APPDIR%% ist noch belegt - Wiederherstellung nicht moeglich, bitte von Hand pruefen."
                    call :cleanup
                    exit /b 1
                )
                ren "%%OLDDIR%%" "%%NAME%%"
                rem 4. Review-Nachtrag, Blocker 6: derselbe Zustands- statt Rueckgabewert-Beweis wie
                rem beim ersten "ren" oben - existiert %%OLDDIR%% immer noch, hat das Zurueckbenennen
                rem nicht gewirkt, unabhaengig von errorlevel.
                if exist "%%OLDDIR%%" (
                    call :log "Update fehlgeschlagen: alter Ordner liess sich nicht zurueckbenennen - bitte von Hand pruefen."
                    call :cleanup
                    exit /b 1
                )
                rem die beiseitegeschobene AELTERE Sicherung (3. Review-Nachtrag) ebenfalls zurueckholen -
                rem nach einem gescheiterten Tausch soll alles wieder genau so daliegen wie davor.
                if exist "%%OLDPREV%%" ren "%%OLDPREV%%" "%%NAME%%.old"
                rem alte Fassung erneut starten - ein Fehlschlag darf nie "die App ist einfach weg" bedeuten.
                start "" /d "%%APPDIR%%" "%%APPDIR%%\\%10$s"
                call :log "Alte Fassung nach fehlgeschlagenem Update erneut gestartet."
                call :cleanup
                exit /b 1

                :cleanup
                rem Den eigenen Zwischenordner (samt dieses Skripts) erst nach einer kurzen Verzoegerung in
                rem einem eigenen, losgeloesten Prozess loeschen (Review-Befund, Aufraeumen): waehrend dieses
                rem Skript noch laeuft, haelt cmd.exe seine eigene Datei fest. update.log liegt NICHT hier
                rem (siehe DATADIR oben), bleibt also erhalten.
                rem 4. Review-Nachtrag, Blocker 7 (kritisch): OHNE "/d %%DATADIR%%" erbt der gestartete
                rem cmd sein Arbeitsverzeichnis von DIESEM Skript - und das ist (siehe "cd /d %%~dp0" ganz
                rem oben) %%STAGING%% selbst. Windows kann ein Verzeichnis nicht loeschen, das das
                rem Arbeitsverzeichnis eines laufenden Prozesses ist - genau derselbe Grund, aus dem
                rem dieses Skript zu Beginn in seinen eigenen Ordner wechselt, gilt hier fuer den
                rem aufraeumenden Prozess ebenso. %%DATADIR%% existiert garantiert (siehe mkdir oben) und
                rem ist nie der zu loeschende Ordner.
                start "" /min /d "%%DATADIR%%" cmd /c "ping -n 3 127.0.0.1 >nul & rmdir /s /q ""%%STAGING%%"" 2>nul"
                goto :eof

                :log
                rem 2. Review-Nachtrag, Befund 3: eine einfache Konsolenausgabe reicht nicht - die Pipes
                rem sterben mit der bereits beendeten JVM, niemand sieht sie je. Deshalb zusaetzlich in
                rem eine eigene Datei im Datenverzeichnis schreiben, die den Neustart ueberlebt.
                echo %%~1
                >>"%%LOG%%" echo %%date%% %%time%% %%~1
                goto :eof
                """.formatted(pid, name, appAbs, newAbs, oldAbs, oldPrevAbs, stagingAbs, dataDirAbs, logAbs,
                EXE_NAME, EXE_NAME, EXE_NAME);
        // cmd.exe braucht CRLF-Zeilenenden (Review-Befund) - der Text-Block liefert reines "\n", sonst
        // brechen goto-Spruenge und die geklammerten if-Bloecke auf Windows.
        Files.writeString(script, content.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        return script;
    }

    /** Raeumt einen bereits angelegten Zwischenordner nach einem Fehlschlag vollstaendig ab - er liegt
     *  IMMER unter {@link #stagingParent}, nie irgendwo sonst. */
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
