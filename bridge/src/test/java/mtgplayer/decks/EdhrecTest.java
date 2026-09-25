package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** EDHREC-Quelle (Spec 2026-09-25-kartenvorschlaege §3). Kein Test geht ins Netz: die Quelle wird als
 *  Funktion eingesetzt, der Zwischenspeicher liegt in einem Temp-Verzeichnis. */
class EdhrecTest {

    private static String fixture() throws Exception {
        try (var in = EdhrecTest.class.getResourceAsStream("/edhrec-titania.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void slugNimmtKommaPunktUndApostrophRaus() {
        assertEquals("titania-protector-of-argoth", Edhrec.slug(List.of("Titania, Protector of Argoth")));
        assertEquals("ms-bumbleflower", Edhrec.slug(List.of("Ms. Bumbleflower")));
        assertEquals("kaalia-of-the-vast", Edhrec.slug(List.of("Kaalia of the Vast")));
        // Apostroph mitten im Wort - wird ersatzlos gestrichen, nicht durch "-" ersetzt
        assertEquals("krrik-son-of-yawgmoth", Edhrec.slug(List.of("K'rrik, Son of Yawgmoth")));
        // Apostroph am Wortende ("tiger's" -> "tigers"): "yuriko-the-tiger-s-shadow" liefert bei EDHREC 403
        assertEquals("yuriko-the-tigers-shadow", Edhrec.slug(List.of("Yuriko, the Tiger's Shadow")));
        assertEquals("kroxa-titan-of-deaths-hunger", Edhrec.slug(List.of("Kroxa, Titan of Death's Hunger")));
        // doppelseitige Karte: nur die Vorderseite zaehlt, der Slug mit beiden Seiten liefert 403
        assertEquals("esika-god-of-the-tree",
                Edhrec.slug(List.of("Esika, God of the Tree // The Prismatic Bridge")));
    }

    /** Wichtiger Befund (Nachtrag): eine Namensliste mit einem {@code null}-Eintrag darf slug() nicht mit
     *  einer NullPointerException abschiessen - null und leere Namen werden uebersprungen. */
    @Test
    void slugUeberspringtNullUndLeereNamen() {
        assertEquals("titania-protector-of-argoth",
                Edhrec.slug(Arrays.asList("Titania, Protector of Argoth", null, "")));
        assertEquals("", Edhrec.slug(Arrays.asList(null, "")));
    }

    /** Dieselbe Situation ueber page(): auch dort nie werfen, sondern leeres Optional (Aufrufer wie
     *  Suggestions duerfen sich darauf verlassen). */
    @Test
    void pageWirftNichtBeiNullInDerNamensliste(@TempDir Path dir) {
        Edhrec e = new Edhrec(url -> { throw new RuntimeException("kein Netz"); }, dir);
        assertTrue(e.page(Arrays.asList((String) null)).isEmpty());
    }

    /** Partner: EDHREC fuehrt sie unter einem gemeinsamen Slug in alphabetischer Reihenfolge - die
     *  Eingabereihenfolge darf also nichts aendern. */
    @Test
    void slugSortiertPartnerAlphabetisch() {
        String expected = "thrasios-triton-hero-tymna-the-weaver";
        assertEquals(expected, Edhrec.slug(List.of("Thrasios, Triton Hero", "Tymna the Weaver")));
        assertEquals(expected, Edhrec.slug(List.of("Tymna the Weaver", "Thrasios, Triton Hero")));
    }

    @Test
    void parseLiestAnteilUndGameChanger() throws Exception {
        Edhrec.Page page = Edhrec.parse("titania-protector-of-argoth", fixture(), Instant.now());
        Edhrec.Card crop = page.byName().get("crop rotation");
        assertTrue(crop.gameChanger(), "Crop Rotation steht in der Game-Changer-Liste");
        assertTrue(crop.share() > 0 && crop.share() <= 1, "Anteil zwischen 0 und 1: " + crop.share());
        Edhrec.Card zuran = page.byName().get("zuran orb");
        assertFalse(zuran.gameChanger(), "Zuran Orb steht nicht in der Game-Changer-Liste");
    }

    /** Dieselbe Karte steht in mehreren Listen (z. B. "High Synergy Cards" und "Creatures"); sie darf nur
     *  einmal auftauchen, und die Game-Changer-Marke darf dabei nicht verloren gehen. */
    @Test
    void parseFuehrtJedeKarteNurEinmal() throws Exception {
        Edhrec.Page page = Edhrec.parse("x", fixture(), Instant.now());
        assertEquals(page.cards().size(), page.byName().size(), "doppelte Namen in der Liste");
    }

    /** Baut selbst ein JSON mit derselben Karte in zwei Listen - "Game Changers" mit dem niedrigeren
     *  Anteil, "Creatures" mit dem hoeheren. Der echte Pruefstein (edhrec-titania.json) enthaelt zufaellig
     *  keine solche Dopplung, deckt die Zusammenfuehrung in parse() also nicht ab. */
    private static String zweiListenJson(boolean gameChangersZuerst) {
        String gameChangers = "{\"header\":\"Game Changers\",\"cardviews\":["
                + "{\"name\":\"Sol Ring\",\"num_decks\":10,\"potential_decks\":100}]}";
        String creatures = "{\"header\":\"Creatures\",\"cardviews\":["
                + "{\"name\":\"Sol Ring\",\"num_decks\":90,\"potential_decks\":100}]}";
        String lists = gameChangersZuerst ? gameChangers + "," + creatures : creatures + "," + gameChangers;
        return "{\"container\":{\"json_dict\":{\"cardlists\":[" + lists + "]}}}";
    }

    private static void pruefeZusammenfuehrung(String json) {
        Edhrec.Page page = Edhrec.parse("x", json, Instant.now());
        long count = page.cards().stream().filter(c -> c.name().equalsIgnoreCase("Sol Ring")).count();
        assertEquals(1, count, "Sol Ring darf nur einmal in cards() stehen");
        Edhrec.Card sol = page.byName().get("sol ring");
        assertEquals(0.9, sol.share(), 1e-9, "der hoehere Anteil (aus \"Creatures\") muss gewinnen");
        assertTrue(sol.gameChanger(), "die Game-Changer-Marke aus der anderen Liste darf nicht verloren gehen");
    }

    /** Zusammenfuehrung, "Game Changers" (niedrigerer Anteil) steht zuerst im JSON. */
    @Test
    void parseUebernimmtHoeherenAnteilUndBehaeltMarkeGameChangerZuerst() {
        pruefeZusammenfuehrung(zweiListenJson(true));
    }

    /** Dieselbe Zusammenfuehrung mit vertauschter Listenreihenfolge ("Game Changers" zuletzt) - das
     *  Ergebnis darf nicht davon abhaengen, welche Liste im JSON zuerst kommt. */
    @Test
    void parseUebernimmtHoeherenAnteilUndBehaeltMarkeGameChangerZuletzt() {
        pruefeZusammenfuehrung(zweiListenJson(false));
    }

    @Test
    void abrufWirdZwischengespeichert(@TempDir Path dir) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String json = fixture();
        Function<String, String> source = url -> { calls.incrementAndGet(); return json; };
        Edhrec first = new Edhrec(source, dir);
        assertTrue(first.page(List.of("Titania, Protector of Argoth")).isPresent());
        // Zweiter Zugriff, frische Instanz: der Stand liegt auf der Platte, es darf kein Abruf mehr kommen.
        Edhrec second = new Edhrec(source, dir);
        assertTrue(second.page(List.of("Titania, Protector of Argoth")).isPresent());
        assertEquals(1, calls.get(), "der zwischengespeicherte Stand wurde nicht benutzt");
    }

    /** Abgelaufener Stand und die Quelle wirft: lieber alte Daten als keine (Spec §3). */
    @Test
    void abgelaufenerStandUeberlebtEinenFehlschlag(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("titania-protector-of-argoth.json");
        Files.writeString(file, fixture());
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(
                Instant.now().minus(Duration.ofDays(30))));
        Edhrec e = new Edhrec(url -> { throw new RuntimeException("kein Netz"); }, dir);
        Optional<Edhrec.Page> page = e.page(List.of("Titania, Protector of Argoth"));
        assertTrue(page.isPresent(), "alter Stand haette benutzt werden muessen");
        assertTrue(page.get().fetched().isBefore(Instant.now().minus(Duration.ofDays(7))));
    }

    @Test
    void ohneStandUndOhneNetzKeineSeite(@TempDir Path dir) {
        Edhrec e = new Edhrec(url -> { throw new RuntimeException("kein Netz"); }, dir);
        assertTrue(e.page(List.of("Titania, Protector of Argoth")).isEmpty());
    }

    @Test
    void kaputteAntwortWirftNicht(@TempDir Path dir) {
        Edhrec e = new Edhrec(url -> "{kein json", dir);
        assertTrue(e.page(List.of("Titania, Protector of Argoth")).isEmpty());
    }

    @Test
    void ohneCommanderKeinAbruf(@TempDir Path dir) {
        AtomicInteger calls = new AtomicInteger();
        Edhrec e = new Edhrec(url -> { calls.incrementAndGet(); return "{}"; }, dir);
        assertTrue(e.page(List.of()).isEmpty());
        assertEquals(0, calls.get());
    }
}
