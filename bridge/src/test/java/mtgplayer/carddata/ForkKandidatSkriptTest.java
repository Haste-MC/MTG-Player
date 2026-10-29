package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prueft {@code scripts/fork-kandidat.sh} ohne Netz: ein erfundenes "upstream" mit zwei Release-Tags, ein
 * erfundener Fork mit unseren Commits auf dem alten Tag, und das Skript baut daraus den Kandidaten-Zweig.
 */
class ForkKandidatSkriptTest {

    private record Lauf(int rc, String ausgabe) { }

    /** wurzel = MTG-Player-Attrappe (mit forge/ als Klon des Forks), upstream und fork als eigene Repos. */
    private record Aufbau(Path wurzel, Path forge, Path upstream, Path fork) {
        Path bericht() {
            return wurzel.resolve("fork-kandidat-bericht.md");
        }
    }

    private static String git(Path repo, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "user.email=t@t", "-c", "user.name=T",
                "-c", "init.defaultBranch=master", "-c", "protocol.file.allow=always"));
        cmd.addAll(List.of(args));
        Files.createDirectories(repo);
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args) + "\n" + aus);
        return aus;
    }

    private static void schreibe(Path repo, String pfad, String inhalt) throws Exception {
        Path p = repo.resolve(pfad);
        Files.createDirectories(p.getParent());
        Files.writeString(p, inhalt);
    }

    private static String pom(String version) {
        return "<project>\n  <groupId>forge</groupId>\n  <artifactId>forge</artifactId>\n  <version>" + version
                + "</version>\n</project>\n";
    }

    private static String kindPom(String version) {
        return "<project>\n  <parent>\n    <artifactId>forge</artifactId>\n    <version>" + version
                + "</version>\n  </parent>\n  <artifactId>forge-ai</artifactId>\n</project>\n";
    }

    /** upstream: forge-1.0 und forge-1.1 (KI.java geaendert, Zeile "b" neu); Fork: unsere Commits auf forge-1.0. */
    private static Aufbau baue(Path tmp, boolean revisionsSchema) throws Exception {
        return baue(tmp, revisionsSchema, true);
    }

    private static Aufbau baue(Path tmp, boolean revisionsSchema, boolean kiKonflikt) throws Exception {
        Path up = tmp.resolve("upstream");
        git(up, "init", "-q");
        schreibe(up, "pom.xml", pom("1.0"));
        schreibe(up, "forge-ai/pom.xml", kindPom("1.0"));
        schreibe(up, "forge-ai/KI.java", "a\nb\nc\n");
        schreibe(up, "forge-ai/Copier.java", "x\ny\nz\n");
        schreibe(up, "forge-gui/res/cardsfolder/s/sol_ring.txt", "Name:Sol Ring\n");
        git(up, "add", "-A");
        git(up, "commit", "-q", "-m", "release 1.0");
        git(up, "tag", "forge-1.0");

        Path fork = tmp.resolve("fork");
        git(tmp, "clone", "-q", up.toString(), fork.toString());
        git(fork, "checkout", "-q", "-b", "mtg-player", "forge-1.0");
        schreibe(fork, "pom.xml", pom("1.0-mtgplayer"));
        schreibe(fork, "forge-ai/pom.xml", kindPom("1.0-mtgplayer"));
        git(fork, "commit", "-q", "-am", "mtg-player: version 1.0-mtgplayer (fork branch for MTG-Player)");
        schreibe(fork, "MODIFICATIONS.md", "Base: tag forge-1.0\n");
        git(fork, "add", "-A");
        git(fork, "commit", "-q", "-m", "Point the modification notice at the 1.0 base");
        schreibe(fork, "forge-ai/KI.java", "a\nB-unseres\nc\n");          // stoesst mit upstream zusammen
        git(fork, "commit", "-q", "-am", "KI: unsere Aenderung an b");
        schreibe(fork, "forge-ai/Copier.java", "x\ny\nz\nunser-copier\n"); // passt sauber
        git(fork, "commit", "-q", "-am", "Copier: unsere Ergaenzung");
        schreibe(fork, "forge-ai/Fix.java", "fix\n");                       // hat upstream schon
        git(fork, "add", "-A");
        git(fork, "commit", "-q", "-m", "Fix: den hat upstream auch");
        schreibe(fork, "forge-gui/res/cardsfolder/n/neu.txt", "Name:Neu\n");
        git(fork, "add", "-A");
        git(fork, "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
                "commit", "-q", "-m", "Sync card data from upstream master");

        // upstream geht weiter zu 1.1
        if (kiKonflikt) {
            schreibe(up, "forge-ai/KI.java", "a\nB-upstream\nc\n");
        }
        schreibe(up, "forge-ai/Fix.java", "fix\n");
        if (revisionsSchema) {
            schreibe(up, "pom.xml", "<project>\n  <groupId>forge</groupId>\n  <artifactId>forge</artifactId>\n"
                    + "  <version>${revision}</version>\n  <properties>\n    <versionCode>1.1</versionCode>\n"
                    + "    <snapshotName></snapshotName>\n    <revision>${versionCode}${snapshotName}</revision>\n"
                    + "  </properties>\n  <build><plugins><plugin>\n"
                    + "    <artifactId>flatten-maven-plugin</artifactId>\n    <executions>\n"
                    + "      <execution>\n        <id>flatten</id>\n        <phase>deploy</phase>\n      </execution>\n"
                    + "      <execution>\n        <id>flatten.clean</id>\n        <phase>clean</phase>\n      </execution>\n"
                    + "    </executions>\n  </plugin></plugins></build>\n</project>\n");
            schreibe(up, "forge-ai/pom.xml", kindPom("${revision}"));
        } else {
            schreibe(up, "pom.xml", pom("1.1"));
            schreibe(up, "forge-ai/pom.xml", kindPom("1.1"));
        }
        git(up, "add", "-A");
        git(up, "commit", "-q", "-m", "release 1.1");
        git(up, "tag", "forge-1.1");

        Path wurzel = tmp.resolve("wurzel");
        Files.createDirectories(wurzel.resolve("scripts"));
        Files.copy(Path.of("..", "scripts", "fork-kandidat.sh"), wurzel.resolve("scripts/fork-kandidat.sh"),
                StandardCopyOption.REPLACE_EXISTING);
        Path forge = wurzel.resolve("forge");
        git(wurzel, "clone", "-q", fork.toString(), forge.toString());
        return new Aufbau(wurzel, forge, up, fork);
    }

    private static Lauf lauf(Aufbau a, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("bash", "scripts/fork-kandidat.sh"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(a.wurzel().toFile()).redirectErrorStream(true);
        pb.environment().put("FORK_KANDIDAT_UPSTREAM_URL", a.upstream().toString());
        pb.environment().put("GIT_AUTHOR_NAME", "T");
        pb.environment().put("GIT_AUTHOR_EMAIL", "t@t");
        pb.environment().put("GIT_COMMITTER_NAME", "T");
        pb.environment().put("GIT_COMMITTER_EMAIL", "t@t");
        Process p = pb.start();
        String aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        System.out.println(aus);
        return new Lauf(rc, aus);
    }

    @Test
    void uebertraegtWasPasstUeberspringtKonflikteUndSetztDieVersion(@TempDir Path tmp) throws Exception {
        Aufbau a = baue(tmp, false);

        Lauf l = lauf(a, "forge-1.1");

        assertEquals(3, l.rc(), "ein Konflikt -> 3: " + l.ausgabe());
        assertEquals("mtg-player-1.1\n", git(a.forge(), "branch", "--show-current"));
        assertEquals("", git(a.forge(), "status", "--porcelain"), "kein halbes cherry-pick zurueckgelassen");
        assertTrue(Files.readString(a.forge().resolve("forge-ai/Copier.java")).contains("unser-copier"),
                "der saubere Commit ist drin");
        assertEquals("a\nB-upstream\nc\n", Files.readString(a.forge().resolve("forge-ai/KI.java")),
                "der Konflikt-Commit fehlt, upstreams Stand gilt");
        assertTrue(Files.readString(a.forge().resolve("pom.xml")).contains("<version>1.1-mtgplayer</version>"));
        assertTrue(Files.readString(a.forge().resolve("forge-ai/pom.xml")).contains("<version>1.1-mtgplayer</version>"));
        assertEquals("Base: tag forge-1.1\n", Files.readString(a.forge().resolve("MODIFICATIONS.md")));
        assertFalse(Files.exists(a.forge().resolve("forge-gui/res/cardsfolder/n/neu.txt")),
                "Kartendaten-Commit des Bots bleibt draussen");

        String bericht = Files.readString(a.bericht());
        assertTrue(bericht.contains("**Konflikt:** `forge-ai/KI.java`"), bericht);
        assertTrue(bericht.contains("leer – upstream enthält die Änderung schon"), bericht);
        assertTrue(bericht.contains("neu gesetzt: `1.1-mtgplayer`"), bericht);
        assertTrue(bericht.contains("1 Kartendaten-Commits ausgelassen"), bericht);
        assertTrue(bericht.contains("**1 mit Konflikt (übersprungen)**"), bericht);
        String log = git(a.forge(), "log", "--format=%s", "forge-1.1..HEAD");
        assertTrue(log.contains("mtg-player: version 1.1-mtgplayer"), log);
        assertTrue(log.contains("Point the modification notice at the 1.1 base"), log);
        assertFalse(log.contains("KI: unsere"), log);
        assertFalse(log.contains("den hat upstream auch"), "leerer Commit entsteht nicht: " + log);
    }

    @Test
    void imRevisionsSchemaWirdRevisionGesetzt(@TempDir Path tmp) throws Exception {
        Aufbau a = baue(tmp, true);

        Lauf l = lauf(a, "forge-1.1");

        assertEquals(3, l.rc(), l.ausgabe());
        String pom = Files.readString(a.forge().resolve("pom.xml"));
        assertTrue(pom.contains("<revision>${versionCode}-mtgplayer</revision>"), pom);
        assertTrue(pom.contains("<version>${revision}</version>"), "die Versionszeile bleibt: " + pom);
        assertTrue(pom.contains("<id>flatten</id>\n        <phase>process-resources</phase>"),
                "flatten laeuft vor install: " + pom);
        assertTrue(pom.contains("<id>flatten.clean</id>\n        <phase>clean</phase>"), pom);
        String bericht = Files.readString(a.bericht());
        assertTrue(bericht.contains("Maven-Version: `1.1-mtgplayer`"), bericht);
        assertTrue(bericht.contains("Build-Hinweis"), bericht);
        assertTrue(git(a.forge(), "log", "--format=%B", "forge-1.1..HEAD", "--grep=mtg-player: version")
                .contains("flatten-maven-plugin"), "der Versions-Commit nennt die Flatten-Aenderung");
    }

    @Test
    void ohneKonfliktEndetEsMit0(@TempDir Path tmp) throws Exception {
        Aufbau a = baue(tmp, false, false);   // upstream laesst KI.java in Ruhe

        Lauf l = lauf(a, "forge-1.1");

        assertEquals(0, l.rc(), l.ausgabe());
    }

    @Test
    void schonAufDemZielStehtWirdAbgelehnt(@TempDir Path tmp) throws Exception {
        Aufbau a = baue(tmp, false);

        Lauf l = lauf(a, "forge-1.0");

        assertEquals(1, l.rc(), l.ausgabe());
        assertTrue(l.ausgabe().contains("sitzt schon auf 1.0"), l.ausgabe());
    }
}
