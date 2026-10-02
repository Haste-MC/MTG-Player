package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardFilesTest {

    private static void karte(Path res, String unterordner, String datei, String name) throws Exception {
        Path p = res.resolve("cardsfolder").resolve(unterordner).resolve(datei);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "Name:" + name + "\nManaCost:1\nTypes:Artifact\nOracle:\n");
    }

    private static void edition(Path res, String datei, String code) throws Exception {
        Path p = res.resolve("editions").resolve(datei);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "[metadata]\nCode=" + code + "\nDate=2027-01-01\n\n[cards]\n");
    }

    @Test
    void kartennameZeigtAufSeineDatei(@TempDir Path res) throws Exception {
        karte(res, "s", "sol_ring.txt", "Sol Ring");

        Map<String, List<Path>> index = CardFiles.karten(res);

        assertEquals(List.of(res.resolve("cardsfolder/s/sol_ring.txt")), index.get("Sol Ring"));
    }

    @Test
    void setCodeZeigtAufSeineDatei(@TempDir Path res) throws Exception {
        edition(res, "MagicFest 2027.txt", "PF27");

        assertEquals(List.of(res.resolve("editions/MagicFest 2027.txt")), CardFiles.editionen(res).get("PF27"));
    }

    /**
     * Der Fall, der beim Abgleich 2026-09-25 von Hand aufgeraeumt werden musste: upstream hatte zwei
     * Kartenskripte umbenannt, dadurch stand derselbe Kartenname in zwei Dateien.
     */
    @Test
    void derselbeNameInZweiDateienIstEineDublette(@TempDir Path res) throws Exception {
        karte(res, "w", "winter_cursed_raider.txt", "Winter, Cursed Raider");
        karte(res, "w", "winter_cursed_rider.txt", "Winter, Cursed Raider");

        assertEquals(List.of("Winter, Cursed Raider"), CardFiles.doppelte(CardFiles.karten(res)));
    }

    @Test
    void derselbeSetCodeInZweiDateienIstEineDublette(@TempDir Path res) throws Exception {
        edition(res, "Alt.txt", "YWOE");
        edition(res, "Neu.txt", "YWOE");

        assertEquals(List.of("YWOE"), CardFiles.doppelte(CardFiles.editionen(res)));
    }

    @Test
    void ohneDubletteIstDieListeLeer(@TempDir Path res) throws Exception {
        karte(res, "s", "sol_ring.txt", "Sol Ring");
        karte(res, "m", "mox_pearl.txt", "Mox Pearl");

        assertTrue(CardFiles.doppelte(CardFiles.karten(res)).isEmpty());
    }

    /**
     * Mehrere Dubletten werden sortiert zurueckgegeben, damit sich Abgleichsberichte zweier Laeufe vergleichen lassen.
     * Dieser Test prueft die Sortierung DIREKT durch Aufruf von doppelte() mit einer unsortierten LinkedHashMap:
     * Der Schluessel mit spaetem Anfangsbuchstaben wird zuerst eingefuegt, der mit fruhem danach.
     * Ist die Sortierlogik entfernt, gibt die Methode die Dubletten in der urspruenglichen Einfuegungsreihenfolge zurueck.
     */
    @Test
    void mehrererDublettenWerdenSortiert() {
        // Ungeordnete Map mit Einfuegungsreihenfolge: spaeter Buchstabe zuerst, dann frueher Buchstabe
        Map<String, List<Path>> unsortiert = new LinkedHashMap<>();
        Path zealotEins = Path.of("/cards/z/zealot_one.txt");
        Path zealotZwei = Path.of("/cards/z/zealot_two.txt");
        unsortiert.put("Zealot", new ArrayList<>(List.of(zealotEins, zealotZwei)));

        Path arborEins = Path.of("/cards/a/arbor_one.txt");
        Path arborZwei = Path.of("/cards/a/arbor_two.txt");
        unsortiert.put("Arbor Elf", new ArrayList<>(List.of(arborEins, arborZwei)));

        // Erwartete Ausgabe: alphabetisch sortiert, unabhaengig von Einfuegungsreihenfolge
        assertEquals(List.of("Arbor Elf", "Zealot"), CardFiles.doppelte(unsortiert));
    }

    /**
     * Nicht jede Datei unter cardsfolder/ ist ein Kartenskript. Sie darf nicht den Index verschmutzen,
     * aber auch nicht lautlos verschwinden - sonst merkt niemand, wenn der Parser daneben liegt.
     */
    @Test
    void dateiOhneNamenZeileWirdUebersprungenUndGezaehlt(@TempDir Path res) throws Exception {
        karte(res, "s", "sol_ring.txt", "Sol Ring");
        Path fremd = res.resolve("cardsfolder").resolve("liesmich.txt");
        Files.writeString(fremd, "kein Kartenskript\n");

        assertEquals(1, CardFiles.karten(res).size());
        assertEquals(1, CardFiles.uebersprungen(res));
    }

    @Test
    void fehlendesVerzeichnisLiefertLeerenIndex(@TempDir Path res) throws Exception {
        assertTrue(CardFiles.karten(res).isEmpty());
        assertTrue(CardFiles.editionen(res).isEmpty());
    }
}
