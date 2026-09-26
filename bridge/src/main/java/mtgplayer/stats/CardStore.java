package mtgplayer.stats;

import mtgplayer.forge.CrashLog;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kartenbiografien ({@link CardLog}) als je eine Datei pro Partie unter einem Verzeichnis, benannt nach
 * der Partie-Id ({@code <bereinigte-id>.json}) - anders als {@link MatchStore} (eine Datei, ein
 * JSON-Array ueber alle Partien): eine Kartenbiografie kostet allein schon rund 7 KB je Sitz (siehe
 * {@link CardLog}), sie in ein gemeinsames Array zu haengen wuerde bei jedem Schreiben die GANZE
 * Historie neu serialisieren. Stattdessen traegt jede Partie ihre eigene kleine Datei, das Loeschen
 * einer Partie ({@link #delete}) entfernt einfach ihre.
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
 * {@code Bridge.deleteMatch}). {@link #validId} verhindert nur noch, dass eine Id aus fremder Quelle in
 * einen Pfad ausbricht (Pfadtrenner {@code /} und {@code \}, Steuerzeichen) - sie ist NICHT mehr die
 * Freigabeliste fuer den Dateinamen, das uebernimmt {@link #fileName}: der ersetzt alles, was Windows in
 * Dateinamen verbietet ({@code : * ? " < > |} sowie beide Pfadtrenner) durch {@code _}, ein unter Linux
 * UND Windows gueltiger Name entsteht ({@link #write} warf unter Windows bisher genau an einem
 * Doppelpunkt aus dem Zeitstempel eine {@link InvalidPathException} - jede Partie bliebe dort ohne
 * Kartendaten). Die Id SELBST (und damit der Inhalt der Datei) bleibt dabei unangetastet - nur der Name
 * auf der Platte aendert sich, die Id ist weiter die Verbindung zur Partie.
 * Ein blankes {@code ".."} als Id bleibt trotzdem sicher: die Id steht immer VOR der fest angehaengten
 * Endung {@code ".json"}, daraus wird {@code "...json"} - ein harmloser Name innerhalb von {@code dir},
 * keine Verzeichnis-Traversierung.</p>
 *
 * <p>{@link #read} und {@link #delete} pruefen zusaetzlich den ALTEN, unveraenderten Namen
 * ({@code <id>.json} ohne Ersetzung) als Rueckfall - auf Kevins Linux-Rechner liegen bereits Dateien mit
 * Doppelpunkt im Namen (vor diesem Umbau geschrieben), die sollen nicht ploetzlich unauffindbar werden.
 * {@link #write} schreibt dagegen immer schon unter dem bereinigten Namen - ein bestehender Doppelpunkt-
 * Name wird beim naechsten Schreiben derselben Partie NICHT migriert, bleibt aber ueber den Rueckfall
 * lesbar. Der alte Name ist unter Windows fuer eine echte Id (mit Doppelpunkt) gar kein gueltiger Pfad -
 * {@link Path#resolve} wirft dafuer selbst eine {@link InvalidPathException}; {@link #legacyFile} faengt
 * das ab, statt genau den Fehler wiederherzustellen, den dieser Umbau beheben soll.</p>
 */
public final class CardStore {

    private static final Pattern VALID_ID = Pattern.compile("[^/\\\\\\p{Cntrl}]+");

    /** Genau die Zeichen, die Windows in Dateinamen verbietet, plus beide Pfadtrenner. */
    private static final Pattern FORBIDDEN_FILENAME_CHARS = Pattern.compile("[:*?\"<>|/\\\\]");

    private final Path dir;

    public CardStore(Path dir) {
        this.dir = dir;
    }

    public static CardStore standard() {
        return new CardStore(ForgeBoot.dataDir().resolve("cards"));
    }

    /**
     * Schreibt die Kartenbiografie atomar unter dem bereinigten Dateinamen der Id (siehe
     * Klassenkommentar). Eine ungueltige Id schreibt nichts und hinterlaesst eine Notiz.
     */
    public void write(CardLog log) {
        String id = log.id();
        if (!validId(id)) {
            CrashLog.note("CardStore", "ungueltige Partie-Id, nicht geschrieben: " + id);
            return;
        }
        Path file = dir.resolve(fileName(id));
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
        Path file = existingFile(id);
        if (file == null) {
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

    /** Entfernt die Datei der Partie (bereinigter Name UND alter Rueckfall-Name); unbekannte oder
     *  ungueltige Id (siehe Klassenkommentar) ist ein Nichts. */
    public void delete(String id) {
        if (!validId(id)) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve(fileName(id)));
            Path legacy = legacyFile(id);
            if (legacy != null) {
                Files.deleteIfExists(legacy);
            }
        } catch (IOException e) {
            throw new IllegalStateException("kann Kartendaten nicht loeschen: " + id, e);
        }
    }

    /** @return die tatsaechlich vorhandene Datei der Partie - bereinigter Name, sonst der alte
     *          Rueckfall-Name (siehe Klassenkommentar) - oder {@code null}, wenn keine von beiden existiert. */
    private Path existingFile(String id) {
        Path current = dir.resolve(fileName(id));
        if (Files.isRegularFile(current)) {
            return current;
        }
        Path legacy = legacyFile(id);
        if (legacy != null && Files.isRegularFile(legacy)) {
            return legacy;
        }
        return null;
    }

    /**
     * @return {@code <id>.json} OHNE Ersetzung - der Name, unter dem {@link #write} vor diesem Umbau
     *         schrieb - oder {@code null}, wenn das auf dieser Plattform gar kein gueltiger Pfad ist
     *         (eine echte Id mit Doppelpunkt unter Windows: genau der Fehler aus dem Klassenkommentar,
     *         den der Rueckfall NICHT wiederherstellen soll).
     */
    private Path legacyFile(String id) {
        try {
            return dir.resolve(id + ".json");
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Ersetzt in der Id alles, was Windows in Dateinamen verbietet, durch {@code _} (siehe Klassenkommentar). */
    private static String fileName(String id) {
        return FORBIDDEN_FILENAME_CHARS.matcher(id).replaceAll("_") + ".json";
    }

    private static boolean validId(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }
}
