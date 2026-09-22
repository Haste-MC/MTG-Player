package mtgplayer.stats;

import com.fasterxml.jackson.core.type.TypeReference;
import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Gespielte Partien ({@link MatchRecord}) als ein JSON-Array in einer Datei, aelteste zuerst. Schreiben
 * ist atomar (eindeutige Temp-Datei je Aufruf + {@code ATOMIC_MOVE}), damit ein Absturz waehrend des
 * Schreibens nie eine halbe Datei hinterlaesst. Eine kaputte Datei (fremder Inhalt, abgebrochener
 * Schreibvorgang vor Einfuehrung des atomaren Schreibens, ...) liefert {@link #all()} als leere Liste
 * statt eines Absturzes und meldet sich per {@link CrashLog} in {@code bridge.log} - die Datei wird beim
 * naechsten {@link #add}/{@link #delete}/{@link #setCounted} ersetzt. Alle vier Methoden sind
 * {@code synchronized}: der Forge-Spiel-Thread ruft {@link #add} auf, waehrend der WebSocket-Thread
 * gleichzeitig {@link #delete}/{@link #setCounted} (und {@link #all}) aufrufen kann - ohne Sperre waere
 * das ein verlorenes Update (Read-Modify-Write auf derselben Liste).
 */
public final class MatchStore {

    public static final int MAX = 2000;

    private final Path file;

    public MatchStore(Path file) {
        this.file = file;
    }

    public static MatchStore standard() {
        return new MatchStore(ForgeBoot.dataDir().resolve("matches.json"));
    }

    /** @return alle Partien, aelteste zuerst; fehlende oder kaputte Datei -&gt; leere Liste */
    public synchronized List<MatchRecord> all() {
        if (!Files.isRegularFile(file)) return new ArrayList<>();
        try {
            String json = Files.readString(file);
            List<MatchRecord> list = Json.mapper().readValue(json, new TypeReference<List<MatchRecord>>() { });
            return new ArrayList<>(list);
        } catch (IOException e) {
            CrashLog.report("MatchStore", "kaputte Datei: " + file, e);
            return new ArrayList<>();
        }
    }

    /** Haengt eine Partie an, kappt auf {@link #MAX} (die aeltesten fallen raus) und schreibt atomar. */
    public synchronized void add(MatchRecord r) {
        List<MatchRecord> list = all();
        list.add(r);
        while (list.size() > MAX) {
            list.remove(0);
        }
        write(list);
    }

    /** @throws IllegalArgumentException unbekannte Partie ("unbekannte Partie: &lt;id&gt;") */
    public synchronized void delete(String id) {
        List<MatchRecord> list = all();
        list.remove(indexOf(list, id));
        write(list);
    }

    /**
     * Setzt nur {@code counted}; ein vorhandener {@code excludeReason} bleibt als Hinweis erhalten,
     * warum die Partie urspruenglich nicht gewertet wurde.
     *
     * @throws IllegalArgumentException unbekannte Partie ("unbekannte Partie: &lt;id&gt;")
     */
    public synchronized void setCounted(String id, boolean counted) {
        List<MatchRecord> list = all();
        int i = indexOf(list, id);
        list.set(i, list.get(i).withCounted(counted, list.get(i).excludeReason()));
        write(list);
    }

    /** @throws IllegalArgumentException unbekannte Partie ("unbekannte Partie: &lt;id&gt;") */
    private static int indexOf(List<MatchRecord> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        throw new IllegalArgumentException("unbekannte Partie: " + id);
    }

    /** Eindeutige Temp-Datei je Aufruf (statt eines festen Namens) - zwei Schreiber ueberschreiben sich sonst gegenseitig die Temp-Datei, bevor sie verschoben ist. */
    private void write(List<MatchRecord> list) {
        Path tmp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            tmp = Files.createTempFile(parent, "matches", ".tmp");
            Files.writeString(tmp, Json.toJson(list));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                    // best effort - Fehlermeldung unten ist bereits die eigentliche Ursache
                }
            }
            throw new IllegalStateException("kann Partien nicht speichern: " + file, e);
        }
    }
}
