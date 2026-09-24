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
 * statt eines Absturzes und meldet sich per {@link CrashLog#warn} in {@code bridge.log} - die Datei wird
 * beim naechsten {@link #add}/{@link #delete}/{@link #setCounted} ersetzt. Bewusst {@code warn} und nicht
 * {@code report}: die Datei hat mit der gerade laufenden Partie nichts zu tun, die darf davon weder als
 * "Absturz" aus der Wertung fallen noch im Browser als abgebrochen gelten. Datensaetze ohne Formatversion
 * ({@code "v"}) gelten als v1 (siehe {@link MatchRecord}). Alle vier Methoden sind
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
            List<MatchRecord> out = new ArrayList<>(list.size());
            for (MatchRecord r : list) {
                out.add(normalizeTimelines(r));
            }
            return out;
        } catch (IOException e) {
            // warn statt report: eine kaputte Datei ist kein Spielabsturz. report() wuerde die Crash-
            // Listener ausloesen (eine gerade laufende Partie waere "Absturz") und dem Browser
            // "Spiel abgebrochen" melden - beides hat mit der Datei nichts zu tun.
            CrashLog.warn("MatchStore", "kaputte Datei: " + file + " (" + e + ")");
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

    /**
     * Jackson liefert fuer eine im JSON fehlende Zeitachse (Feld nie geschrieben - v1-Datensatz vor
     * Runde B) {@code null} in {@code Seat.timeline()}; der Compact-Konstruktor von
     * {@link MatchRecord.Seat} laesst das inzwischen bewusst durch (siehe dort - wegen
     * {@link MatchRecord#withoutTimeline()}, das dasselbe {@code null} absichtlich erzeugt). Erst
     * HIER, beim Lesen von der Platte, wird aus einem gelesenen {@code null} eine leere Liste
     * ("keine Daten") - ein frisch aufgezeichneter Datensatz ({@code MatchRecorder}) traegt ohnehin
     * immer eine echte (ggf. leere) Liste und ist von dieser Methode unberuehrt.
     */
    private static MatchRecord normalizeTimelines(MatchRecord r) {
        boolean anyNull = false;
        for (MatchRecord.Seat s : r.seats()) {
            if (s.timeline() == null) {
                anyNull = true;
                break;
            }
        }
        if (!anyNull) return r;
        List<MatchRecord.Seat> seats = new ArrayList<>(r.seats().size());
        for (MatchRecord.Seat s : r.seats()) {
            seats.add(s.timeline() == null ? s.withTimeline(List.of()) : s);
        }
        return new MatchRecord(r.v(), r.id(), r.startedAt(), r.endedAt(), r.durationMs(), r.source(),
                r.aiTimeout(), r.turns(), r.reason(), r.draw(), r.counted(), r.excludeReason(), seats);
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
