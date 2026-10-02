package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExclusionsTest {

    @Test
    void schreibenUndLesenLiefertDieselbenEintraege(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ausgeschlossen.txt");
        // schreiben() sortiert alphabetisch (siehe unten), also steht die Eingabe schon in dieser Reihenfolge.
        List<Exclusions.Eintrag> eintraege = List.of(
                new Exclusions.Eintrag("Coin Flipper", "FlippedCoinOnce fehlt in TriggerType", "2026-10-02"),
                new Exclusions.Eintrag("Dreamdrinker Vampire", "Empower fehlt in ApiType", "2026-09-25"));

        Exclusions.schreiben(datei, eintraege);

        assertEquals(eintraege, Exclusions.lesen(datei));
    }

    /** Alphabetisch, damit Aenderungen an der Liste im Diff lesbar bleiben. */
    @Test
    void schreibenSortiertAlphabetisch(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ausgeschlossen.txt");

        Exclusions.schreiben(datei, List.of(
                new Exclusions.Eintrag("Zebra", "g", "2026-10-02"),
                new Exclusions.Eintrag("Apfel", "g", "2026-10-02")));

        assertEquals(List.of("Apfel", "Zebra"),
                Exclusions.lesen(datei).stream().map(Exclusions.Eintrag::schluessel).toList());
    }

    /** Der Kopf bleibt beim Fortschreiben erhalten und wird nicht als Eintrag gelesen. */
    @Test
    void schreibenBehaeltDenKopfUndLesenSiehtLeereListe(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ausgeschlossen.txt");

        Exclusions.schreiben(datei, List.of());

        assertTrue(Files.readString(datei).startsWith("# Karten und Set-Codes"));
        assertTrue(Exclusions.lesen(datei).isEmpty());
    }

    @Test
    void fehlendeDateiIstEineLeereListe(@TempDir Path tmp) throws Exception {
        assertTrue(Exclusions.lesen(tmp.resolve("gibtsnicht.txt")).isEmpty());
    }

    /** Kommentarzeilen erklaeren die Datei dem Menschen und duerfen den Leser nicht stoeren. */
    @Test
    void kommentarUndLeerzeilenWerdenUebersprungen(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ausgeschlossen.txt");
        Files.writeString(datei, "# Kopf\n\nSol Ring\tGrund\t2026-10-02\n");

        assertEquals(List.of(new Exclusions.Eintrag("Sol Ring", "Grund", "2026-10-02")),
                Exclusions.lesen(datei));
    }

    /** Der eigentliche Zweck: nur NEUE Befunde wachsen in die Liste, Bestehendes bleibt unberuehrt. */
    @Test
    void nurNeueBefundeKommenHinzu() {
        List<Exclusions.Eintrag> bestand = List.of(
                new Exclusions.Eintrag("Alte Karte", "alter Grund", "2026-09-25"));
        CardDataCheck.Befund befund = new CardDataCheck.Befund(100,
                List.of(new CardDataCheck.Problem("Alte Karte", "neuer Grund"),
                        new CardDataCheck.Problem("Neue Karte", "Empower fehlt in ApiType")),
                List.of(), List.of(), List.of(), List.of());

        List<Exclusions.Eintrag> neu = Exclusions.ergaenzen(bestand, befund, "2026-10-02");

        assertEquals(2, neu.size());
        assertEquals("2026-09-25", neu.get(0).seit(), "der bestehende Eintrag behaelt sein Datum");
        assertEquals("alter Grund", neu.get(0).grund(), "und seinen Grund");
        assertEquals(new Exclusions.Eintrag("Neue Karte", "Empower fehlt in ApiType", "2026-10-02"), neu.get(1));
    }

    @Test
    void auchNichtAuffindbareUndDublettenWandernInDieListe() {
        CardDataCheck.Befund befund = new CardDataCheck.Befund(100, List.of(),
                List.of("Unlesbare Karte"), List.of("YWOE"), List.of("Doppelte Karte"), List.of());

        List<String> schluessel = Exclusions.ergaenzen(List.of(), befund, "2026-10-02")
                .stream().map(Exclusions.Eintrag::schluessel).toList();

        assertEquals(List.of("Doppelte Karte", "Unlesbare Karte", "YWOE"), schluessel.stream().sorted().toList());
    }

    /** Die Betriebsart liest den Befund aus der JSON-Zeile des Pruefmodus - der Weg muss verlustfrei sein. */
    @Test
    void befundUeberstehtDenJsonWegDesPruefmodus() throws Exception {
        CardDataCheck.Befund befund = new CardDataCheck.Befund(5,
                List.of(new CardDataCheck.Problem("A", "g")), List.of("B"), List.of("C"), List.of("D"), List.of("E"));

        String json = mtgplayer.protocol.Json.toJson(befund);

        assertEquals(befund, mtgplayer.protocol.Json.mapper().readValue(json, CardDataCheck.Befund.class));
    }

    /**
     * Der Grund ist der Text einer Forge-Ausnahme und kann Umbruch und Tabulator tragen. Ohne
     * Normalisierung wuerde der Eintrag auf zwei zu kurze Zeilen verteilt (die lesen() verwirft) bzw. die
     * Datumsspalte verschoben.
     */
    @Test
    void grundMitUmbruchUndTabulatorUeberstehtDenRundlauf(@TempDir Path tmp) throws Exception {
        Path datei = tmp.resolve("ausgeschlossen.txt");
        String grund = "java.lang.IllegalStateException: Error in Trigger\n\tat forge.Foo.bar(Foo.java:1)\r\n"
                + "Caused by: Empower\tfehlt  in ApiType";
        List<Exclusions.Eintrag> eintraege = List.of(
                new Exclusions.Eintrag("Anna", grund, "2026-10-02"),
                new Exclusions.Eintrag("Berta", "heil", "2026-09-25"));

        Exclusions.schreiben(datei, eintraege);
        List<Exclusions.Eintrag> gelesen = Exclusions.lesen(datei);

        assertEquals(2, gelesen.size(), "kein Eintrag geht verloren");
        assertEquals("Anna", gelesen.get(0).schluessel());
        assertEquals("java.lang.IllegalStateException: Error in Trigger at forge.Foo.bar(Foo.java:1) "
                + "Caused by: Empower fehlt in ApiType", gelesen.get(0).grund());
        assertEquals("2026-10-02", gelesen.get(0).seit(), "die Datumsspalte bleibt, wo sie ist");
        assertEquals(new Exclusions.Eintrag("Berta", "heil", "2026-09-25"), gelesen.get(1));
    }
}
