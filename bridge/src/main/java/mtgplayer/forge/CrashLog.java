package mtgplayer.forge;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Abbrueche des Spiel-Threads sichtbar machen: Forge meldet sie ueber {@code showBugReportDialog} oder als
 * uncaught exception, die Bridge-JVM lief bisher einfach weiter und der Tisch im Browser wirkte "eingefroren"
 * (der Spiel-Thread ist tot, kein Prompt kommt mehr). Jeder Abbruch landet jetzt mit Stacktrace in
 * {@code ~/.mtg-player/logs/bridge.log} und - wenn ein Listener registriert ist (Bridge) - als Fehlermeldung
 * im Browser-Log.
 */
public final class CrashLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static volatile Consumer<String> listener;
    private static volatile Path fileOverride;

    private CrashLog() { }

    public static void setListener(Consumer<String> l) {
        listener = l;
    }

    /** Zieldatei umlenken (Tests); {@code null} = wieder {@code ~/.mtg-player/logs/bridge.log}. */
    public static void setFile(Path f) {
        fileOverride = f;
    }

    public static Path file() {
        Path f = fileOverride;
        return f != null ? f : ForgeBoot.dataDir().resolve("logs").resolve("bridge.log");
    }

    /** @param title kurze Ueberschrift (Thread-Name oder Forge-Titel), @param text Forge-Text oder null */
    public static synchronized void report(String title, String text, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(STAMP.format(LocalDateTime.now())).append(' ').append(title);
        if (text != null && !text.isBlank()) {
            sb.append(": ").append(text.strip());
        }
        sb.append('\n');
        if (e != null) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            sb.append(sw);
        }
        sb.append('\n');
        String entry = sb.toString();
        System.err.print(entry);
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.writeString(f, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException io) {
            System.err.println("[crashlog] konnte nicht schreiben: " + io);
        }
        Consumer<String> l = listener;
        if (l != null) {
            String first = e == null ? (text == null ? title : text.strip().lines().findFirst().orElse(title))
                    : e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            l.accept("Spiel abgebrochen (" + title + "): " + first + " – Details in " + file());
        }
    }
}
