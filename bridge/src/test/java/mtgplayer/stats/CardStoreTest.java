package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import mtgplayer.forge.CrashLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardStoreTest {

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} des Nutzers schreiben (kaputte-Datei-/ungueltige-Id-Test). */
    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    private static CardLog log(String id, String deck) {
        CardLog.Card card = new CardLog.Card("Sol Ring", 1, 1, 1, 1, null, null, "battlefield");
        CardLog.SeatCards seat = new CardLog.SeatCards(0, deck, List.of(card));
        return new CardLog(id, List.of(seat));
    }

    @Test
    void schreibenUndLesenLiefertDieselbenZeilen(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        store.write(log("m1", "Mein Deck"));
        assertEquals(Optional.of(log("m1", "Mein Deck")), store.read("m1"));
    }

    @Test
    void readUnbekannteIdLiefertLeeresOptional(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        assertTrue(store.read("nix").isEmpty());
    }

    @Test
    void kaputteDateiLiefertLeeresOptionalUndNotiz(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("m1.json"), "{kaputt");
        CardStore store = new CardStore(dir);
        assertTrue(store.read("m1").isEmpty());
        assertTrue(Files.readString(CrashLog.file()).contains("kaputte Datei"),
                "eine Notiz ueber CrashLog.note steht in bridge.log");
    }

    @Test
    void deleteEntferntUndUnbekannteIdIstEinNichts(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        store.write(log("m1", "Mein Deck"));
        store.delete("m1");
        assertTrue(store.read("m1").isEmpty());
        store.delete("nix");   // wirft nicht, tut einfach nichts
    }

    @Test
    void zweiSchreibvorgaengeNacheinanderUeberschreibenSauber(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        store.write(log("m1", "Erstes Deck"));
        store.write(log("m1", "Zweites Deck"));
        Optional<CardLog> loaded = store.read("m1");
        assertTrue(loaded.isPresent());
        assertEquals("Zweites Deck", loaded.get().seats().get(0).deck());
    }

    @Test
    void ungueltigeIdWirdNichtGeschriebenUndNotiert(@TempDir Path dir) throws Exception {
        CardStore store = new CardStore(dir);
        store.write(log("../ausserhalb", "Mein Deck"));
        assertFalse(Files.exists(dir.resolve("../ausserhalb.json")), "es wandert nichts in einen fremden Pfad");
        assertTrue(Files.readString(CrashLog.file()).contains("ungueltige Partie-Id"),
                "eine Notiz steht in bridge.log");
    }

    /**
     * {@code MatchRecorder.newId} liefert Ids wie {@code "2026-09-25T14:39:39.718Z-f94358"} -
     * Doppelpunkt und Punkt sind also Teil einer GANZ NORMALEN Partie-Id, keine Ausnahme. Waere
     * {@link CardStore#write} strenger als das (nur {@code [A-Za-z0-9_-]}), schriebe die Ablage bei
     * jeder echten Partie eine "ungueltige Id"-Notiz und nie eine Kartendatei - das genaue Gegenteil
     * der Aufgabe.
     */
    @Test
    void echteIdMitDoppelpunktUndPunktWirdGeschrieben(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        String id = "2026-09-25T14:39:39.718Z-f94358";
        store.write(log(id, "Mein Deck"));
        assertEquals(Optional.of(log(id, "Mein Deck")), store.read(id));
    }

    /**
     * Eine blanke Id {@code ".."} bleibt trotz erlaubtem Punkt sicher: die Id steht immer VOR der fest
     * angehaengten Endung {@code ".json"}, aus {@code ".."} wird also der Dateiname {@code "...json"} -
     * ein harmloser Name innerhalb von {@code dir}, keine Verzeichnis-Traversierung.
     */
    @Test
    void zweiPunkteAlsIdTraversierenNicht(@TempDir Path dir) {
        CardStore store = new CardStore(dir);
        store.write(log("..", "Mein Deck"));
        assertEquals(Optional.of(log("..", "Mein Deck")), store.read(".."));
        assertTrue(Files.exists(dir.resolve("...json")), "die Datei liegt brav im Zielverzeichnis");
    }
}
