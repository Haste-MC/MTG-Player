package mtgplayer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mtgplayer.forge.CrashLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MatchStoreTest {

    /** Nie in die echte {@code ~/.mtg-player/logs/bridge.log} des Nutzers schreiben (kaputte-Datei-Test). */
    @BeforeEach
    void redirectCrashLog(@TempDir Path dir) {
        CrashLog.setFile(dir.resolve("logs").resolve("bridge.log"));
    }

    @AfterEach
    void resetCrashLog() {
        CrashLog.setFile(null);
    }

    private static MatchRecord record(String id) {
        MatchRecord.Seat seat = new MatchRecord.Seat("Du", "Titania, Gaea Incarnate", true, null, true, null,
                null, 1, 7, List.of(0, 1, 2, 2, 3), 2, 4, 12, 34,
                1, 0, 2, 9, 3, 6, 5, 2, 3, 4, 1, 2,
                2, 2, 4, 21, 18, 12, 22, 0);
        return new MatchRecord(id, "2026-09-22T19:00:00Z", "2026-09-22T19:13:32Z", 812345, "live",
                5, 14, "AllOpponentsLost", false, true, null, List.of(seat));
    }

    @Test
    void addUndAllLiefernReihenfolge(@TempDir Path dir) {
        MatchStore store = new MatchStore(dir.resolve("matches.json"));
        store.add(record("m1"));
        store.add(record("m2"));
        assertEquals(List.of("m1", "m2"), store.all().stream().map(MatchRecord::id).toList());
    }

    @Test
    void deleteEntferntUndUnbekanntWirft(@TempDir Path dir) {
        MatchStore store = new MatchStore(dir.resolve("matches.json"));
        store.add(record("m1"));
        store.add(record("m2"));
        store.delete("m1");
        assertEquals(List.of("m2"), store.all().stream().map(MatchRecord::id).toList());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.delete("m1"));
        assertEquals("unbekannte Partie: m1", e.getMessage());
    }

    @Test
    void setCountedSetztFeldUndUnbekanntWirft(@TempDir Path dir) {
        MatchStore store = new MatchStore(dir.resolve("matches.json"));
        store.add(record("m1"));
        store.setCounted("m1", false);
        MatchRecord loaded = store.all().get(0);
        assertFalse(loaded.counted());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.setCounted("nix", true));
        assertEquals("unbekannte Partie: nix", e.getMessage());
    }

    @Test
    void setCountedBehaeltExcludeReasonAlsHinweis(@TempDir Path dir) {
        MatchStore store = new MatchStore(dir.resolve("matches.json"));
        MatchRecord ausgeschlossen = record("m1").withCounted(false, "zu kurz");
        store.add(ausgeschlossen);
        store.setCounted("m1", true);
        MatchRecord loaded = store.all().get(0);
        assertTrue(loaded.counted());
        assertEquals("zu kurz", loaded.excludeReason(), "excludeReason bleibt als Hinweis erhalten");
    }

    @Test
    void neuladenAusDerselbenDateiLiefertDasselbe(@TempDir Path dir) {
        Path file = dir.resolve("matches.json");
        MatchStore store = new MatchStore(file);
        store.add(record("m1"));
        MatchStore reloaded = new MatchStore(file);
        assertEquals(store.all(), reloaded.all());
    }

    @Test
    void deckelBeiMaxPlus5FaelltAeltesterWeg(@TempDir Path dir) {
        MatchStore store = new MatchStore(dir.resolve("matches.json"));
        for (int i = 0; i < MatchStore.MAX + 5; i++) {
            store.add(record("m" + i));
        }
        List<MatchRecord> all = store.all();
        assertEquals(MatchStore.MAX, all.size());
        assertEquals("m5", all.get(0).id(), "die 5 aeltesten sind rausgefallen");
        assertEquals("m" + (MatchStore.MAX + 4), all.get(all.size() - 1).id());
    }

    @Test
    void kaputteDateiLiefertLeereListeUndDanachFunktioniertAdd(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("matches.json");
        Files.writeString(file, "{kaputt");
        MatchStore store = new MatchStore(file);
        assertTrue(store.all().isEmpty());
        store.add(record("m1"));
        assertEquals(List.of("m1"), store.all().stream().map(MatchRecord::id).toList());
    }

    /** Eine kaputte Datei ist kein Spielabsturz: der Crash-Kanal wuerde die LAUFENDE Partie als
     *  "Absturz" aus der Wertung nehmen und dem Browser ein Spielende melden. */
    @Test
    void kaputteDateiLoestKeinenCrashListenerAus(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("matches.json");
        Files.writeString(file, "{kaputt");
        AtomicInteger crashes = new AtomicInteger();
        List<String> browser = new ArrayList<>();
        Runnable listener = crashes::incrementAndGet;
        CrashLog.addCrashListener(listener);
        CrashLog.setListener(browser::add);
        try {
            assertTrue(new MatchStore(file).all().isEmpty());
        } finally {
            CrashLog.removeCrashListener(listener);
            CrashLog.setListener(null);
        }
        assertEquals(0, crashes.get(), "kein Crash-Listener bei einer kaputten Datei");
        assertEquals(1, browser.size(), "aber eine Fehlerzeile im Browser");
        assertFalse(browser.get(0).contains("Spiel abgebrochen"), "und zwar ohne Spielende: " + browser.get(0));
        assertTrue(Files.readString(CrashLog.file()).contains("kaputte Datei"), "und eine Zeile in bridge.log");
    }

    @Test
    void datensatzTraegtFormatversionUndAelterOhneFeldGiltAlsV1(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("matches.json");
        MatchStore store = new MatchStore(file);
        store.add(record("m1"));
        assertEquals(MatchRecord.VERSION, store.all().get(0).v());
        assertTrue(Files.readString(file).startsWith("[{\"v\":" + MatchRecord.VERSION + ","),
                "v steht als erstes Feld im Datensatz");

        // Datei ohne "v" (vor Einfuehrung des Feldes geschrieben)
        Files.writeString(file, Files.readString(file).replace("{\"v\":" + MatchRecord.VERSION + ",", "{"));
        assertEquals(1, new MatchStore(file).all().get(0).v(),
                "ohne Feld gilt v1 - eine neue Formatversion macht einen alten Datensatz nicht neuer");
    }

    @Test
    void withCountedAendertNurCountedUndExcludeReason(@TempDir Path dir) {
        MatchRecord r = record("m1");
        MatchRecord ausgeschlossen = r.withCounted(false, "aufgegeben");
        assertFalse(ausgeschlossen.counted());
        assertEquals("aufgegeben", ausgeschlossen.excludeReason());
        assertEquals(r.id(), ausgeschlossen.id());
        assertNull(r.excludeReason());
    }
}
