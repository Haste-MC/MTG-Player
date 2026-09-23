package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class DeckStoreTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void speichernListenLaden(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        assertEquals(List.of(), store.names());
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        store.save("Mein Abzan", d);
        assertEquals(List.of("Mein Abzan"), store.names());
        Deck loaded = store.load("Mein Abzan");
        assertEquals(1, loaded.getCommanders().size());
        assertEquals(d.get(forge.deck.DeckSection.Main).countAll(), loaded.get(forge.deck.DeckSection.Main).countAll());
    }

    @Test
    void unbekannterNameWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckStore(dir).load("nix"));
    }

    @Test
    void deleteEntferntDateiUndMeldetUnbekannt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("Weg damit", Precons.load("Abzan Armor [TDC] [2025]"));
        store.delete("Weg damit");
        assertEquals(List.of(), store.names());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.delete("Weg damit"));
        assertTrue(e.getMessage().contains("Weg damit"));
    }

    @Test
    void dateinameWirdBereinigt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("A/B: C?", Precons.load("Abzan Armor [TDC] [2025]"));
        assertEquals(List.of("A/B: C?"), store.names(), "Anzeigename bleibt, nur der Dateiname wird bereinigt");
        assertTrue(dir.resolve("A_B_ C_.dck").toFile().exists());
        assertEquals("A/B: C?", store.load("A/B: C?").getName(), "geladenes Deck traegt den gespeicherten Anzeigenamen");
    }

    /**
     * Eine defekte .dck darf infos() nicht zu Fall bringen. Ein fehlender [Main]-Abschnitt reicht als
     * Defekt nicht: DeckSerializer.fromFile liest nur die Metadaten und parst die Kartenlisten erst beim
     * Zugriff (Deck.setDeferredSections) - und ein leeres Deck ist dabei gueltig. Eine Kartenzeile mit
     * einer Anzahl jenseits von int laesst dagegen CardPool.processCardList (Integer.parseInt) beim ersten
     * Zugriff auf die Sections (getCommanders in Precons.info) mit NumberFormatException scheitern.
     */
    @Test
    void infosUeberspringenUnlesbaresDeck(@TempDir Path dir) throws IOException {
        DeckStore store = new DeckStore(dir);
        store.save("Mein Abzan", Precons.load("Abzan Armor [TDC] [2025]"));
        Files.writeString(dir.resolve("kaputt.dck"), "[metadata]\nName=Kaputt\n[Main]\n99999999999999 Sol Ring\n");
        assertEquals(List.of("Kaputt", "Mein Abzan"), store.names(), "names() liest nur die Name-Zeile");
        assertThrows(RuntimeException.class, () -> Precons.info("Kaputt", store.load("Kaputt"), null, null),
            "Vorbedingung: die Datei laesst sich nicht zu einer DeckInfo laden");
        List<Messages.DeckInfo> infos = store.infos();
        assertEquals(List.of("Mein Abzan"), infos.stream().map(Messages.DeckInfo::name).toList());
    }

    @Test
    void infosLiefernCommanderMitBildUndArchidektTag(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        d.getTags().add(DeckStore.ARCHIDEKT_TAG + "12345");
        store.save("Mein Abzan", d);
        List<Messages.DeckInfo> infos = store.infos();
        assertEquals(1, infos.size());
        assertEquals("Mein Abzan", infos.get(0).name());
        assertEquals("Felothar the Steadfast", infos.get(0).commanders().get(0).name());
        assertTrue(infos.get(0).commanders().get(0).imageKey().startsWith("c:Felothar"));
        assertEquals("12345", infos.get(0).archidekt());
        assertEquals("12345", store.archidektId("Mein Abzan"));
        assertNull(store.archidektId("gibt es nicht"));
    }

    @Test
    void zweitesTagLiefertUpdatedUndNameJeArchidektId(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        d.getTags().add(DeckStore.ARCHIDEKT_TAG + "12345");
        d.getTags().add(DeckStore.ARCHIDEKT_UPDATED_TAG + "2026-09-21T18:41:17.869883Z");
        store.save("Mein Abzan", d);
        List<Messages.DeckInfo> infos = store.infos();
        assertEquals("12345", infos.get(0).archidekt());
        assertEquals("2026-09-21T18:41:17.869883Z", infos.get(0).archidektUpdated());
        assertEquals("Mein Abzan", store.byArchidektId("12345"));
        assertNull(store.byArchidektId("0"));
        // ohne Tags: null statt Fehler (bewusst ein anderes Precon, damit der Fall auch dann sauber
        // bleibt, wenn oben mal etwas am Abzan-Deck haengen bleibt)
        store.save("Ohne", Precons.load("Adaptive Enchantment [C18] [2018]"));
        assertNull(store.infos().stream().filter(i -> i.name().equals("Ohne")).findFirst().orElseThrow().archidektUpdated());
    }
}
