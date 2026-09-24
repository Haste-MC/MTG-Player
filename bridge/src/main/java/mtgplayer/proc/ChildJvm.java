package mtgplayer.proc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import mtgplayer.forge.ForgeBoot;

/**
 * Startet {@code mtgplayer.Main} in einem frischen JVM-Kindprozess und liest genau eine
 * Ergebniszeile von dessen stdout. Gemeinsame Grundlage von {@code mtgplayer.bench.SubprocessRunner}
 * (Bench, {@code BENCH_RESULT}) und {@code mtgplayer.sparring.SubprocessGameRunner} (Sparring,
 * {@code SPARRING_RESULT}).
 *
 * <p>Grund fuer den Kindprozess je Spiel: ein Forge-eigener Absturz waehrend der Simulation (z. B.
 * {@code GameCopier} "Couldn't map") vergiftet globalen Zustand so, dass Folgespiele in DERSELBEN JVM
 * reihenweise abstuerzen - eine eigene JVM je Spiel ist die robuste Isolation dagegen, mit dem
 * Nebeneffekt eines frischen Heaps. Beim Sparring kommt hinzu, dass Kevins Bridge nebenher laeuft und
 * ein Absturz sie nicht mitnehmen darf.</p>
 */
public final class ChildJvm {

    private ChildJvm() { }

    /** Was ein Kindprozess geliefert hat. {@code value}: die Ergebniszeile ohne ihr Praefix, sonst null. */
    public record Result(boolean timedOut, int exit, String value, long millis) {
        /** Sauber beendet und mit Ergebnis - nur dann darf der Aufrufer {@link #value()} auswerten. */
        public boolean ok() {
            return !timedOut && exit == 0 && value != null;
        }
    }

    /**
     * java-Binary des aktuellen JDK, unabhaengig vom Aufruf (Maven bringt sein eigenes ggf. anderes java
     * nicht mit auf den PATH) - {@code ProcessHandle.current().info().command()} liefert den tatsaechlich
     * benutzten Pfad, sonst {@code java.home}/bin/java als Fallback (z. B. wenn das Betriebssystem den
     * Befehl nicht preisgibt).
     */
    public static final String JAVA_BIN = ProcessHandle.current().info().command()
            .orElseGet(() -> System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");

    /**
     * Klassenpfad fuer den Kindprozess. Zwei sehr unterschiedliche Ausgangslagen je nachdem, wie DIESE
     * JVM gestartet wurde:
     * <ul>
     *   <li>{@code mvn test} (Surefire): forkt selbst eine neue JVM fuer die Tests, {@code java.class.path}
     *       zeigt hier auf den vollstaendigen Testklassenpfad (auf Linux ist die Kommandozeile lang genug,
     *       dass Surefire keine Manifest-Jar-Indirektion braucht) und enthaelt forge-gui/forge-ai/jackson -
     *       direkt verwendbar.</li>
     *   <li>{@code mvn exec:java} (ExecJavaMojo): forkt KEINE neue JVM, sondern laedt {@code mtgplayer.Main}
     *       in DIESER Maven-JVM ueber einen selbstgebauten {@link URLClassLoader}. {@code java.class.path}
     *       zeigt dann nur auf Mavens eigene Launcher-Jars (plexus-classworlds & co.), nicht auf das
     *       Projekt - ein Kindprozess mit diesem Classpath faende {@code mtgplayer.Main} gar nicht. In
     *       diesem Fall stattdessen die URLs von {@code ChildJvm.class.getClassLoader()} nehmen: das ist
     *       unter exec:java genau der URLClassLoader, den ExecJavaMojo aus dem Projekt-Klassenpfad baut
     *       und mit dem es Main laedt (auch dessen Nachbarklassen haengen daran).</li>
     * </ul>
     * Erkennung: enthaelt {@code java.class.path} einen Eintrag, der nach forge-gui/forge-ai/jackson-databind
     * oder einem {@code classes}-Verzeichnis aussieht, gilt er als vollstaendig; sonst der ClassLoader-Pfad,
     * falls der seinerseits vollstaendig aussieht - sonst bestmoeglicher Rueckfall auf java.class.path
     * (z. B. Surefires {@code surefirebooter*.jar}: dessen Manifest-Class-Path liest der NEUE java-Launcher
     * selbst beim Start, ganz ohne unser Zutun, falls Surefire doch einmal eine Manifest-Jar braucht).
     */
    private static final String CLASSPATH = resolveClasspath();

    public static String classpath() {
        return CLASSPATH;
    }

    /**
     * {@code java -Xmx4g -D… -cp <classpath> mtgplayer.Main <mainArgs>}. Weitergereicht werden
     * {@code mtgplayer.assets} (explizit statt auf das geerbte Arbeitsverzeichnis zu vertrauen -
     * {@code ForgeBoot.assetsDir()} loest sonst relativ zum cwd des Kindprozesses auf),
     * {@code mtgplayer.data} (ein Kindprozess erbt KEINE System-Properties, nur Umgebungsvariablen -
     * ohne diese Weitergabe wuerde ein Spiel aus einem isolierten Testlauf trotzdem in Kevins echtes
     * {@code ~/.mtg-player} schreiben) und das Fork-Flag {@code forge.ai.sim.debug}.
     */
    public static List<String> command(List<String> mainArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(JAVA_BIN);
        cmd.add("-Xmx4g");
        cmd.add("-Dmtgplayer.assets=" + ForgeBoot.assetsDir());
        String data = System.getProperty("mtgplayer.data");
        if (data != null) {
            cmd.add("-Dmtgplayer.data=" + data);
        }
        if (Boolean.getBoolean("forge.ai.sim.debug")) {
            cmd.add("-Dforge.ai.sim.debug=true");
        }
        cmd.add("-cp");
        cmd.add(CLASSPATH);
        cmd.add("mtgplayer.Main");
        cmd.addAll(mainArgs);
        return cmd;
    }

    /**
     * Startet den Kindprozess, liest stdout zeilenweise mit und wartet bis zum Zeitlimit (danach
     * {@code destroyForcibly}). Kein Ergebnis, Exit ≠ 0 oder Zeitablauf sind KEINE Ausnahme, sondern
     * stehen im {@link Result} - der Aufrufer entscheidet, ob das ein Absturz genau dieses Spiels ist.
     *
     * @param mainArgs     Argumente hinter {@code mtgplayer.Main}
     * @param resultPrefix Praefix der Ergebniszeile (z. B. {@code "BENCH_RESULT "}); die letzte Zeile mit
     *                     diesem Praefix landet ohne Praefix in {@link Result#value()}
     * @param stderrFile   Datei fuer stderr des Kindes ({@code null} = verwerfen)
     * @param stdoutLines  bekommt jede stdout-Zeile, die nicht die Ergebniszeile ist ({@code null} = verwerfen);
     *                     laeuft auf einem eigenen Lese-Thread, der vor der Rueckkehr beendet wird
     * @param onStart      bekommt den gestarteten Prozess (z. B. um ihn bei Ctrl-C zu beenden), darf null sein
     */
    public static Result run(List<String> mainArgs, String resultPrefix, Path stderrFile,
                             Consumer<String> stdoutLines, long timeout, TimeUnit unit,
                             Consumer<Process> onStart) {
        List<String> cmd = command(mainArgs);
        long t0 = System.currentTimeMillis();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.PIPE);
            pb.redirectError(stderrFile == null ? ProcessBuilder.Redirect.DISCARD
                    : ProcessBuilder.Redirect.to(stderrFile.toFile()));
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("kann Kindprozess nicht starten: " + cmd, e);
        }
        if (onStart != null) {
            onStart.accept(p);
        }

