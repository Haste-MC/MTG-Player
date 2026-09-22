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
 * ist atomar (Temp-Datei + {@code ATOMIC_MOVE}), damit ein Absturz waehrend des Schreibens nie eine
 * halbe Datei hinterlaesst. Eine kaputte Datei (fremder Inhalt, abgebrochener Schreibvorgang vor
 * Einfuehrung des atomaren Schreibens, ...) liefert {@link #all()} als leere Liste statt eines Absturzes
 * und meldet sich per {@link CrashLog} in {@code bridge.log} - die Datei wird beim naechsten
 * {@link #add}/{@link #delete}/{@link #setCounted} ersetzt.
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
    public List<MatchRecord> all() {
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
    public void add(MatchRecord r) {
        List<MatchRecord> list = all();
        list.add(r);
        while (list.size() > MAX) {
            list.remove(0);
        }
        write(list);
    }

    /** @throws IllegalArgumentException unbekannte Partie ("unbekannte Partie: &lt;id&gt;") */
    public void delete(String id) {
        List<MatchRecord> list = all();
        if (!list.removeIf(m -> m.id().equals(id))) {
            throw new IllegalArgumentException("unbekannte Partie: " + id);
        }
        write(list);
    }

    /**
     * Setzt nur {@code counted}; ein vorhandener {@code excludeReason} bleibt als Hinweis erhalten,
     * warum die Partie urspruenglich nicht gewertet wurde.
     *
     * @throws IllegalArgumentException unbekannte Partie ("unbekannte Partie: &lt;id&gt;")
     */
    public void setCounted(String id, boolean counted) {
        List<MatchRecord> list = all();
        for (int i = 0; i < list.size(); i++) {
            MatchRecord m = list.get(i);
            if (m.id().equals(id)) {
                list.set(i, m.withCounted(counted, m.excludeReason()));
                write(list);
                return;
            }
        }
        throw new IllegalArgumentException("unbekannte Partie: " + id);
    }

    private void write(List<MatchRecord> list) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.toJson(list));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("kann Partien nicht speichern: " + file, e);
        }
    }
}
