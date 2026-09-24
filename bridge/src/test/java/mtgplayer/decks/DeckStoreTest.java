package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
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
import java.util.Map;
import java.util.TreeMap;

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
        assertThrows(RuntimeException.class, () -> Precons.info("Kaputt", store.load("Kaputt"), null, null, null),
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

    @Test
    void bracketWirdGesetztGelesenUndEntfernt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("Mein Deck", Precons.load("Abzan Armor [TDC] [2025]"));
        assertNull(store.bracket("Mein Deck"), "kein Tag -> unbekannt");
        store.setBracket("Mein Deck", 3);
        assertEquals(3, store.bracket("Mein Deck"));
        store.setBracket("Mein Deck", null);
        assertNull(store.bracket("Mein Deck"), "null entfernt das Tag");
    }

    @Test
    void bracketAusserhalbDesBereichsWirft(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("Mein Deck", Precons.load("Abzan Armor [TDC] [2025]"));
        assertThrows(IllegalArgumentException.class, () -> store.setBracket("Mein Deck", 0));
        assertThrows(IllegalArgumentException.class, () -> store.setBracket("Mein Deck", 6));
        assertNull(store.bracket("Mein Deck"), "fehlgeschlagenes setBracket aendert nichts");
    }

    @Test
    void bracketUnbekanntesDeckLiefertNullSetBracketWirft(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        assertNull(store.bracket("gibt es nicht"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> store.setBracket("gibt es nicht", 3));
        assertTrue(e.getMessage().contains("gibt es nicht"));
    }

    /**
     * Der Bracket-Knopf im Deck-Panel geht ueber {@code load} + {@code save} - er schreibt also jede
     * .dck-Datei neu, die Kevin anfasst (bei 46 Decks: 46-mal). Dieser Test haelt fest, was dabei
     * unangetastet bleiben muss: Kartenzahl je Abschnitt, die AUSGEWAEHLTEN Drucke (Edition und
     * Art-Index - sonst taeuscht das Deck-Panel andere Bilder vor, und ein Resync haelt das Deck fuer
     * geaendert) und die vorhandenen Tags {@code archidekt:}/{@code archidekt-updated:}, an denen der
     * Archidekt-Abgleich das Deck wiedererkennt.
     */
    @Test
    void setBracketErhaeltKartenDruckeUndArchidektTags(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        d.getTags().add(DeckStore.ARCHIDEKT_TAG + "12345");
        d.getTags().add(DeckStore.ARCHIDEKT_UPDATED_TAG + "2026-09-21T18:41:17.869883Z");
        store.save("Mein Deck", d);
        Map<String, Integer> before = printings(store.load("Mein Deck"));
        assertTrue(before.size() > 50, "die Vergleichsgrundlage ist nicht leer: " + before.size());

        store.setBracket("Mein Deck", 4);

        Deck after = store.load("Mein Deck");
        assertEquals(4, store.bracket("Mein Deck"));
        assertEquals(before, printings(after), "Karten, Anzahl und gewaehlte Drucke bleiben");
        assertEquals("12345", store.archidektId("Mein Deck"), "archidekt:-Tag bleibt");
        assertEquals("2026-09-21T18:41:17.869883Z", store.infos().get(0).archidektUpdated(),
                "archidekt-updated:-Tag bleibt");
        // Und auch der zweite Klick (Bracket wieder entfernen) laesst alles stehen.
        store.setBracket("Mein Deck", null);
        assertNull(store.bracket("Mein Deck"));
        assertEquals(before, printings(store.load("Mein Deck")));
        assertEquals("12345", store.archidektId("Mein Deck"));
    }

    /**
     * Jede Karte des Decks als "Abschnitt|Name|Edition|ArtIndex" -> Anzahl. Der Schluessel traegt
     * Edition und Art-Index mit, damit der Vergleich auch einen stillen Wechsel des Drucks bemerkt und
     * nicht nur eine veraenderte Kartenzahl.
     */
    private static Map<String, Integer> printings(Deck deck) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<DeckSection, CardPool> section : deck) {
            for (Map.Entry<PaperCard, Integer> card : section.getValue()) {
                PaperCard c = card.getKey();
                out.merge(section.getKey() + "|" + c.getName() + "|" + c.getEdition() + "|" + c.getArtIndex(),
                        card.getValue(), Integer::sum);
            }
        }
        return out;
    }

    @Test
    void infosTragenBracket(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        d.getTags().add(DeckStore.BRACKET_TAG + "2");
        store.save("Mein Deck", d);
        assertEquals(2, store.infos().get(0).bracket());
    }
}
