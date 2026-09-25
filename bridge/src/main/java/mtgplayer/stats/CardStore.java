package mtgplayer.stats;

import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kartenbiografien ({@link CardLog}) als je eine Datei pro Partie unter einem Verzeichnis, benannt nach
 * der Partie-Id ({@code <id>.json}) - anders als {@link MatchStore} (eine Datei, ein JSON-Array ueber
 * alle Partien): eine Kartenbiografie kostet allein schon rund 7 KB je Sitz (siehe {@link CardLog}), sie
 * in ein gemeinsames Array zu haengen wuerde bei jedem Schreiben die GANZE Historie neu serialisieren.
 * Stattdessen traegt jede Partie ihre eigene kleine Datei, das Loeschen einer Partie ({@link #delete})
 * entfernt einfach ihre.
 *
 * <p>Schreiben ist atomar wie bei {@link MatchStore} (eindeutige Temp-Datei je Aufruf + {@code
 * ATOMIC_MOVE}), eine kaputte Datei liefert {@link #read} als leeres {@code Optional} statt einer
 * Ausnahme und meldet sich ueber {@link CrashLog#note} - bewusst {@code note} und nicht {@code warn}: die
 * Datei einer EINZELNEN Partie betrifft nur deren Kartendaten, nicht die ganze Historie wie bei
 * {@code MatchStore}, das geht den Browser nichts an.</p>
 *
 * <p>Die Partie-Id kommt vom Aufrufer (beim Schreiben aus {@code MatchRecorder.newId} - Format
 * {@code <ISO-Zeitstempel>-<6 Hex>}, z. B. {@code "2026-09-25T14:39:39.718Z-f94358"}, ENTHAELT also
 * Doppelpunkt und Punkt; beim Lesen/Loeschen letztlich aus dem WebSocket-Protokoll, siehe
 * {@code Bridge.deleteMatch}) und wird direkt zum Dateinamen - eine ungeprüfte Id darf deshalb nie in
 * einen Pfad wandern ({@code "../../etc/passwd"} etc.). {@link #validId} laesst deshalb NICHT nur
 * {@code [A-Za-z0-9_-]} zu (das wiese jede echte Partie-Id ab, siehe oben), sondern zusaetzlich
 * {@code :} und {@code .} - verboten bleibt insbesondere der Pfadtrenner {@code /} (und {@code \}), der
 * einzige Weg, mit dem eine Id ueberhaupt aus dem Zielverzeichnis ausbrechen koennte: die Id steht immer
 * VOR der fest angehaengten Endung {@code ".json"}, ein blankes {@code ".."} wird so zu {@code "...json"}
 * - einem harmlosen Dateinamen, keiner Verzeichnis-Traversierung. Eine Id ausserhalb dieser Menge wird
 * von allen drei Methoden wie eine unbekannte Partie behandelt (kein Schreiben/Lesen/Loeschen), ein
 * Schreibversuch zusaetzlich ueber {@link CrashLog#note} vermerkt - das ist der einzige Fall, in dem eine
 * ungueltige Id ueberhaupt etwas Sichtbares hinterlassen wuerde (ohne die Notiz bliebe unklar, ob die
 * Kartendaten einer Partie schlicht fehlen oder nie geschrieben werden konnten).</p>
 */
public final class CardStore {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_:.-]+");

    private final Path dir;

    public CardStore(Path dir) {
        this.dir = dir;
    }

    public static CardStore standard() {
        return new CardStore(ForgeBoot.dataDir().resolve("cards"));
    }

    /**
     * Schreibt die Kartenbiografie atomar unter {@code <id>.json}. Eine ungueltige Id schreibt nichts
     * und hinterlaesst eine Notiz (siehe Klassenkommentar).
     */
    public void write(CardLog log) {
        String id = log.id();
        if (!validId(id)) {
            CrashLog.note("CardStore", "ungueltige Partie-Id, nicht geschrieben: " + id);
            return;
        }
        Path file = dir.resolve(id + ".json");
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            // Eindeutige Temp-Datei je Aufruf (statt eines festen Namens) - zwei Schreiber ueberschreiben
            // sich sonst gegenseitig die Temp-Datei, bevor sie verschoben ist (siehe MatchStore).
            tmp = Files.createTempFile(dir, "card", ".tmp");
            Files.writeString(tmp, Json.toJson(log));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignore) {
                    // best effort - Fehlermeldung unten ist bereits die eigentliche Ursache
                }
            }
            throw new IllegalStateException("kann Kartendaten nicht speichern: " + file, e);
        }
    }

    /**
     * @return die Kartenbiografie der Partie, oder ein leeres Optional - unbekannte Id, ungueltige Id
     *         (siehe Klassenkommentar) oder eine kaputte Datei (dann zusaetzlich ueber
     *         {@link CrashLog#note} vermerkt, ohne Browser-Meldung).
     */
    public Optional<CardLog> read(String id) {
        if (!validId(id)) {
            return Optional.empty();
        }
        Path file = dir.resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file);
            return Optional.of(Json.mapper().readValue(json, CardLog.class));
        } catch (IOException e) {
            // note statt warn: die kaputte Datei einer einzelnen Partie ist kein Vorfall, der die
            // laufende Partie oder die ganze Historie betrifft (siehe Klassenkommentar).
            CrashLog.note("CardStore", "kaputte Datei: " + file + " (" + e + ")");
            return Optional.empty();
        }
    }

    /** Entfernt die Datei der Partie; unbekannte oder ungueltige Id (siehe Klassenkommentar) ist ein Nichts. */
    public void delete(String id) {
        if (!validId(id)) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (IOException e) {
            throw new IllegalStateException("kann Kartendaten nicht loeschen: " + id, e);
        }
    }

    private static boolean validId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }
}
