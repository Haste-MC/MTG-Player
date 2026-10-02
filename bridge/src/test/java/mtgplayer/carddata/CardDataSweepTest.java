package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Das Aussortieren muss genau die Dateien loeschen, die der Befund meint - und nie die falsche Dublette. */
class CardDataSweepTest {

    private static Path karte(Path res, String datei, String name) throws Exception {
        Path p = res.resolve("cardsfolder").resolve(datei);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "Name:" + name + "\nManaCost:1\n");
        return p;
    }

    private static Path edition(Path res, String datei, String code) throws Exception {
        Path p = res.resolve("editions").resolve(datei);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "[metadata]\nCode=" + code + "\n");
        return p;
    }

    private static CardDataCheck.Befund befund(List<CardDataCheck.Problem> nichtBaubar,
            List<String> nichtAuffindbar, List<String> doppelteSetCodes, List<String> doppelteNamen) {
        return new CardDataCheck.Befund(10, nichtBaubar, nichtAuffindbar, doppelteSetCodes, doppelteNamen,
                List.of());
    }

    private static Path neueDateien(Path tmp, String... zeilen) throws Exception {
        Path p = tmp.resolve("neu.txt");
        Files.write(p, List.of(zeilen));
        return p;
    }

    @Test
    void nichtBaubareKarteWirdAnIhremVorderseitenNamenGefunden(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path kaputt = karte(res, "a/kaputt.txt", "Kaputt");
        Path teil = karte(res, "f/fuse.txt", "Wetterleuchten");   // Split-Karte: Skript fuehrt nur die Vorderseite
        Path heil = karte(res, "h/heil.txt", "Heil");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("Kaputt", "x"),
                        new CardDataCheck.Problem("Wetterleuchten", "y")), List.of(), List.of(), List.of()),
                res, tmp.resolve("keine-neuen.txt"));

        assertEquals(2, n);
        assertFalse(Files.exists(kaputt));
        assertFalse(Files.exists(teil));
        assertTrue(Files.exists(heil), "unbeteiligte Karten bleiben");
    }

    @Test
    void nichtAuffindbareKarteWirdEntfernt(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path weg = karte(res, "u/unlesbar.txt", "Unlesbar");
        Path heil = karte(res, "h/heil.txt", "Heil");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of("Unlesbar"), List.of(), List.of()),
                res, tmp.resolve("keine-neuen.txt"));

        assertEquals(1, n);
        assertFalse(Files.exists(weg));
        assertTrue(Files.exists(heil));
    }

    @Test
    void dubletteFaelltNurDieNeueDatei(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "d/doppelt.txt", "Doppelt");
        Path neu = karte(res, "d/doppelt_neu.txt", "Doppelt");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/d/doppelt_neu.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste);

        assertEquals(1, n);
        assertTrue(Files.exists(alt), "die bestehende Karte bleibt");
        assertFalse(Files.exists(neu), "die neu hereingekommene faellt");
    }

    /** Spiegelbild: steht die ALTE Datei auf der Liste, faellt sie - die Wahl folgt der Liste, nicht der Sortierung. */
    @Test
    void dubletteFaelltDieDateiAufDerNeuListeAuchWennSieZuerstSortiert(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path vorn = karte(res, "d/a_doppelt.txt", "Doppelt");
        Path hinten = karte(res, "d/z_doppelt.txt", "Doppelt");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/d/a_doppelt.txt");

        CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste);

        assertFalse(Files.exists(vorn));
        assertTrue(Files.exists(hinten));
    }

    @Test
    void dubletteOhneNeueDateiLaesstBeideStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "d/doppelt.txt", "Doppelt");
        Path zwei = karte(res, "d/doppelt_zwei.txt", "Doppelt");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/x/ganz_andere.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste);

        assertEquals(0, n);
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    @Test
    void dubletteOhneNeueDateienListeLaesstBeideStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "d/doppelt.txt", "Doppelt");
        Path zwei = karte(res, "d/doppelt_zwei.txt", "Doppelt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")),
                res, tmp.resolve("gibtsnicht.txt"));

        assertEquals(0, n);
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    @Test
    void doppelterSetCodeFaelltNurDieNeueEditionsdatei(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = edition(res, "Alt.txt", "YWOE");
        Path neu = edition(res, "Neu.txt", "YWOE");
        Path liste = neueDateien(tmp, "forge-gui/res/editions/Neu.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of("YWOE"), List.of()), res, liste);

        assertEquals(1, n);
        assertTrue(Files.exists(alt));
        assertFalse(Files.exists(neu));
    }

    @Test
    void doppelterSetCodeOhneNeueDateiLaesstBeideStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = edition(res, "Alt.txt", "YWOE");
        Path neu = edition(res, "Neu.txt", "YWOE");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of("YWOE"), List.of()),
                res, tmp.resolve("keine.txt"));

        assertEquals(0, n);
        assertTrue(Files.exists(alt));
        assertTrue(Files.exists(neu));
    }
}
