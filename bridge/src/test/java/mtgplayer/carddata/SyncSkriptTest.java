package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Prueft NUR den Uebernahme-Schritt des Skripts ({@code --nur-uebernehmen}): aus einem erfundenen
 * upstream-Stand muss genau das Richtige im Arbeitsbaum landen - neue Dateien, geaenderte Dateien,
 * und Geloeschtes muss verschwinden. Ohne Forge, ohne Netz.
 *
 * <p>Der zweite Teil ({@code Voller Durchgang}) prueft den Rest des Skripts: Ruecknahme bei Fehlschlag,
 * Zuordnung der Rueckgabewerte, Format der Neu-Liste, Zurueckholen unserer alten Fassung und die
 * Namens-Gegenprobe. Dazu laeuft das Skript in einem Wegwerf-Verzeichnis mit einem Git-Repo als
 * "forge" und einer Attrappe fuer {@code java} (siehe {@link #baueWurzel}), die die Befunde und das
 * Aussortieren der Bridge nachspielt.</p>
 */
class SyncSkriptTest {

    private static int lauf(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("bash",
                Path.of("..", "scripts", "kartendaten-sync.sh").toAbsolutePath().toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        return p.waitFor();
    }

    /** Ein Mini-Git-Repo mit einem Zweig "upstream-test", der die Kartendaten anders hat. */
    private static Path baueRepo(Path tmp) throws Exception {
        Path repo = tmp.resolve("repo");
        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        Files.createDirectories(res);
        Files.writeString(res.resolve("sol_ring.txt"), "Name:Sol Ring\n");
        Files.writeString(res.resolve("alt.txt"), "Name:Alte Karte\n");
        git(repo, "init", "-q", "-b", "mtg-player");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "basis");

        git(repo, "checkout", "-q", "-b", "upstream-test");
        Files.writeString(res.resolve("sol_ring.txt"), "Name:Sol Ring\nOracle:neu\n");
        Files.writeString(res.resolve("neu.txt"), "Name:Neue Karte\n");
        Files.delete(res.resolve("alt.txt"));
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "upstream");
        git(repo, "checkout", "-q", "mtg-player");
        return repo;
    }

    private static void git(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Files.createDirectories(repo);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void uebernimmtNeueGeaenderteUndGeloeschteDateien(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        assertTrue(Files.exists(res.resolve("neu.txt")), "neue Datei ist da");
        assertFalse(Files.exists(res.resolve("alt.txt")), "geloeschte Datei ist weg");
        assertTrue(Files.readString(res.resolve("sol_ring.txt")).contains("Oracle:neu"),
                "geaenderte Datei wurde uebernommen");
    }

    @Test
    void ausgeschlosseneKartenKommenGarNichtErstHerein(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);
        Path liste = repo.resolve("ausgeschlossen.txt");
        Files.writeString(liste, "Neue Karte\tnicht baubar\t2026-10-02\n");

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test", "--ausschluss", liste.toString()));

        assertFalse(Files.exists(repo.resolve("forge-gui/res/cardsfolder/s/neu.txt")),
                "die ausgeschlossene Karte wurde nicht uebernommen");
    }

    @Test
    void einAusgeschlossenerNameLaesstDieGuteAlteDateiStehen(@TempDir Path tmp) throws Exception {
        // Eintrag "Kartenname in mehr als einer Datei": der Schluessel haengt auch an der alten, guten Datei.
        Path repo = baueRepo(tmp);
        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        git(repo, "checkout", "-q", "upstream-test");
        Files.writeString(res.resolve("sol_ring_zwei.txt"), "Name:Sol Ring\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "dublette");
        git(repo, "checkout", "-q", "mtg-player");
        Path liste = repo.resolve("ausgeschlossen.txt");
        Files.writeString(liste, "Sol Ring\tKartenname in mehr als einer Datei\t2026-10-02\n");

        assertEquals(0, lauf(repo, "--nur-uebernehmen", "upstream-test", "--ausschluss", liste.toString()));

        assertTrue(Files.readString(res.resolve("sol_ring.txt")).contains("Oracle:neu"),
                "die alte Datei bleibt, in upstreams Fassung");
        assertFalse(Files.exists(res.resolve("sol_ring_zwei.txt")), "nur die neue Zweitdatei fiel");
    }

    @Test
    void unbekannterStandBrichtAbUndLaesstDenBaumUnberuehrt(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "gibt-es-nicht"));

        Path res = repo.resolve("forge-gui/res/cardsfolder/s");
        assertTrue(Files.exists(res.resolve("alt.txt")), "nichts wurde geloescht");
        assertFalse(Files.exists(res.resolve("neu.txt")), "nichts wurde uebernommen");
    }

    @Test
    void nichtEingecheckteHandarbeitWirdNichtUeberschrieben(@TempDir Path tmp) throws Exception {
        Path repo = baueRepo(tmp);
        Path alt = repo.resolve("forge-gui/res/cardsfolder/s/alt.txt");
        Files.writeString(alt, "Name:Alte Karte\nvon Hand geaendert\n");

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        assertTrue(Files.readString(alt).contains("von Hand geaendert"), "Handarbeit ist noch da");
    }

    @Test
    void fehlendesVerzeichnisInUpstreamBrichtAbStattAlleszuLoeschen(@TempDir Path tmp) throws Exception {
        // Bei uns gibt es editions/, upstream hat es nicht mehr (Umbau): nichts anfassen, laut abbrechen.
        Path repo = baueRepo(tmp);
        Path editions = repo.resolve("forge-gui/res/editions");
        git(repo, "checkout", "-q", "mtg-player");
        Files.createDirectories(editions);
        Files.writeString(editions.resolve("A.txt"), "Code=A\n");
        git(repo, "add", "-A");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "editions");

        assertEquals(1, lauf(repo, "--nur-uebernehmen", "upstream-test"));

        assertTrue(Files.exists(editions.resolve("A.txt")), "die Editionen sind noch da");
        assertTrue(Files.exists(repo.resolve("forge-gui/res/cardsfolder/s/alt.txt")), "nichts geloescht");
    }

    // ------------------------------------------------------------------------------------------
    // Voller Durchgang mit Java-Attrappe
    // ------------------------------------------------------------------------------------------

    private static final String BASIS_SOL = "Name:Sol Ring\n";
    private static final String BASIS_ALT = "Name:Alte Karte\n";

    private record Lauf(int rc, String ausgabe) { }

    /** Ein Wegwerf-Verzeichnis, das aussieht wie die Wurzel von MTG-Player. */
    private record Wurzel(Path wurzel, Path forge, Path steuer, Path bin) {
        Path liste() {
            return wurzel.resolve("docs/ausschluss.txt");
        }

        Path bericht() {
            return wurzel.resolve("kartendaten-bericht.md");
        }

        Path karte(String unter) {
            return forge.resolve("forge-gui/res/cardsfolder").resolve(unter);
        }
    }

    /**
     * Baut: scripts/kartendaten-sync.sh (Kopie), forge/ als eigenes Git-Repo (Zweig "mtg-player" = unser
     * Stand), eine "gebaute" Bridge (leere Ordner), den Symlink bridge/assets/res und eine Attrappe
     * {@code java}, die statt der Bridge antwortet: Pruefmodus -> der n-te Befund aus steuer/befund-n.json,
     * Aussortieren -> steuer/aussortieren.sh (falls vorhanden; sein Rueckgabewert ist der der Attrappe),
     * Partie -> TRIMMED_RESULT.
     */
    private static Wurzel baueWurzel(Path tmp) throws Exception {
        Path wurzel = tmp.resolve("wurzel");
        Path forge = wurzel.resolve("forge");
        Path steuer = tmp.resolve("steuer");
        Path bin = tmp.resolve("bin");
        Files.createDirectories(wurzel.resolve("scripts"));
        Files.createDirectories(wurzel.resolve("docs"));
        Files.createDirectories(wurzel.resolve("bridge/target/classes/mtgplayer"));
        Files.createDirectories(wurzel.resolve("bridge/assets"));
        Files.createDirectories(wurzel.resolve("target"));
        Files.createDirectories(steuer);
        Files.createDirectories(bin);
        Files.copy(Path.of("..", "scripts", "kartendaten-sync.sh"),
                wurzel.resolve("scripts/kartendaten-sync.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(wurzel.resolve("target/cp.txt"), "unbenutzt");
        Files.createSymbolicLink(wurzel.resolve("bridge/assets/res"), Path.of("../../forge/forge-gui/res"));

        Path s = forge.resolve("forge-gui/res/cardsfolder/s");
        Files.createDirectories(s);
        Files.writeString(s.resolve("sol_ring.txt"), BASIS_SOL);
        Files.writeString(s.resolve("alt.txt"), BASIS_ALT);
        git(forge, "init", "-q", "-b", "mtg-player");
        git(forge, "add", "-A");
        git(forge, "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "basis");

        Path java = bin.resolve("java");
        Files.writeString(java, """
                #!/usr/bin/env bash
                st="$FAKE_STEUER"
                args=("$@"); i=0
                while [ "${args[$i]}" != "mtgplayer.Main" ]; do i=$((i+1)); done
                modus="${args[$((i+1))]}"
                rest=("${args[@]:$((i+2))}")
                case "$modus" in
                  --kartendaten-pruefen)
                    n=$(( $(cat "$st/pruefen-n" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$st/pruefen-n"
                    echo "irgendeine Forge-Meldung"
                    echo "KARTENDATEN_BEFUND $(cat "$st/befund-$n.json")" ;;
                  --kartendaten-aussortieren)
                    cp "${rest[2]}" "$st/neu-gesehen.txt"
                    if [ -f "$st/aussortieren.sh" ]; then bash "$st/aussortieren.sh" "${rest[@]}"; exit $?; fi
                    exit 0 ;;
                  --trimmed-check) echo "TRIMMED_RESULT ok" ;;
                  *) echo "unerwarteter Modus: $modus" >&2; exit 99 ;;
                esac
                """);
        java.toFile().setExecutable(true);
        return new Wurzel(wurzel, forge, steuer, bin);
    }

    /** Legt auf dem Zweig "upstream-test" einen Stand an, den ein Shell-Schnipsel im forge-Verzeichnis baut. */
    private static void upstream(Wurzel w, String shell) throws Exception {
        git(w.forge(), "checkout", "-q", "-b", "upstream-test");
        Process p = new ProcessBuilder("bash", "-c", shell).directory(w.forge().toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(System.out);
        assertEquals(0, p.waitFor(), "Schnipsel: " + shell);
        git(w.forge(), "add", "-A");
        git(w.forge(), "-c", "user.email=t@t", "-c", "user.name=T", "commit", "-q", "-m", "upstream");
        git(w.forge(), "checkout", "-q", "mtg-player");
    }

    private static String json(int karten, String... nichtBaubar) {
        return "{\"karten\":" + karten + ",\"nichtBaubar\":[" + String.join(",", nichtBaubar)
                + "],\"nichtAuffindbar\":[],\"doppelteSetCodes\":[],\"doppelteNamen\":[],\"parseMeldungen\":[]}";
    }

    private static String problem(String karte, String grund) {
        return "{\"karte\":\"" + karte + "\",\"grund\":\"" + grund + "\"}";
    }

    /** Die drei Befunde, die das Skript nacheinander anfordert: vorher, roh (nach dem Uebernehmen), nachher. */
    private static void befunde(Wurzel w, String vorher, String roh, String nachher) throws Exception {
        Files.writeString(w.steuer().resolve("befund-1.json"), vorher);
        Files.writeString(w.steuer().resolve("befund-2.json"), roh);
        Files.writeString(w.steuer().resolve("befund-3.json"), nachher);
    }

    private static void aussortieren(Wurzel w, String shell, int rc) throws Exception {
        Files.writeString(w.steuer().resolve("aussortieren.sh"), shell + "\nexit " + rc + "\n");
    }

    private static Lauf laufVoll(Wurzel w, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("bash", "scripts/kartendaten-sync.sh"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(w.wurzel().toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", w.bin() + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE_STEUER", w.steuer().toString());
        pb.environment().remove("KARTENDATEN_BEHALTEN");
        Process p = pb.start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        System.out.println(ausgabe);
        return new Lauf(rc, ausgabe);
    }

    private static String gitAusgabe(Path repo, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String ausgabe = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
        return ausgabe;
    }

    // --- Befund 2a: Fehlschlag laesst den Arbeitsbaum unveraendert ------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void einFehlschlagImAussortierenLaesstDenArbeitsbaumUnveraendert(boolean listeVorhanden,
                                                                    @TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        upstream(w, "echo 'Oracle:neu' >> forge-gui/res/cardsfolder/s/sol_ring.txt;"
                + " echo 'Name:Neue Karte' > forge-gui/res/cardsfolder/s/neu.txt");
        String vorherListe = "# Kopf\nAlt\tgrund\t2026-01-01\n";
        if (listeVorhanden) {
            Files.writeString(w.liste(), vorherListe);
        }
        befunde(w, json(2), json(3, problem("Neue Karte", "kaputt")), json(2));
        // Wie die echte Bridge: erst loeschen und die Liste fortschreiben, dann scheitern.
        aussortieren(w, "rm -f '" + w.karte("s/neu.txt") + "'\n"
                + "printf 'Neue Karte\\tkaputt\\t2026-10-02\\n' >> \"$2\"", 1);
        String kopfVorher = gitAusgabe(w.forge(), "rev-parse", "HEAD");

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(1, lauf.rc());
        assertTrue(lauf.ausgabe().contains("ABBRUCH im Schritt: 3. Pruefen und aussortieren"), lauf.ausgabe());
        assertTrue(lauf.ausgabe().contains("Arbeitsbaum zurueckgesetzt"), lauf.ausgabe());
        assertEquals("", gitAusgabe(w.forge(), "status", "--porcelain", "-uall"),
                "weder Index noch Arbeitsbaum haben etwas Uebriges");
        assertEquals(kopfVorher, gitAusgabe(w.forge(), "rev-parse", "HEAD"));
        assertEquals(BASIS_SOL, Files.readString(w.karte("s/sol_ring.txt")), "geaenderte Karte zurueck");
        assertFalse(Files.exists(w.karte("s/neu.txt")), "neue Karte weg");
        assertEquals(BASIS_ALT, Files.readString(w.karte("s/alt.txt")));
        if (listeVorhanden) {
            assertEquals(vorherListe, Files.readString(w.liste()), "Ausschlussliste wie vorher");
        } else {
            assertFalse(Files.exists(w.liste()), "es gab keine Liste, es gibt auch keine");
        }
    }

    // --- Befund 2b: Rueckgabewerte 2 und 3 des Aussortierens sind beide ein Fehlschlag -----------

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void javaRueckgabewert2Und3ImAussortierenEndenBeideMit1(int javaRc, @TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        upstream(w, "echo 'Name:Neue Karte' > forge-gui/res/cardsfolder/s/neu.txt");
        befunde(w, json(2), json(3), json(3));
        aussortieren(w, "echo 'KARTENDATEN_AUSSORTIEREN_FEHLER Attrappe' >&2", javaRc);

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(1, lauf.rc(), "2 hiesse fuers aufrufende Skript 'nichts Neues' - darf nie durchkommen");
        assertTrue(lauf.ausgabe().contains("Aussortieren endete mit " + javaRc), lauf.ausgabe());
        assertFalse(Files.exists(w.karte("s/neu.txt")), "zurueckgenommen");
    }

    // --- Befund 2c: Format der Neu-Liste ---------------------------------------------------------

    @Test
    void dieNeuListeHatJeZeileDenPfadUnterForgeGuiResAuchBeiUmlautUndNeuemUnterverzeichnis(
            @TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        // Der Dateiname mit Umlaut wird per printf aus Bytes gebaut: die JVM muss ihn nicht kodieren koennen.
        upstream(w, "echo 'Oracle:neu' >> forge-gui/res/cardsfolder/s/sol_ring.txt;"
                + " mkdir -p forge-gui/res/cardsfolder/j forge-gui/res/cardsfolder/neuverz;"
                + " printf 'Name:J\\303\\266tun Grunt\\n' > \"forge-gui/res/cardsfolder/j/$(printf 'j\\303\\266tun.txt')\";"
                + " echo 'Name:Ding' > forge-gui/res/cardsfolder/neuverz/ding.txt");
        befunde(w, json(2), json(4), json(4));

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(0, lauf.rc(), lauf.ausgabe());
        byte[] roh = Files.readAllBytes(w.wurzel().resolve("kartendaten-neu.txt"));
        String text = new String(roh, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("\n") && !text.contains("\r"), "Zeilenende je Zeile ein \\n");
        assertFalse(text.contains("\""), "keine Anfuehrungszeichen um Pfade mit Sonderzeichen");
        assertFalse(text.contains("\\"), "keine Oktal-Escapes wie \\303");
        List<String> zeilen = Arrays.asList(text.split("\n"));
        assertEquals(Set.of(
                "forge-gui/res/cardsfolder/j/jötun.txt",
                "forge-gui/res/cardsfolder/neuverz/ding.txt",
                "forge-gui/res/cardsfolder/s/sol_ring.txt"), new HashSet<>(zeilen), text);
        assertEquals(3, zeilen.size(), "eine Zeile je Datei, keine Verzeichnis-Sammelzeile");
        assertEquals(text, Files.readString(w.steuer().resolve("neu-gesehen.txt")),
                "das Aussortieren bekam genau diese Datei");
    }

    // --- Befund 1: eine bisher spielbare Karte geht nicht verloren -------------------------------

    @Test
    void faelltUpstreamsNeueFassungEinerVorhandenenKarteDurchBleibtUnsereAlte(@TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        upstream(w, "echo 'Oracle:neu' >> forge-gui/res/cardsfolder/s/sol_ring.txt;"
                + " echo 'Name:Neue Karte' > forge-gui/res/cardsfolder/s/neu.txt");
        befunde(w, json(2), json(3, problem("Sol Ring", "Parameter Foo unbekannt")), json(3));
        // Wie die echte Bridge: Datei loeschen, Name auf die Liste.
        aussortieren(w, "rm -f '" + w.karte("s/sol_ring.txt") + "'\n"
                + "printf 'Sol Ring\\tParameter Foo unbekannt\\t2026-10-02\\n' >> \"$2\"", 0);

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(0, lauf.rc(), lauf.ausgabe());
        assertEquals(BASIS_SOL, Files.readString(w.karte("s/sol_ring.txt")), "unsere alte Fassung ist zurueck");
        assertFalse(Files.readString(w.liste()).contains("Sol Ring"), "die Karte laeuft, sie ist nicht ausgeschlossen");
        assertEquals("A\tforge-gui/res/cardsfolder/s/neu.txt\n",
                gitAusgabe(w.forge(), "diff", "--cached", "--name-status", "HEAD"),
                "gegenueber HEAD ist nur die neue Karte hinzugekommen");
        String bericht = Files.readString(w.bericht());
        assertTrue(bericht.contains("Bleibt auf unserer bisherigen Fassung (1)"), bericht);
        assertTrue(bericht.contains("`Sol Ring`: Parameter Foo unbekannt"), bericht);
        assertTrue(bericht.contains("Nichts neu ausgeschlossen"), bericht);
    }

    @Test
    void faelltEineWirklichNeueDateiDurchWirdSieGeloeschtUndAusgeschlossen(@TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        upstream(w, "echo 'Oracle:neu' >> forge-gui/res/cardsfolder/s/sol_ring.txt;"
                + " echo 'Name:Neue Karte' > forge-gui/res/cardsfolder/s/neu.txt");
        befunde(w, json(2), json(3, problem("Neue Karte", "Parameter Foo unbekannt")), json(2));
        aussortieren(w, "rm -f '" + w.karte("s/neu.txt") + "'\n"
                + "printf 'Neue Karte\\tParameter Foo unbekannt\\t2026-10-02\\n' >> \"$2\"", 0);

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(0, lauf.rc(), lauf.ausgabe());
        assertFalse(Files.exists(w.karte("s/neu.txt")), "die neue, unbaubare Karte ist weg");
        assertTrue(Files.readString(w.liste()).contains("Neue Karte"), "und ausgeschlossen");
        assertTrue(Files.readString(w.karte("s/sol_ring.txt")).contains("Oracle:neu"),
                "die heile Aenderung an einer anderen Karte kam an");
        String bericht = Files.readString(w.bericht());
        assertTrue(bericht.contains("Neu ausgeschlossen (1)"), bericht);
        assertFalse(bericht.contains("Bleibt auf unserer bisherigen Fassung"), bericht);
    }

    @Test
    void einAltEintragAufDerListeBleibtAuchWennUnsereAlteFassungZurueckkommt(@TempDir Path tmp) throws Exception {
        // Ein Eintrag, der VOR dem Lauf schon stand, ist Handarbeit und wird nicht gestrichen.
        Wurzel w = baueWurzel(tmp);
        Files.writeString(w.liste(), "Sol Ring\tvon Hand\t2026-01-01\n");
        upstream(w, "echo 'Oracle:neu' >> forge-gui/res/cardsfolder/s/sol_ring.txt");
        befunde(w, json(2), json(2, problem("Sol Ring", "Foo")), json(2));
        aussortieren(w, "rm -f '" + w.karte("s/sol_ring.txt") + "'", 0);

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(0, lauf.rc(), lauf.ausgabe());
        assertEquals(BASIS_SOL, Files.readString(w.karte("s/sol_ring.txt")));
        assertEquals("Sol Ring\tvon Hand\t2026-01-01\n", Files.readString(w.liste()));
    }

    // --- Befund 4: die Gegenprobe vergleicht Namen, nicht nur die Zahl --------------------------

    @Test
    void verschwindetEineKarteAberKommenZweiNeueDazuIstDieGegenprobeRot(@TempDir Path tmp) throws Exception {
        Wurzel w = baueWurzel(tmp);
        upstream(w, "rm forge-gui/res/cardsfolder/s/alt.txt;"
                + " echo 'Name:Neu Eins' > forge-gui/res/cardsfolder/s/n1.txt;"
                + " echo 'Name:Neu Zwei' > forge-gui/res/cardsfolder/s/n2.txt");
        befunde(w, json(2), json(3), json(3));   // die Zahl STEIGT: 2 -> 3

        Lauf lauf = laufVoll(w, "--ausschluss", w.liste().toString(), "upstream-test");

        assertEquals(1, lauf.rc(), "eine Karte fehlt - trotz steigender Zahl");
        assertTrue(lauf.ausgabe().contains("Alte Karte"), "die fehlende Karte wird genannt: " + lauf.ausgabe());
        assertTrue(Files.readString(w.bericht()).contains("`Alte Karte`"), "auch im Bericht");
        assertEquals("", gitAusgabe(w.forge(), "status", "--porcelain", "-uall"), "zurueckgenommen");
        assertTrue(Files.exists(w.karte("s/alt.txt")));
    }
}
