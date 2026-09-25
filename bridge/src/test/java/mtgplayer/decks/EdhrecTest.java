package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
