package mtgplayer.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Reiner Helfer fuer die Fehlertexte je Deck im archidektImport-Lauf (siehe Bridge.importRun). */
class BridgeImportErrorTextTest {

    @Test
    void resyncPraefixWirdEntfernt() {
        assertEquals("Archidekt: HTTP 500", Bridge.stripDeckPrefix("Pilze", "Resync Pilze: Archidekt: HTTP 500"));
    }

    @Test
    void namePraefixWirdEntfernt() {
        assertEquals("kein Archidekt-Deck", Bridge.stripDeckPrefix("Pilze", "Pilze: kein Archidekt-Deck"));
    }

    @Test
    void ohnePraefixUnveraendert() {
        assertEquals("Archidekt: HTTP 404", Bridge.stripDeckPrefix("Deck 42", "Archidekt: HTTP 404"));
        assertEquals("Resync Anderes: x", Bridge.stripDeckPrefix("Pilze", "Resync Anderes: x"), "fremder Name bleibt");
        assertEquals("unbekannter Fehler", Bridge.stripDeckPrefix("Pilze", null));
    }
}