        String[] value = {null};
        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith(resultPrefix)) {
                        value[0] = line.substring(resultPrefix.length());
                    } else if (stdoutLines != null && !line.startsWith("Warning: default")) {
                        stdoutLines.accept(line);
                    }
                }
            } catch (IOException ignored) {
                // Kindprozess weg/gekillt waehrend wir lesen - value bleibt null, der Aufrufer wertet das
                // ueber Result.ok() als Absturz.
            }
        }, "child-jvm-stdout-" + p.pid());
        pump.setDaemon(true);
        pump.start();

        boolean timedOut;
        try {
            timedOut = !p.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            timedOut = true;
        }
        if (timedOut) {
            p.destroyForcibly();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            pump.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new Result(timedOut, exitOf(p), value[0], System.currentTimeMillis() - t0);
    }

    /** Letzte nicht-leere Zeile einer Datei (stderr des Kindes) fuer die Fehlermeldung. */
    public static String lastLine(Path file) {
        if (file == null) {
            return "(kein Log)";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int j = lines.size() - 1; j >= 0; j--) {
                if (!lines.get(j).isBlank()) {
                    return lines.get(j);
                }
            }
            return "(leer)";
        } catch (IOException e) {
            return "(Log nicht lesbar: " + e.getMessage() + ")";
        }
    }

    /** {@code exitValue()} wirft, solange der Prozess lebt - nach einem unterbrochenen {@code waitFor}
     *  kann genau das passieren; dann steht -1 fuer "unbekannt" in der Fehlermeldung. */
    private static int exitOf(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static String resolveClasspath() {
        String cp = System.getProperty("java.class.path", "");
        if (looksLikeProjectClasspath(cp)) {
            return cp;
        }
        ClassLoader cl = ChildJvm.class.getClassLoader();
        if (cl instanceof URLClassLoader u) {
            String joined = joinUrls(u.getURLs());
            if (looksLikeProjectClasspath(joined)) {
                return joined;
            }
        }
        return cp;
    }

    private static boolean looksLikeProjectClasspath(String cp) {
        if (cp == null || cp.isBlank()) {
            return false;
        }
        for (String entry : cp.split(File.pathSeparator)) {
            Path p = Path.of(entry);
            String name = p.getFileName() == null ? entry : p.getFileName().toString();
            if (name.contains("forge-gui") || name.contains("forge-ai") || name.contains("jackson-databind")
                    || name.equals("classes")) {
                return true;
            }
        }
        return false;
    }

    private static String joinUrls(URL[] urls) {
        StringBuilder sb = new StringBuilder();
        for (URL u : urls) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparatorChar);
            }
            try {
                sb.append(Path.of(u.toURI()));
            } catch (Exception e) {
                sb.append(u.getPath());
            }
        }
        return sb.toString();
    }
}
