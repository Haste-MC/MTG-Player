package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import mtgplayer.carddata.CardDataSweep.Ergebnis;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;
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
                res, neueDateien(tmp)).entfernt();

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
                res, neueDateien(tmp)).entfernt();

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

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste).entfernt();

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
        karte(res, "x/ganz_andere.txt", "Ganz Andere");   // die Neu-Liste ist gueltig, nennt nur eine andere Datei
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/x/ganz_andere.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste).entfernt();

        assertEquals(0, n);
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    /** Eine fehlende Neu-Liste ist KEIN "keine neuen Dateien" (das ist die leere Datei), sondern ein Fehler. */
    @Test
    void fehlendeNeuListeIstEinFehlerUndLaesstBeideStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "d/doppelt.txt", "Doppelt");
        Path zwei = karte(res, "d/doppelt_zwei.txt", "Doppelt");

        assertThrows(NoSuchFileException.class, () -> CardDataSweep.aussortieren(
                befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, tmp.resolve("gibtsnicht.txt")));

        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    @Test
    void leereNeuListeBedeutetKeineNeuenDateien(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "d/doppelt.txt", "Doppelt");
        Path zwei = karte(res, "d/doppelt_zwei.txt", "Doppelt");

        Ergebnis e = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")),
                res, neueDateien(tmp));

        assertEquals(0, e.entfernt());
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    /** Leerraum am Zeilenrand (auch Tabulator, auch am Anfang) darf die neue Datei nicht unsichtbar machen. */
    @Test
    void leerraumAmZeilenrandDerNeuListeSchadetNicht(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "d/doppelt.txt", "Doppelt");
        Path neu = karte(res, "d/doppelt_neu.txt", "Doppelt");
        Path liste = neueDateien(tmp, "", "  forge-gui/res/cardsfolder/d/doppelt_neu.txt \t ", "   ");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of(), List.of("Doppelt")),
                res, liste).entfernt();

        assertEquals(1, n);
        assertTrue(Files.exists(alt));
        assertFalse(Files.exists(neu));
    }

    /**
     * Jede dieser Schreibweisen hat die Durchsicht nachgespielt: sie loeschte 0 Dateien und endete ohne
     * Meldung. Jetzt bricht sie ab - und loescht nichts.
     */
    @Test
    void neuListeInFalschemFormatBrichtLautAb(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "d/doppelt.txt", "Doppelt");
        Path neu = karte(res, "d/doppelt_neu.txt", "Doppelt");
        String richtig = "forge-gui/res/cardsfolder/d/doppelt_neu.txt";
        for (String zeile : List.of(
                "cardsfolder/d/doppelt_neu.txt",             // ohne Praefix forge-gui/res/
                "res/cardsfolder/d/doppelt_neu.txt",         // mit res/ davor
                "./" + richtig,                              // mit ./ davor
                "?? " + richtig,                             // rohes Statuskuerzel
                "forge-gui/res/cardsfolder/d/gibtsnicht.txt")) {
            Path liste = neueDateien(tmp, zeile);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> CardDataSweep.aussortieren(
                    befund(List.of(), List.of(), List.of(), List.of("Doppelt")), res, liste), zeile);

            assertTrue(e.getMessage().contains("forge-gui/res/"), "die Meldung nennt das erwartete Format");
            assertTrue(Files.exists(alt), zeile);
            assertTrue(Files.exists(neu), zeile);
        }
    }

    /** Nur Dubletten brauchen die Neu-Liste, um zu waehlen - sonst waere ein fremdes Format kein Hindernis. */
    @Test
    void neuListeInFremdemFormatStoertOhneDublettenNicht(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path kaputt = karte(res, "a/kaputt.txt", "Kaputt");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("Kaputt", "x")), List.of(), List.of(), List.of()),
                res, neueDateien(tmp, "irgendwas")).entfernt();

        assertEquals(1, n);
        assertFalse(Files.exists(kaputt));
    }

    @Test
    void doppelterSetCodeFaelltNurDieNeueEditionsdatei(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = edition(res, "Alt.txt", "YWOE");
        Path neu = edition(res, "Neu.txt", "YWOE");
        Path liste = neueDateien(tmp, "forge-gui/res/editions/Neu.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of("YWOE"), List.of()), res, liste).entfernt();

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
                res, neueDateien(tmp)).entfernt();

        assertEquals(0, n);
        assertTrue(Files.exists(alt));
        assertTrue(Files.exists(neu));
    }

    // ---- Befund 1: Schluessel ohne Datei ----

    @Test
    void schluesselOhneDateiKommenZurueckUndNichtInDieAusschlussliste(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path kaputt = karte(res, "a/kaputt.txt", "Kaputt");
        // Die verstuemmelte Schreibung, wie sie unter LC_ALL=POSIX aus "J\u00f6tun Grunt" wird.
        CardDataCheck.Befund b = new CardDataCheck.Befund(10,
                List.of(new CardDataCheck.Problem("Kaputt", "x"), new CardDataCheck.Problem("J?tun Grunt", "y")),
                List.of("Nirgends"), List.of("QQQ"), List.of("Doppelt Ohne Datei"), List.of());

        Ergebnis e = CardDataSweep.aussortieren(b, res, neueDateien(tmp));

        assertEquals(1, e.entfernt());
        assertFalse(Files.exists(kaputt));
        assertEquals(List.of("J?tun Grunt", "Nirgends", "Doppelt Ohne Datei", "QQQ"), e.ohneDatei());
        List<String> inDerListe = Exclusions.ergaenzen(List.of(), e.fortzuschreiben(), "2026-10-02").stream()
                .map(Exclusions.Eintrag::schluessel).toList();
        assertEquals(List.of("Kaputt"), inDerListe, "nur was eine Datei hatte, kommt auf die Liste");
    }

    /**
     * Der Weg bis zum Ende, im Kindprozess unter LC_ALL=POSIX (dort ist die Standardkodierung der JVM
     * ASCII): ein Schluessel mit Umlaut ohne Datei muss lesbar auf stderr stehen, der Lauf mit != 0
     * enden und die Ausschlussliste den Schluessel NICHT enthalten; die heile Karte wird trotzdem
     * aussortiert und eingetragen.
     */
    @Test
    void aussortierenMeldetSchluesselOhneDateiLesbarUndEndetMitFehler(@TempDir Path tmp) throws Exception {
        Path assets = tmp.resolve("assets");
        Path res = assets.resolve("res");
        Path kaputt = karte(res, "a/kaputt.txt", "Kaputt");
        CardDataCheck.Befund b = new CardDataCheck.Befund(10,
                List.of(new CardDataCheck.Problem("Kaputt", "x"),
                        new CardDataCheck.Problem("J\u00f6tun Grunt", "y")),
                List.of(), List.of(), List.of(), List.of());
        Path befundDatei = tmp.resolve("befund.json");
        Files.writeString(befundDatei, Json.toJson(b), StandardCharsets.UTF_8);
        Path liste = tmp.resolve("ausgeschlossen.txt");
        Path neu = neueDateien(tmp);

        String vorherAssets = System.getProperty("mtgplayer.assets");
        System.setProperty("mtgplayer.assets", assets.toString());
        List<String> cmd;
        try {
            cmd = ChildJvm.command(List.of("--kartendaten-aussortieren", befundDatei.toString(),
                    liste.toString(), neu.toString()));
        } finally {
            if (vorherAssets == null) {
                System.clearProperty("mtgplayer.assets");
            } else {
                System.setProperty("mtgplayer.assets", vorherAssets);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().remove("LC_CTYPE");
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        pb.environment().put("LC_ALL", "POSIX");
        pb.environment().put("LANG", "POSIX");
        Path out = tmp.resolve("out.txt");
        Path err = tmp.resolve("err.txt");
        pb.redirectOutput(out.toFile()).redirectError(err.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES), "Kindprozess haengt");

        String stderr = Files.readString(err, StandardCharsets.UTF_8);
        assertNotEquals(0, p.exitValue(), "bei einem Schluessel ohne Datei muss der Lauf scheitern: " + stderr);
        assertTrue(stderr.contains("J\u00f6tun Grunt"), "der Schluessel steht lesbar auf stderr: " + stderr);
        assertFalse(Files.exists(kaputt), "die heile Karte wird trotzdem aussortiert");
        String listeText = Files.readString(liste, StandardCharsets.UTF_8);
        assertTrue(listeText.contains("Kaputt\t"), listeText);
        assertFalse(listeText.contains("tun Grunt"), "der Schluessel ohne Datei steht NICHT auf der Liste: " + listeText);
    }

    // ---- Befund 3: Ueberschneidung ----

    /** Genau der Fall der Durchsicht: der Schluessel steht in nichtBaubar UND doppelteNamen. */
    @Test
    void nichtBaubarUndDubletteZugleichLaesstDieBestehendeDateiStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "x/x.txt", "X");
        Path neu = karte(res, "x/x_neu.txt", "X");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/x/x_neu.txt");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("X", "kaputt")), List.of(), List.of(), List.of("X")),
                res, liste).entfernt();

        assertEquals(1, n);
        assertTrue(Files.exists(alt), "die bisher funktionierende Karte bleibt");
        assertFalse(Files.exists(neu));
    }

    @Test
    void nichtBaubarMitMehrerenDateienLaesstNurDieNeueFallen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "x/x.txt", "X");
        Path neu = karte(res, "x/x_neu.txt", "X");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/x/x_neu.txt");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("X", "kaputt")), List.of(), List.of(), List.of()),
                res, liste).entfernt();

        assertEquals(1, n);
        assertTrue(Files.exists(alt));
        assertFalse(Files.exists(neu));
    }

    @Test
    void nichtAuffindbarMitMehrerenDateienLaesstNurDieNeueFallen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path alt = karte(res, "x/x.txt", "X");
        Path neu = karte(res, "x/x_neu.txt", "X");
        Path liste = neueDateien(tmp, "forge-gui/res/cardsfolder/x/x_neu.txt");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of("X"), List.of(), List.of()), res, liste)
                .entfernt();

        assertEquals(1, n);
        assertTrue(Files.exists(alt));
        assertFalse(Files.exists(neu));
    }

    /** Mehrere Dateien, keine davon neu: dann faellt keine - welche die kaputte ist, ist nicht zu raten. */
    @Test
    void nichtBaubarMitMehrerenDateienOhneNeueLaesstAlleStehen(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "x/x.txt", "X");
        Path zwei = karte(res, "x/x_zwei.txt", "X");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("X", "kaputt")), List.of(), List.of(), List.of()),
                res, neueDateien(tmp)).entfernt();

        assertEquals(0, n);
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
    }

    /** Die andere Haelfte: genau eine Datei faellt wie bisher, auch wenn sie nicht auf der Neu-Liste steht. */
    @Test
    void nichtBaubarMitGenauEinerDateiFaelltAuchOhneEintragAufDerNeuListe(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path einzige = karte(res, "x/x.txt", "X");

        int n = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("X", "kaputt")), List.of(), List.of(), List.of()),
                res, neueDateien(tmp)).entfernt();

        assertEquals(1, n);
        assertFalse(Files.exists(einzige));
    }

    @Test
    void nichtAuffindbarMitGenauEinerDateiFaelltAuchOhneEintragAufDerNeuListe(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path einzige = karte(res, "x/x.txt", "X");

        int n = CardDataSweep.aussortieren(befund(List.of(), List.of("X"), List.of(), List.of()), res,
                neueDateien(tmp)).entfernt();

        assertEquals(1, n);
        assertFalse(Files.exists(einzige));
    }

    // ---- Mehrere Dateien, keine neu: nichts faellt, also darf auch nichts auf die Ausschlussliste ----

    private static List<String> aufDerListe(Ergebnis e) {
        return Exclusions.ergaenzen(List.of(), e.fortzuschreiben(), "2026-10-02").stream()
                .map(Exclusions.Eintrag::schluessel).toList();
    }

    /** Der Fall der Durchsicht: X in nichtBaubar UND doppelteNamen, zwei Dateien, leere Neu-Liste. */
    @Test
    void nichtBaubarMitMehrerenDateienOhneNeueIstUngeloestUndNichtAufDerListe(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "x/x.txt", "X");
        Path zwei = karte(res, "x/x_zwei.txt", "X");

        Ergebnis e = CardDataSweep.aussortieren(
                befund(List.of(new CardDataCheck.Problem("X", "kaputt")), List.of(), List.of(), List.of("X")),
                res, neueDateien(tmp));

        assertEquals(0, e.entfernt());
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
        assertEquals(List.of("X"), e.mehrereKeineNeu());
        assertEquals(List.of(), e.ohneDatei(), "das ist nicht 'keine Datei gefunden'");
        assertEquals(List.of(), aufDerListe(e), "die kaputte Karte liegt noch da: nicht totschweigen");
    }

    @Test
    void nichtAuffindbarMitMehrerenDateienOhneNeueIstUngeloestUndNichtAufDerListe(@TempDir Path tmp)
            throws Exception {
        Path res = tmp.resolve("res");
        karte(res, "x/x.txt", "X");
        karte(res, "x/x_zwei.txt", "X");

        Ergebnis e = CardDataSweep.aussortieren(befund(List.of(), List.of("X"), List.of(), List.of()), res,
                neueDateien(tmp));

        assertEquals(0, e.entfernt());
        assertEquals(List.of("X"), e.mehrereKeineNeu());
        assertEquals(List.of(), aufDerListe(e));
    }

    /** Gegenfall: eine reine Dublette ohne neue Datei laeuft, beide Karten sind heil - Eintrag wie bisher. */
    @Test
    void reineDubletteOhneNeueDateiBleibtAufDerListeUndIstNichtUngeloest(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        Path eins = karte(res, "x/x.txt", "X");
        Path zwei = karte(res, "x/x_zwei.txt", "X");
        edition(res, "a.txt", "YWOE");
        edition(res, "b.txt", "YWOE");

        Ergebnis e = CardDataSweep.aussortieren(befund(List.of(), List.of(), List.of("YWOE"), List.of("X")), res,
                neueDateien(tmp));

        assertEquals(0, e.entfernt());
        assertTrue(Files.exists(eins));
        assertTrue(Files.exists(zwei));
        assertEquals(List.of(), e.mehrereKeineNeu());
        assertEquals(List.of(), e.ohneDatei());
        assertEquals(List.of("X", "YWOE"), aufDerListe(e).stream().sorted().toList());
    }

    private record Lauf(int exit, String stderr, String liste) { }

    /** --kartendaten-aussortieren im Kindprozess: Exit-Wert, stderr und die fortgeschriebene Ausschlussliste. */
    private static Lauf aussortierenImKind(Path tmp, Path assets, CardDataCheck.Befund b) throws Exception {
        Path befundDatei = tmp.resolve("befund.json");
        Files.writeString(befundDatei, Json.toJson(b), StandardCharsets.UTF_8);
        Path liste = tmp.resolve("ausgeschlossen.txt");
        Path neu = neueDateien(tmp);
        String vorherAssets = System.getProperty("mtgplayer.assets");
        System.setProperty("mtgplayer.assets", assets.toString());
        List<String> cmd;
        try {
            cmd = ChildJvm.command(List.of("--kartendaten-aussortieren", befundDatei.toString(),
                    liste.toString(), neu.toString()));
        } finally {
            if (vorherAssets == null) {
                System.clearProperty("mtgplayer.assets");
            } else {
                System.setProperty("mtgplayer.assets", vorherAssets);
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path err = tmp.resolve("err.txt");
        pb.redirectOutput(tmp.resolve("out.txt").toFile()).redirectError(err.toFile());
        Process p = pb.start();
        assertTrue(p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES), "Kindprozess haengt");
        return new Lauf(p.exitValue(), Files.readString(err, StandardCharsets.UTF_8),
                Files.exists(liste) ? Files.readString(liste, StandardCharsets.UTF_8) : "");
    }

    @Test
    void aussortierenMeldetMehrereDateienOhneNeueUnterscheidbarUndEndetMitFehler(@TempDir Path tmp)
            throws Exception {
        Path assets = tmp.resolve("assets");
        Path res = assets.resolve("res");
        Path eins = karte(res, "x/x.txt", "Zweifach");
        Path zwei = karte(res, "x/x_zwei.txt", "Zweifach");
        CardDataCheck.Befund b = new CardDataCheck.Befund(10,
                List.of(new CardDataCheck.Problem("Zweifach", "kaputt"), new CardDataCheck.Problem("Fehlt", "y")),
                List.of(), List.of(), List.of("Zweifach"), List.of());

        Lauf l = aussortierenImKind(tmp, assets, b);

        assertNotEquals(0, l.exit(), l.stderr());
        assertTrue(Files.exists(eins) && Files.exists(zwei));
        assertTrue(l.stderr().contains("MEHRERE Dateien"), l.stderr());
        assertTrue(l.stderr().contains("keine Datei unter"), "beide Ursachen getrennt gemeldet: " + l.stderr());
        assertTrue(l.stderr().contains("  Zweifach"), l.stderr());
        assertFalse(l.liste().contains("Zweifach"), "nicht auf der Liste: " + l.liste());
        assertFalse(l.liste().contains("Fehlt"), l.liste());
    }

    @Test
    void aussortierenEndetBeiReinerDubletteOhneNeueDateiMitNull(@TempDir Path tmp) throws Exception {
        Path assets = tmp.resolve("assets");
        Path res = assets.resolve("res");
        karte(res, "x/x.txt", "Doppelt");
        karte(res, "x/x_zwei.txt", "Doppelt");
        CardDataCheck.Befund b = new CardDataCheck.Befund(10, List.of(), List.of(), List.of(),
                List.of("Doppelt"), List.of());

        Lauf l = aussortierenImKind(tmp, assets, b);

        assertEquals(0, l.exit(), l.stderr());
        assertTrue(l.liste().contains("Doppelt\t"), l.liste());
    }
}
