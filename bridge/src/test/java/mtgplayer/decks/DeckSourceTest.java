package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DeckSourceTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** resolve() + save() (save laeuft in Bridge normalerweise erst nach ALLEN resolve()-Aufrufen). */
    private static Deck resolveAndSave(DeckSource src, JsonNode node) {
        DeckSource.Resolved r = src.resolve(node);
        r.save().run();
        return r.deck();
    }

    @Test
    void precon(@TempDir Path dir) {
        Deck d = resolveAndSave(new DeckSource(new DeckStore(dir)), Json.parse("{\"precon\":\"Abzan Armor [TDC] [2025]\"}"));
        assertEquals(1, d.getCommanders().size());
    }

    @Test
    void textWirdGeparstUndGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"" + text + "\",\"deckName\":\"Testdeck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Testdeck"), store.names());
        Deck again = resolveAndSave(new DeckSource(store), Json.parse("{\"saved\":\"Testdeck\"}"));
        assertEquals(1, again.getCommanders().size());
    }

    @Test
    void textOhneNamenNimmtVorschlag(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"1 Sol Ring\\n1 Felothar the Steadfast\\n\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void deckNameUeberschreibtSpielernameBeimSpeichern(@TempDir Path dir) {
        // Regression: startGame.opponents[i].name ist der Spielername ("KI 1"), nicht der
        // Speichername des Decks - der kommt aus dem eigenen Feld "deckName".
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = resolveAndSave(new DeckSource(store),
                Json.parse("{\"text\":\"" + text + "\",\"name\":\"KI 1\",\"deckName\":\"Mein Deck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Mein Deck"), store.names());
    }

    @Test
    void ohneDeckNameWirdSpielernameNichtAlsSpeichernameGenutzt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n";
        resolveAndSave(new DeckSource(store), Json.parse("{\"text\":\"" + text + "\",\"name\":\"KI 1\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void speichertErstNachAllenResolveAufrufen(@TempDir Path dir) {
        // resolve() alleine darf noch nichts auf Platte schreiben - erst save().run().
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n";
        DeckSource.Resolved r = new DeckSource(store).resolve(Json.parse("{\"text\":\"" + text + "\",\"deckName\":\"Spaeter\"}"));
        assertEquals(List.of(), store.names());
        r.save().run();
        assertEquals(List.of("Spaeter"), store.names());
    }

    @Test
    void importProblemeWerdenGemeldetUndNichtGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DeckSource(store).resolve(Json.parse("{\"text\":\"1 Sol Ring\\n1 Gibtsnicht\\n\"}")));
        assertTrue(e.getMessage().contains("Gibtsnicht"));
        assertTrue(e.getMessage().toLowerCase().contains("commander"));
        assertEquals(List.of(), store.names());
    }

    @Test
    void ohneBekannteFormWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckSource(new DeckStore(dir)).resolve(Json.parse("{}")));
    }

    @Test
    void archidektFormHoltParstUndSpeichert(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        DeckSource.Resolved r = src.resolve(Json.parse("{\"archidekt\":\"https://archidekt.com/decks/1/x\"}"));
        r.save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        assertEquals("Thelon of Havenwood", r.deck().getCommanders().get(0).getName());
        assertEquals("1", store.archidektId("Fun With Fungus"));
        assertEquals("2018-03-12T05:12:58Z", store.infos().get(0).archidektUpdated());
    }

    @Test
    void importArchidektNeuUndDannResyncUnterGespeichertemNamen(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        // umbenannt gespeichert (Name aus einem frueheren Import mit deckName) - Resync behaelt den Namen
        Deck d = store.load("Fun With Fungus");
        store.save("Pilze", d);
        java.nio.file.Files.delete(dir.resolve(DeckStore.fileName("Fun With Fungus")));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Pilze"), store.names());
        assertEquals("1", store.archidektId("Pilze"));
        assertEquals("2018-03-12T05:12:58Z", store.infos().get(0).archidektUpdated());
        // Resync erzeugt keine doppelten Tags
        Deck again = store.load("Pilze");
        assertEquals(1, again.getTags().stream().filter(t -> t.startsWith(DeckStore.ARCHIDEKT_TAG)).count(), again.getTags().toString());
        assertEquals(1, again.getTags().stream().filter(t -> t.startsWith(DeckStore.ARCHIDEKT_UPDATED_TAG)).count(), again.getTags().toString());
    }

    @Test
    void importArchidektUebernimmtGleichnamigesDeckOhneTag(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        // lokales Deck ohne Tag unter dem Archidekt-Namen (alter Textimport). Precons.load() liefert eine
        // Kopie (siehe PreconsTest), das Deck kommt hier also garantiert ungetaggt an.
        store.save("Fun With Fungus", Precons.load("Adaptive Enchantment [C18] [2018]"));
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        assertEquals("1", store.archidektId("Fun With Fungus"));
        assertEquals("Thelon of Havenwood", store.load("Fun With Fungus").getCommanders().get(0).getName());
    }

    @Test
    void importArchidektNeuMitNamenskollisionSpeichertUnterNameMitId(@TempDir Path dir) throws Exception {
        // Ein lokales Deck heisst schon so wie das Archidekt-Deck, traegt aber ein ANDERES
        // Archidekt-Tag (kein alter Textimport, sondern ein anderer Import): der Import darf es
        // nicht ueberschreiben, sondern speichert unter "<Name> (<id>)".
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckImport.Result local = DeckImport.parse("1 Sol Ring\n1 Felothar the Steadfast\n");
        local.deck().getTags().add(DeckStore.ARCHIDEKT_TAG + "99");
        store.save("Fun With Fungus", local.deck());
        new DeckSource(store, new Archidekt(url -> body)).importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus", "Fun With Fungus (1)"), store.names());
        assertEquals("99", store.archidektId("Fun With Fungus"));
        assertEquals("Felothar the Steadfast", store.load("Fun With Fungus").getCommanders().get(0).getName());
        assertEquals("1", store.archidektId("Fun With Fungus (1)"));
        assertEquals("Thelon of Havenwood", store.load("Fun With Fungus (1)").getCommanders().get(0).getName());
        // beim naechsten Mal ist die Id bekannt -> Resync unter "Fun With Fungus (1)", kein drittes Deck
        new DeckSource(store, new Archidekt(url -> body)).importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus", "Fun With Fungus (1)"), store.names());
    }

    @Test
    void importArchidektNeuMitDateikollisionSpeichertUnterNameMitId(@TempDir Path dir) throws Exception {
        // Verschiedene Namen, dieselbe .dck-Datei (DeckStore.fileName ersetzt ":" durch "_"), das lokale
        // Deck traegt ein ANDERES Archidekt-Tag: auch dann eindeutiger Name statt Ueberschreiben.
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"))
                .replaceFirst("\"name\":\"Fun With Fungus\"", "\"name\":\"Fun:With Fungus\"");
        DeckStore store = new DeckStore(dir);
        DeckImport.Result local = DeckImport.parse("1 Sol Ring\n1 Felothar the Steadfast\n");
        local.deck().getTags().add(DeckStore.ARCHIDEKT_TAG + "99");
        store.save("Fun_With Fungus", local.deck());
        assertEquals(DeckStore.fileName("Fun_With Fungus"), DeckStore.fileName("Fun:With Fungus"));
        new DeckSource(store, new Archidekt(url -> body)).importArchidekt(1).save().run();
        assertEquals(List.of("Fun:With Fungus (1)", "Fun_With Fungus"), store.names());
        assertEquals("Felothar the Steadfast", store.load("Fun_With Fungus").getCommanders().get(0).getName());
        assertEquals("1", store.archidektId("Fun:With Fungus (1)"));
    }

    @Test
    void archidektImportSchreibtBracket(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.importArchidekt(1).save().run();
        assertEquals(3, store.bracket("Fun With Fungus"));
    }

    @Test
    void resyncBehaeltVonHandGesetztenBracketWennArchidektKeinenLiefert(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        String bodyOhneBracket = body.replaceFirst("\"edhBracket\":3", "\"edhBracket\":null");
        DeckStore store = new DeckStore(dir);
        new DeckSource(store, new Archidekt(url -> body)).importArchidekt(1).save().run();
        assertEquals(3, store.bracket("Fun With Fungus"), "Vorbedingung: Bracket aus Archidekt gesetzt");

        store.setBracket("Fun With Fungus", 4); // von Hand ueberschrieben
        new DeckSource(store, new Archidekt(url -> bodyOhneBracket)).resync("Fun With Fungus").save().run();
        assertEquals(4, store.bracket("Fun With Fungus"), "Resync ohne Archidekt-Bracket behaelt den von Hand gesetzten");
    }

    @Test
    void archidektFehlerWirdGemeldet(@TempDir Path dir) {
        DeckSource src = new DeckSource(new DeckStore(dir), new Archidekt(url -> { throw new java.io.IOException("HTTP 500"); }));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> src.resolve(Json.parse("{\"archidekt\":\"42\"}")));
        assertTrue(e.getMessage().contains("Archidekt"));
    }
}
