# Kartendaten-Abgleich mit upstream – Umsetzungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Forges Kartendaten wöchentlich automatisch von upstream übernehmen, alles Nichtbaubare belegt aussortieren und das Ergebnis als Pull Request im Fork zur Freigabe vorlegen.

**Architecture:** Drei Schichten, jede für sich prüfbar. (1) Java in der Bridge: `CardFiles` ordnet Kartendateien ihren Namen/Set-Codes zu (ohne Forge), `CardDataCheck` fährt Forge hoch und baut jede Karte, `Exclusions` führt die Ausschlussliste. (2) Zwei Betriebsarten von `mtgplayer.Main` machen das von außen aufrufbar: `--kartendaten-pruefen` berichtet, `--kartendaten-aussortieren` entfernt. (3) `scripts/kartendaten-sync.sh` ruft beides in fester Reihenfolge, zwei GitHub-Abläufe sind nur die Klammer darum.

**Tech Stack:** Java 17, Forge 2.0.14-mtgplayer (`StaticData`, `CardFactory`), Jackson über `mtgplayer.protocol.Json`, JUnit 5, Bash, GitHub Actions, `gh` CLI.

**Spezifikation:** `docs/superpowers/specs/2026-10-02-kartendaten-abgleich-design.md` (§8 ist abgespalten und **nicht** Teil dieses Plans).

## Global Constraints

- Abgeglichen werden **genau drei** Verzeichnisse unter `forge/forge-gui/res/`: `cardsfolder`, `editions`, `tokenscripts`. Kein Java, keine POMs, nichts anderes.
- Kein Test schreibt nach `~/.mtg-player`. Jeder Test, der Forge startet, setzt `mtgplayer.data` auf ein eigenes Verzeichnis.
- Kein Test geht ins Netz. Der upstream-Abruf steckt ausschließlich im Skript.
- **Niemals `mvn clean`.** Vor jedem Maven-Lauf prüfen: `ps -eo args= | grep -c '[c]lassworlds'` muss `0` oder `1` sein.
- Commits im Hauptprojekt: Deutsch, klein, Präfixe `bridge:` / `test:` / `build:` / `docs:`, Abschluss genau `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Der Prüfmodus **urteilt nicht**: er berichtet und liefert immer Rückgabewert 0. Entschieden wird im Skript.
- **Kein halbes Ergebnis:** Bleibt nach dem Aussortieren ein Befund offen oder sinkt die Kartenzahl unter die Vergleichsmarke, entsteht kein Pull Request und der Ablauf schlägt fehl.
- Dateiformate: Kartenskript trägt `Name:<Kartenname>` als erste Zeile, Editionsdatei `Code=<SETCODE>` unter `[metadata]`.

---

### Task 1: `CardFiles` – Kartendateien ihren Namen zuordnen

Ohne diese Zuordnung kann niemand die Datei entfernen, hinter der eine durchgefallene Karte steckt. Bewusst **ohne Forge**: reines Dateilesen, in Millisekunden testbar.

**Files:**
- Create: `bridge/src/main/java/mtgplayer/carddata/CardFiles.java`
- Test: `bridge/src/test/java/mtgplayer/carddata/CardFilesTest.java`

**Interfaces:**
- Consumes: nichts.
- Produces:
  - `public static Map<String, List<Path>> karten(Path res) throws IOException` – Kartenname → Dateien unter `res/cardsfolder`
  - `public static Map<String, List<Path>> editionen(Path res) throws IOException` – Set-Code → Dateien unter `res/editions`
  - `public static List<String> doppelte(Map<String, List<Path>> index)` – Schlüssel mit mehr als einer Datei, alphabetisch
  - `public static List<Path> ohneSchluessel()` ist **nicht** vorgesehen; Dateien ohne `Name:`/`Code=` werden still übersprungen und zählen in `uebersprungen(Path res)` → `int`

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`bridge/src/test/java/mtgplayer/carddata/CardFilesTest.java`:

```java
package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
     * Nicht jede Datei unter cardsfolder/ ist ein Kartenskript. Sie darf nicht den Index verschmutzen,
     * aber auch nicht lautlos verschwinden - sonst merkt niemand, wenn der Parser danebenliegt.
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
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag sehen**

```bash
cd bridge && mvn test -Dtest=CardFilesTest -DfailIfNoSpecifiedTests=false
```

Erwartet: Übersetzungsfehler „cannot find symbol: class CardFiles".

- [ ] **Step 3: `CardFiles` schreiben**

`bridge/src/main/java/mtgplayer/carddata/CardFiles.java`:

```java
package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Ordnet Forges Kartendateien ihren Schluesseln zu: Kartenskripte unter {@code cardsfolder/} ihrem
 * Kartennamen, Editionsdateien unter {@code editions/} ihrem Set-Code.
 *
 * <p>Das ist die Haelfte des Abgleichs, die OHNE Forge auskommt - und die gebraucht wird, sobald eine
 * Karte durchfaellt: der Pruefmodus kennt dann ihren NAMEN, entfernt werden muss aber ihre DATEI.</p>
 *
 * <p>Dateien ohne den jeweiligen Schluessel werden uebersprungen (unter {@code cardsfolder/} liegt nicht
 * nur Kartenskript). Damit das nicht lautlos geschieht, zaehlt {@link #uebersprungen} sie.</p>
 */
public final class CardFiles {

    private CardFiles() { }

    public static Map<String, List<Path>> karten(Path res) throws IOException {
        return index(res.resolve("cardsfolder"), CardFiles::kartenname);
    }

    public static Map<String, List<Path>> editionen(Path res) throws IOException {
        return index(res.resolve("editions"), CardFiles::setCode);
    }

    /** Schluessel, die in mehr als einer Datei stehen - alphabetisch, damit Berichte vergleichbar sind. */
    public static List<String> doppelte(Map<String, List<Path>> index) {
        List<String> treffer = new ArrayList<>();
        index.forEach((schluessel, dateien) -> {
            if (dateien.size() > 1) {
                treffer.add(schluessel);
            }
        });
        treffer.sort(String::compareTo);
        return treffer;
    }

    /** Dateien unter {@code cardsfolder/}, die keine {@code Name:}-Zeile tragen. */
    public static int uebersprungen(Path res) throws IOException {
        Path dir = res.resolve("cardsfolder");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> dateien = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) dateien.filter(Files::isRegularFile)::iterator) {
                if (kartenname(p) == null) {
                    n++;
                }
            }
        }
        return n;
    }

    private interface Schluessel {
        String of(Path datei) throws IOException;
    }

    private static Map<String, List<Path>> index(Path dir, Schluessel schluessel) throws IOException {
        Map<String, List<Path>> index = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return index;
        }
        try (Stream<Path> dateien = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) dateien.filter(Files::isRegularFile)::iterator) {
                String s = schluessel.of(p);
                if (s != null) {
                    index.computeIfAbsent(s, k -> new ArrayList<>()).add(p);
                }
            }
        }
        index.values().forEach(l -> l.sort(Path::compareTo));
        return index;
    }

    /** @return der Wert der ERSTEN {@code Name:}-Zeile, oder {@code null}. */
    private static String kartenname(Path datei) throws IOException {
        return ersteZeileMit(datei, "Name:");
    }

    /** @return der Wert der ERSTEN {@code Code=}-Zeile, oder {@code null}. */
    private static String setCode(Path datei) throws IOException {
        return ersteZeileMit(datei, "Code=");
    }

    private static String ersteZeileMit(Path datei, String praefix) throws IOException {
        // Zeilenweise statt readString: cardsfolder/ hat ueber 30.000 Dateien, und gebraucht wird
        // immer nur die eine Zeile am Anfang.
        try (var zeilen = Files.lines(datei, StandardCharsets.UTF_8)) {
            return zeilen.filter(z -> z.startsWith(praefix))
                    .map(z -> z.substring(praefix.length()).trim())
                    .findFirst().orElse(null);
        } catch (java.io.UncheckedIOException kaputteKodierung) {
            // Eine Datei, die sich nicht als UTF-8 lesen laesst, ist kein Kartenskript dieser Sammlung.
            return null;
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg sehen**

```bash
cd bridge && mvn test -Dtest=CardFilesTest -DfailIfNoSpecifiedTests=false
```

Erwartet: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 5: Committen**

```bash
git add bridge/src/main/java/mtgplayer/carddata/CardFiles.java bridge/src/test/java/mtgplayer/carddata/CardFilesTest.java
git commit -m "bridge: kartendateien ihren namen und set-codes zuordnen"
```

---

### Task 2: `CardDataCheck` und die Betriebsart `--kartendaten-pruefen`

Das Stück, an dem alles hängt: Forge hochfahren und **jede** Karte wirklich bauen. Beim Abgleich 2026-09-25 hingen daran 37 Karten, die Forge 2.0.14 nicht bauen kann – sie hätten jede Partie beim Spielaufbau abgerissen.

**Files:**
- Create: `bridge/src/main/java/mtgplayer/carddata/CardDataCheck.java`
- Modify: `bridge/src/main/java/mtgplayer/Main.java` (neue Betriebsart neben `--trimmed-check`, dort bei Zeile 105 beginnend)
- Test: `bridge/src/test/java/mtgplayer/carddata/CardDataCheckTest.java`

**Interfaces:**
- Consumes: `CardFiles.karten(Path)`, `CardFiles.editionen(Path)`, `CardFiles.doppelte(Map)` aus Task 1.
- Produces:
  - `public record Problem(String karte, String grund)`
  - `public record Befund(int karten, List<Problem> nichtBaubar, List<String> nichtAuffindbar, List<String> doppelteSetCodes, List<String> doppelteNamen, List<String> parseMeldungen)` mit `public boolean sauber()`
  - `public static Befund pruefen(Path res, List<String> parseMeldungen)`
  - Betriebsart `--kartendaten-pruefen`, schreibt `KARTENDATEN_BEFUND <json>` auf stdout und beendet mit 0.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`bridge/src/test/java/mtgplayer/carddata/CardDataCheckTest.java`:

```java
package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import mtgplayer.proc.ChildJvm;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Laeuft im KINDPROZESS wie {@code TrimmedResTest}: der Pruefmodus faehrt Forge hoch, und Forges
 * statische Initialisierung laesst sich in derselben JVM kein zweites Mal auf ein anderes
 * {@code res}-Verzeichnis richten.
 */
class CardDataCheckTest {

    private static JsonNode befund(Path tmp, Path res) {
        String vorherAssets = System.getProperty("mtgplayer.assets");
        String vorherData = System.getProperty("mtgplayer.data");
        System.setProperty("mtgplayer.assets", res.toString());
        System.setProperty("mtgplayer.data", tmp.resolve("child-data").toString());
        try {
            Path stderr = tmp.resolve("pruefen-stderr.log");
            ChildJvm.Result r = ChildJvm.run(List.of("--kartendaten-pruefen"), "KARTENDATEN_BEFUND ",
                    stderr, line -> { }, 15, TimeUnit.MINUTES, null);
            if (!r.ok()) {
                throw new IllegalStateException("Kindprozess scheiterte: " + ChildJvm.failureLine(stderr));
            }
            return Json.parse(r.value());
        } finally {
            setze("mtgplayer.assets", vorherAssets);
            setze("mtgplayer.data", vorherData);
        }
    }

    private static void setze(String name, String wert) {
        if (wert == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, wert);
        }
    }

    /**
     * Unsere heutigen Kartendaten duerfen nichts enthalten, was nicht auch auf der Ausschlussliste
     * steht. Bewusst NICHT "null Befunde": ob unter den 33.000 Karten von 2.0.14 schon vor diesem Plan
     * welche stehen, die sich nicht bauen lassen, hat nie jemand gemessen - der erste Lauf stellt das
     * fest, und sein Ergebnis wird der Ausgangszustand der Liste (siehe Step 6). Ab dann ist dieser
     * Test das Netz: taucht etwas NEUES auf, faellt er.
     */
    @Test
    void jederBefundStehtAufDerAusschlussliste(@TempDir Path tmp) throws Exception {
        JsonNode b = befund(tmp, Path.of("assets").toAbsolutePath());

        assertTrue(b.get("karten").asInt() > 30000, "Kartenzahl: " + b.get("karten"));
        List<String> bekannt = ausschlussliste();
        for (String feld : List.of("nichtAuffindbar", "doppelteSetCodes", "doppelteNamen")) {
            b.get(feld).forEach(n -> assertTrue(bekannt.contains(n.asText()),
                    feld + " enthaelt " + n.asText() + ", was nicht auf der Ausschlussliste steht"));
        }
        b.get("nichtBaubar").forEach(p -> assertTrue(bekannt.contains(p.get("karte").asText()),
                "nicht baubar: " + p.get("karte").asText() + " steht nicht auf der Ausschlussliste"));
    }

    /** Eigener Leser statt {@code Exclusions}: ein Test soll nicht mit demselben Werkzeug pruefen, das
     *  die Datei schreibt. */
    private static List<String> ausschlussliste() throws Exception {
        Path datei = Path.of("..", "docs", "kartendaten-ausgeschlossen.txt");
        if (!Files.isRegularFile(datei)) {
            return List.of();
        }
        return Files.readAllLines(datei).stream()
                .filter(z -> !z.isBlank() && !z.startsWith("#"))
                .map(z -> z.split("\t", 2)[0])
                .toList();
    }

    /**
     * Ohne diesen Test beweist der erste nichts: er waere auch gruen, wenn der Modus gar nichts prueft.
     * Das Kartenskript hier ist absichtlich unlesbar fuer Forge (unbekannte Zeilen) - es traegt einen
     * Namen, landet aber nicht in der Datenbank.
     */
    @Test
    void einUnlesbaresKartenskriptWirdGemeldet(@TempDir Path tmp) throws Exception {
        Path res = tmp.resolve("res");
        kopiereRes(Path.of("assets").toAbsolutePath(), res);
        Files.writeString(res.resolve("cardsfolder").resolve("z").resolve("zzz_kaputt.txt"),
                "Name:Zzz Kaputte Testkarte\nUnbekanntesSchluesselwort:Ja\nOracle:\n");
        Files.writeString(res.resolve("editions").resolve("ZZZ Doppelt.txt"),
                "[metadata]\nCode=LEA\nDate=2027-01-01\nName=ZZZ Doppelt\n\n[cards]\n");

        JsonNode b = befund(tmp, res.getParent());

        assertTrue(b.get("nichtAuffindbar").toString().contains("Zzz Kaputte Testkarte"),
                "die unlesbare Karte steht im Befund: " + b.get("nichtAuffindbar"));
        assertTrue(b.get("doppelteSetCodes").toString().contains("LEA"),
                "der doppelte Set-Code steht im Befund: " + b.get("doppelteSetCodes"));
    }

    /** Symlink statt Kopie fuer cardsfolder waere schneller, aber der Test MUSS hineinschreiben. */
    private static void kopiereRes(Path quelleAssets, Path zielRes) throws Exception {
        Path quelle = quelleAssets.resolve("res");
        try (var pfade = Files.walk(quelle)) {
            for (Path p : (Iterable<Path>) pfade::iterator) {
                Path ziel = zielRes.resolve(quelle.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(ziel);
                } else {
                    Files.createDirectories(ziel.getParent());
                    Files.copy(p, ziel);
                }
            }
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag sehen**

```bash
cd bridge && mvn test -Dtest=CardDataCheckTest -DfailIfNoSpecifiedTests=false
```

Erwartet: Übersetzungsfehler „cannot find symbol: class CardDataCheck" bzw. – nach dem Übersetzen – ein Kindprozess, der `--kartendaten-pruefen` nicht kennt.

- [ ] **Step 3: `CardDataCheck` schreiben**

`bridge/src/main/java/mtgplayer/carddata/CardDataCheck.java`:

```java
package mtgplayer.carddata;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
import forge.game.Match;
import forge.game.card.CardFactory;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import mtgplayer.ai.AiConfig;
import mtgplayer.match.CommanderRules;

/**
 * Stellt fest, ob Forge mit den vorliegenden Kartendaten arbeiten kann - die Pruefung hinter dem
 * woechentlichen Abgleich mit upstream (siehe {@code scripts/kartendaten-sync.sh}).
 *
 * <p>Die Klasse URTEILT NICHT: sie berichtet, was sie findet, und ueberlaesst jede Folgerung dem
 * Aufrufer. So bleibt sie auch von Hand brauchbar, wenn man nur wissen will, wie es gerade steht.</p>
 *
 * <p>Der wichtigste Posten ist {@link Befund#nichtBaubar}: eine Karte kann in der Datenbank stehen und
 * trotzdem nicht ENTSTEHEN, wenn ihr Skript ein Schluesselwort benutzt, das unsere Forge-Fassung noch
 * nicht kennt. Beim Abgleich am 2026-09-25 waren das 37 von 394 Karten ({@code Empower} fehlt in
 * {@code ApiType}, {@code FlippedCoinOnce} in {@code TriggerType}, {@code CantBeBeamedUp} und
 * {@code IgnorePlaneswalkerZeroLoyaltyRule} in {@code StaticAbilityMode}). Im Spiel reisst so eine
 * Karte den Spielaufbau ab, sobald sie in einem Deck liegt - erkennbar ist das nur, indem man sie
 * baut.</p>
 */
public final class CardDataCheck {

    public record Problem(String karte, String grund) { }

    public record Befund(int karten,
                         List<Problem> nichtBaubar,
                         List<String> nichtAuffindbar,
                         List<String> doppelteSetCodes,
                         List<String> doppelteNamen,
                         List<String> parseMeldungen) {

        /** Parse-Meldungen zaehlen NICHT: sie sind Beiwerk fuer den Bericht, kein Mangel. */
        public boolean sauber() {
            return nichtBaubar.isEmpty() && nichtAuffindbar.isEmpty()
                    && doppelteSetCodes.isEmpty() && doppelteNamen.isEmpty();
        }
    }

    private CardDataCheck() { }

    /**
     * @param res            Forges {@code res}-Verzeichnis, aus dem die geladenen Daten stammen
     * @param parseMeldungen was Forge beim Laden gemeldet hat (siehe {@code Main})
     */
    public static Befund pruefen(Path res, List<String> parseMeldungen) {
        StaticData sd = StaticData.instance();
        List<PaperCard> alle = new ArrayList<>(sd.getCommonCards().getUniqueCards());
        alle.addAll(sd.getVariantCards().getUniqueCards());

        Set<String> inDerDatenbank = new TreeSet<>();
        alle.forEach(pc -> inDerDatenbank.add(pc.getName()));

        Map<String, List<Path>> kartendateien;
        Map<String, List<Path>> editionsdateien;
        try {
            kartendateien = CardFiles.karten(res);
            editionsdateien = CardFiles.editionen(res);
        } catch (IOException e) {
            throw new IllegalStateException("kann die Kartendateien unter " + res + " nicht lesen", e);
        }

        List<String> nichtAuffindbar = new ArrayList<>();
        kartendateien.keySet().forEach(name -> {
            if (!inDerDatenbank.contains(name)) {
                nichtAuffindbar.add(name);
            }
        });

        return new Befund(sd.getCommonCards().getUniqueCards().size(),
                nichtBaubar(alle),
                nichtAuffindbar,
                CardFiles.doppelte(editionsdateien),
                CardFiles.doppelte(kartendateien),
                parseMeldungen);
    }

    /** Baut jede Karte im Spielkontext - genau das, was beim Deckaufbau einer echten Partie geschieht. */
    private static List<Problem> nichtBaubar(List<PaperCard> alle) {
        Player besitzer = leeresSpiel().getPlayers().get(0);
        List<Problem> probleme = new ArrayList<>();
        for (PaperCard pc : alle) {
            try {
                CardFactory.getCard(pc, besitzer, besitzer.getGame());
            } catch (RuntimeException | LinkageError e) {
                probleme.add(new Problem(pc.getName(), e.toString()));
            }
        }
        return probleme;
    }

    /**
     * Zwei Sitze mit leeren Decks, Commander-Regeln wie in einer echten Partie (siehe
     * {@link CommanderRules}) - mehr braucht {@link CardFactory#getCard} nicht.
     */
    private static Game leeresSpiel() {
        // AiConfig.DEFAULT statt AiConfig.parse(...): parse() prueft das Profil ueber Forges
        // AiProfileUtil, DEFAULT ist die fertige Instanz (STANDARD/"Default") und braucht das nicht.
        List<RegisteredPlayer> sitze = List.of(
                new RegisteredPlayer(new Deck()).setPlayer(AiConfig.DEFAULT.newLobbyPlayer("Pruefung 1")),
                new RegisteredPlayer(new Deck()).setPlayer(AiConfig.DEFAULT.newLobbyPlayer("Pruefung 2")));
        GameRules regeln = CommanderRules.create();
        Game spiel = new Game(sitze, regeln, new Match(regeln, sitze, "Kartendaten"));
        spiel.setAge(GameStage.Play);
        return spiel;
    }
}
```

- [ ] **Step 4: Die Betriebsart in `Main` ergänzen**

In `bridge/src/main/java/mtgplayer/Main.java` direkt **nach** dem `--trimmed-check`-Block einfügen:

```java
            if (modus.equals("--kartendaten-pruefen")) {
                // Vom woechentlichen Abgleich gerufen (scripts/kartendaten-sync.sh) und von
                // CardDataCheckTest. Die Parse-Meldungen koennen nur WAEHREND des Ladens eingesammelt
                // werden - danach sind sie durch, deshalb haengt sich der Modus vorher an System.out.
                List<String> meldungen = new ArrayList<>();
                PrintStream echt = System.out;
                System.setOut(new PrintStream(new OutputStream() {
                    private final StringBuilder zeile = new StringBuilder();
                    @Override public void write(int b) {
                        if (b == '\n') {
                            meldungen.add(zeile.toString());
                            zeile.setLength(0);
                        } else if (b != '\r') {
                            zeile.append((char) b);
                        }
                    }
                }, true));
                try {
                    ForgeBoot.init();
                } finally {
                    System.setOut(echt);
                }
                CardDataCheck.Befund befund = CardDataCheck.pruefen(
                        ForgeBoot.assetsDir().resolve("res"), meldungen);
                System.out.println("KARTENDATEN_BEFUND " + Json.toJson(befund));
                System.exit(0);
                return;
            }
```

Dazu die Importe `java.io.OutputStream`, `java.io.PrintStream` und `mtgplayer.carddata.CardDataCheck`
ergänzen (`java.util.ArrayList`, `java.util.List` und `mtgplayer.protocol.Json` sind schon vorhanden).

- [ ] **Step 5: Tests laufen lassen und Erfolg sehen**

```bash
cd bridge && mvn test -Dtest=CardDataCheckTest -DfailIfNoSpecifiedTests=false
```

Erwartet: `Tests run: 2, Failures: 0, Errors: 0`. Der erste Test dauert mehrere Minuten (33.000 Karten
werden gebaut); der zweite kopiert zusätzlich das `res`-Verzeichnis.

**Schlägt der erste Test fehl, ist das kein Fehler im Code**, sondern eine Messung: unsere heutigen
Daten enthalten dann bereits Karten, die 2.0.14 nicht bauen kann. Weiter mit Step 6.

- [ ] **Step 6: Ausgangszustand der Ausschlussliste herstellen**

Den Befund aus Step 5 nehmen und jeden gemeldeten Schlüssel in `docs/kartendaten-ausgeschlossen.txt`
eintragen — eine Zeile je Eintrag, `Schlüssel⇥Grund⇥2026-10-02`, alphabetisch, unter diesem Kopf:

```
# Karten und Set-Codes, die unsere Forge-Fassung nicht bauen kann.
# Erzeugt von scripts/kartendaten-sync.sh - von Hand aendern ist erlaubt, aber unnoetig.
# Spalten (Tabulator): Schluessel, Grund, seit wann.
```

Meldet der Befund nichts, bleibt die Datei bei diesem Kopf. Danach Step 5 wiederholen; jetzt muss der
Test grün sein. Die Zahl der Einträge gehört in die Commit-Nachricht — sie ist das erste Mal, dass
jemand diese Zahl kennt.

- [ ] **Step 7: Committen**

```bash
git add bridge/src/main/java/mtgplayer/carddata/CardDataCheck.java bridge/src/main/java/mtgplayer/Main.java bridge/src/test/java/mtgplayer/carddata/CardDataCheckTest.java docs/kartendaten-ausgeschlossen.txt
git commit -m "bridge: pruefmodus fuer kartendaten, der jede karte wirklich baut"
```

Die Commit-Nachricht nennt die in Step 6 gemessene Zahl: wie viele unserer heutigen Karten 2.0.14 nicht
bauen kann.

---

### Task 3: Ausschlussliste und die Betriebsart `--kartendaten-aussortieren`

Ohne Ausschlussliste käme jede Woche derselbe Kartensatz herein und flöge wieder heraus – der Bericht wäre jedes Mal gleich laut und niemand läse ihn mehr.

**Files:**
- Create: `bridge/src/main/java/mtgplayer/carddata/Exclusions.java`
- Create: `bridge/src/main/java/mtgplayer/carddata/CardDataSweep.java`
- Modify: `bridge/src/main/java/mtgplayer/Main.java` (Betriebsart nach `--kartendaten-pruefen`)
- Modify: `docs/kartendaten-ausgeschlossen.txt` (in Task 2 angelegt; hier nur noch maschinell fortgeschrieben)
- Test: `bridge/src/test/java/mtgplayer/carddata/ExclusionsTest.java`

**Interfaces:**
- Consumes: `CardFiles.karten(Path)`, `CardFiles.editionen(Path)`, `CardDataCheck.Befund` (als JSON).
- Produces:
  - `public record Eintrag(String schluessel, String grund, String seit)`
  - `public static List<Eintrag> lesen(Path datei) throws IOException`
  - `public static void schreiben(Path datei, List<Eintrag> eintraege) throws IOException`
  - `public static List<Eintrag> ergaenzen(List<Eintrag> bestand, CardDataCheck.Befund befund, String heute)`
  - `public static int CardDataSweep.aussortieren(CardDataCheck.Befund befund, Path res, Path neueDateien) throws IOException`
  - Betriebsart `--kartendaten-aussortieren <befund.json> <ausschlussliste.txt> <neue-dateien.txt>`, schreibt `KARTENDATEN_AUSSORTIERT <anzahl>` und beendet mit 0.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`bridge/src/test/java/mtgplayer/carddata/ExclusionsTest.java`:

```java
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
        List<Exclusions.Eintrag> eintraege = List.of(
                new Exclusions.Eintrag("Dreamdrinker Vampire", "Empower fehlt in ApiType", "2026-09-25"),
                new Exclusions.Eintrag("Coin Flipper", "FlippedCoinOnce fehlt in TriggerType", "2026-10-02"));

        Exclusions.schreiben(datei, eintraege);

        assertEquals(eintraege, Exclusions.lesen(datei));
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
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag sehen**

```bash
cd bridge && mvn test -Dtest=ExclusionsTest -DfailIfNoSpecifiedTests=false
```

Erwartet: Übersetzungsfehler „cannot find symbol: class Exclusions".

- [ ] **Step 3: `Exclusions` schreiben**

`bridge/src/main/java/mtgplayer/carddata/Exclusions.java`:

```java
package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Liste der Kartennamen und Set-Codes, die unsere Forge-Fassung nicht verkraftet
 * ({@code docs/kartendaten-ausgeschlossen.txt}).
 *
 * <p>Sie hat genau einen Zweck: der woechentliche Abgleich soll ruhig sein. Ohne sie wuerde upstream
 * jede Woche dieselben Karten hereinreichen, die Pruefung sie jede Woche wieder aussortieren, und der
 * Bericht waere jedes Mal gleich laut - bis ihn niemand mehr liest. Mit ihr nennt der Bericht nur, was
 * NEU dazugekommen ist.</p>
 *
 * <p>Sie gilt fuer unsere Engine-Fassung. Beim Wechsel auf ein neueres Forge wird sie geleert und neu
 * aufgebaut - ihre Laenge ist dann zugleich die Antwort auf „was bekomme ich dadurch zurueck?".</p>
 *
 * <p>Format: eine Zeile je Eintrag, {@code Schluessel\tGrund\tDatum}, alphabetisch. Zeilen, die mit
 * {@code #} beginnen, und Leerzeilen sind Erklaerung fuer den Menschen.</p>
 */
public final class Exclusions {

    public record Eintrag(String schluessel, String grund, String seit) { }

    private static final String KOPF = """
            # Karten und Set-Codes, die unsere Forge-Fassung nicht bauen kann.
            # Erzeugt von scripts/kartendaten-sync.sh - von Hand aendern ist erlaubt, aber unnoetig.
            # Spalten (Tabulator): Schluessel, Grund, seit wann.
            """;

    private Exclusions() { }

    public static List<Eintrag> lesen(Path datei) throws IOException {
        List<Eintrag> eintraege = new ArrayList<>();
        if (!Files.isRegularFile(datei)) {
            return eintraege;
        }
        for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
            if (zeile.isBlank() || zeile.startsWith("#")) {
                continue;
            }
            String[] teile = zeile.split("\t", 3);
            if (teile.length == 3) {
                eintraege.add(new Eintrag(teile[0], teile[1], teile[2]));
            }
        }
        return eintraege;
    }

    public static void schreiben(Path datei, List<Eintrag> eintraege) throws IOException {
        StringBuilder sb = new StringBuilder(KOPF);
        eintraege.stream()
                .sorted((a, b) -> a.schluessel().compareTo(b.schluessel()))
                .forEach(e -> sb.append(e.schluessel()).append('\t')
                        .append(e.grund()).append('\t').append(e.seit()).append('\n'));
        Path eltern = datei.getParent();
        if (eltern != null) {
            Files.createDirectories(eltern);
        }
        Files.writeString(datei, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * @return der Bestand plus jeden Befund, der noch nicht darin steht. Ein bestehender Eintrag behaelt
     *         Grund UND Datum - sonst waere jede Woche jedes Datum neu und die Liste erzaehlte nichts
     *         mehr darueber, seit wann eine Karte fehlt.
     */
    public static List<Eintrag> ergaenzen(List<Eintrag> bestand, CardDataCheck.Befund befund, String heute) {
        Map<String, Eintrag> nachSchluessel = new LinkedHashMap<>();
        bestand.forEach(e -> nachSchluessel.put(e.schluessel(), e));

        befund.nichtBaubar().forEach(p -> nachSchluessel.putIfAbsent(p.karte(),
                new Eintrag(p.karte(), p.grund(), heute)));
        befund.nichtAuffindbar().forEach(name -> nachSchluessel.putIfAbsent(name,
                new Eintrag(name, "Kartenskript von dieser Forge-Fassung nicht lesbar", heute)));
        befund.doppelteNamen().forEach(name -> nachSchluessel.putIfAbsent(name,
                new Eintrag(name, "Kartenname in mehr als einer Datei", heute)));
        befund.doppelteSetCodes().forEach(code -> nachSchluessel.putIfAbsent(code,
                new Eintrag(code, "Set-Code in mehr als einer Editionsdatei", heute)));

        return new ArrayList<>(nachSchluessel.values());
    }
}
```

- [ ] **Step 4: Test laufen lassen und Erfolg sehen**

```bash
cd bridge && mvn test -Dtest=ExclusionsTest -DfailIfNoSpecifiedTests=false
```

Erwartet: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Die Betriebsart `--kartendaten-aussortieren` in `Main` ergänzen**

Direkt **nach** dem `--kartendaten-pruefen`-Block einfügen:

```java
            if (modus.equals("--kartendaten-aussortieren")) {
                // Entfernt die Dateien hinter einem Befund und schreibt die Ausschlussliste fort.
                // Getrennt vom Pruefmodus, weil der nicht urteilen soll (Spezifikation 3).
                // args: <befund.json> <ausschlussliste.txt> <neue-dateien.txt>
                Path befundDatei = Paths.get(args[1]);
                Path listenDatei = Paths.get(args[2]);
                Path neueDateien = Paths.get(args[3]);
                CardDataCheck.Befund befund = Json.mapper()
                        .readValue(Files.readString(befundDatei), CardDataCheck.Befund.class);
                Path res = ForgeBoot.assetsDir().resolve("res");
                int entfernt = CardDataSweep.aussortieren(befund, res, neueDateien);
                Exclusions.schreiben(listenDatei,
                        Exclusions.ergaenzen(Exclusions.lesen(listenDatei), befund,
                                java.time.LocalDate.now().toString()));
                System.out.println("KARTENDATEN_AUSSORTIERT " + entfernt);
                System.exit(0);
                return;
            }
```

Dazu in `Main.java` die Importe `java.nio.file.Files`, `mtgplayer.carddata.CardDataSweep` und
`mtgplayer.carddata.Exclusions` ergänzen (`java.nio.file.Paths`, `java.nio.file.Path` und
`mtgplayer.protocol.Json` sind vorhanden). `CardDataSweep` entsteht im nächsten Step — bis dahin
übersetzt dieser Block nicht.

- [ ] **Step 6: `CardDataSweep` schreiben**

`bridge/src/main/java/mtgplayer/carddata/CardDataSweep.java`:

```java
package mtgplayer.carddata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entfernt die Dateien hinter einem {@link CardDataCheck.Befund}.
 *
 * <p>Bei einer DUBLETTE darf nicht irgendeine der beiden Dateien fallen, sonst verschwindet unter
 * Umstaenden die Karte, die vorher da war. Deshalb bekommt diese Klasse die Liste der Dateien, die der
 * Abgleich gerade NEU hereingebracht hat (aus {@code git status} im Submodul): von einer Dublette faellt
 * die neue Datei, die bestehende bleibt. Steht keine der Dateien auf der Liste, faellt keine - dann ist
 * die Dublette aelter als dieser Abgleich und gehoert von Hand angesehen.</p>
 */
public final class CardDataSweep {

    private CardDataSweep() { }

    public static int aussortieren(CardDataCheck.Befund befund, Path res, Path neueDateien)
            throws IOException {
        Set<String> neu = Set.copyOf(Files.isRegularFile(neueDateien)
                ? Files.readAllLines(neueDateien, StandardCharsets.UTF_8)
                : List.of());
        Map<String, List<Path>> karten = CardFiles.karten(res);
        Map<String, List<Path>> editionen = CardFiles.editionen(res);

        List<Path> zuLoeschen = new ArrayList<>();
        befund.nichtBaubar().forEach(p -> zuLoeschen.addAll(karten.getOrDefault(p.karte(), List.of())));
        befund.nichtAuffindbar().forEach(n -> zuLoeschen.addAll(karten.getOrDefault(n, List.of())));
        befund.doppelteNamen().forEach(n -> zuLoeschen.addAll(nurNeue(karten.get(n), res, neu)));
        befund.doppelteSetCodes().forEach(c -> zuLoeschen.addAll(nurNeue(editionen.get(c), res, neu)));

        int entfernt = 0;
        for (Path p : zuLoeschen) {
            if (Files.deleteIfExists(p)) {
                entfernt++;
            }
        }
        return entfernt;
    }

    private static List<Path> nurNeue(List<Path> dateien, Path res, Set<String> neu) {
        if (dateien == null) {
            return List.of();
        }
        List<Path> treffer = new ArrayList<>();
        for (Path p : dateien) {
            // Die Liste aus "git status" nennt Pfade relativ zum Submodul-Wurzelverzeichnis.
            String relativ = "forge-gui/res/" + res.relativize(p).toString().replace('\\', '/');
            if (neu.contains(relativ)) {
                treffer.add(p);
            }
        }
        return treffer;
    }
}
```

- [ ] **Step 7: Alles übersetzen und die bisherigen Tests laufen lassen**

```bash
cd bridge && mvn test -Dtest='CardFilesTest,ExclusionsTest' -DfailIfNoSpecifiedTests=false
```

Erwartet: `Tests run: 12, Failures: 0, Errors: 0`.

- [ ] **Step 8: Committen**

```bash
git add bridge/src/main/java/mtgplayer/carddata/Exclusions.java bridge/src/main/java/mtgplayer/carddata/CardDataSweep.java bridge/src/main/java/mtgplayer/Main.java bridge/src/test/java/mtgplayer/carddata/ExclusionsTest.java
git commit -m "bridge: ausschlussliste und aussortieren der durchgefallenen kartendateien"
```

---

### Task 4: Das Abgleich-Skript

**Files:**
- Create: `scripts/kartendaten-sync.sh`
- Test: `bridge/src/test/java/mtgplayer/carddata/SyncSkriptTest.java`

**Interfaces:**
- Consumes: die Betriebsarten `--kartendaten-pruefen` und `--kartendaten-aussortieren` aus Task 2 und 3.
- Produces: `scripts/kartendaten-sync.sh [<upstream-ref>]` (Vorgabe `upstream/master`), legt `kartendaten-bericht.md` und `kartendaten-befund.json` im Arbeitsverzeichnis ab, Rückgabewert 0 = Pull Request darf entstehen, 1 = Fehlschlag, 2 = nichts Neues.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

`bridge/src/test/java/mtgplayer/carddata/SyncSkriptTest.java` – prüft die **Dateiauswahl** des Skripts
ohne Forge und ohne Netz, gegen ein erfundenes upstream:

```java
package mtgplayer.carddata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prueft NUR den Uebernahme-Schritt des Skripts ({@code --nur-uebernehmen}): aus einem erfundenen
 * upstream-Stand muss genau das Richtige im Arbeitsbaum landen - neue Dateien, geaenderte Dateien,
 * und Geloeschtes muss verschwinden. Ohne Forge, ohne Netz.
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
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag sehen**

```bash
cd bridge && mvn test -Dtest=SyncSkriptTest -DfailIfNoSpecifiedTests=false
```

Erwartet: Fehlschlag, weil `scripts/kartendaten-sync.sh` nicht existiert.

- [ ] **Step 3: Das Skript schreiben**

`scripts/kartendaten-sync.sh`:

```bash
#!/usr/bin/env bash
# Gleicht Forges Kartendaten mit upstream ab und belegt, dass unsere Engine damit arbeiten kann.
#
# Aufruf im Wurzelverzeichnis von MTG-Player (oder, fuer --nur-uebernehmen, in einem Repo mit
# derselben Verzeichnisstruktur):
#   scripts/kartendaten-sync.sh [<upstream-ref>]
#   scripts/kartendaten-sync.sh --nur-uebernehmen <ref> [--ausschluss <datei>]
#
# Rueckgabewerte: 0 = Ergebnis liegt vor, 1 = Fehlschlag, 2 = nichts Neues bei upstream.
set -euo pipefail

VERZEICHNISSE=(forge-gui/res/cardsfolder forge-gui/res/editions forge-gui/res/tokenscripts)
AUSSCHLUSS="docs/kartendaten-ausgeschlossen.txt"
NUR_UEBERNEHMEN=0
REF="upstream/master"

while [ $# -gt 0 ]; do
  case "$1" in
    --nur-uebernehmen) NUR_UEBERNEHMEN=1; shift ;;
    --ausschluss) AUSSCHLUSS="$2"; shift 2 ;;
    *) REF="$1"; shift ;;
  esac
done

# --- Uebernehmen -----------------------------------------------------------------------------
# "git checkout <ref> -- <pfade>" holt neue und geaenderte Dateien, aber KEINE Loeschungen: eine
# Datei, die upstream nicht mehr hat, bliebe stehen. Deshalb vorher raeumen und danach alles aus
# <ref> einspielen - so entsteht genau upstreams Stand.
uebernehmen() {
  local repo_wurzel="$1"
  ( cd "$repo_wurzel"
    for d in "${VERZEICHNISSE[@]}"; do
      [ -d "$d" ] && git rm -r -q --cached "$d" >/dev/null 2>&1 || true
      rm -rf "$d"
    done
    git checkout "$REF" -- "${VERZEICHNISSE[@]}"

    # Ausgeschlossene Karten gar nicht erst hereinlassen (erste Spalte der Liste).
    if [ -f "$AUSSCHLUSS" ]; then
      while IFS=$'\t' read -r schluessel _rest; do
        case "$schluessel" in ''|'#'*) continue ;; esac
        grep -rl --fixed-strings --line-regexp "Name:$schluessel" forge-gui/res/cardsfolder 2>/dev/null \
          | while read -r treffer; do rm -f "$treffer"; done
      done < "$AUSSCHLUSS"
    fi
  )
}

if [ "$NUR_UEBERNEHMEN" = 1 ]; then
  uebernehmen "$PWD"
  exit 0
fi

# --- Voller Durchgang ------------------------------------------------------------------------
cd "$(dirname "$0")/.."
WURZEL="$PWD"
BEFUND_VORHER="$WURZEL/kartendaten-befund-vorher.json"
BEFUND="$WURZEL/kartendaten-befund.json"
BERICHT="$WURZEL/kartendaten-bericht.md"
NEUE_DATEIEN="$WURZEL/kartendaten-neu.txt"

pruefen() {   # $1 = Zieldatei
  ( cd bridge
    mvn -q -DskipTests package >/dev/null
    java -Xmx4g -Dmtgplayer.data="$WURZEL/target/kartendaten-data" \
         -cp "target/classes:$(cat "$WURZEL/target/cp.txt")" \
         mtgplayer.Main --kartendaten-pruefen ) \
    | sed -n 's/^KARTENDATEN_BEFUND //p' > "$1"
  [ -s "$1" ] || { echo "Pruefmodus lieferte keinen Befund" >&2; exit 1; }
}

mvn -q -f bridge/pom.xml dependency:build-classpath -Dmdep.outputFile="$WURZEL/target/cp.txt" >/dev/null

echo "== 1. Vergleichsmarke =="
pruefen "$BEFUND_VORHER"
KARTEN_VORHER=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['karten'])" "$BEFUND_VORHER")
echo "Karten vorher: $KARTEN_VORHER"

echo "== 2. Uebernehmen von $REF =="
git -C forge fetch --quiet upstream
uebernehmen "$WURZEL/forge"
git -C forge status --porcelain -- "${VERZEICHNISSE[@]}" | sed 's/^...//' > "$NEUE_DATEIEN"
if [ ! -s "$NEUE_DATEIEN" ]; then
  echo "nichts Neues bei upstream"
  exit 2
fi

echo "== 3. Pruefen und aussortieren =="
pruefen "$BEFUND"
( cd bridge && java -Xmx4g -Dmtgplayer.data="$WURZEL/target/kartendaten-data" \
    -cp "target/classes:$(cat "$WURZEL/target/cp.txt")" \
    mtgplayer.Main --kartendaten-aussortieren "$BEFUND" "$WURZEL/$AUSSCHLUSS" "$NEUE_DATEIEN" )

echo "== 4. Gegenprobe =="
pruefen "$BEFUND"
python3 - "$BEFUND" "$BEFUND_VORHER" "$BERICHT" <<'PY'
import json, sys
befund, vorher, bericht = (json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3])
offen = (befund["nichtBaubar"] or befund["nichtAuffindbar"]
         or befund["doppelteSetCodes"] or befund["doppelteNamen"])
with open(bericht, "w") as f:
    f.write(f"## Kartendaten-Abgleich\n\nKarten: {vorher['karten']} -> {befund['karten']}\n\n")
    if offen:
        f.write("**Befund nach dem Aussortieren noch offen:**\n\n```\n"
                + json.dumps(befund, indent=1, ensure_ascii=False) + "\n```\n")
if offen:
    sys.exit(1)
if befund["karten"] < vorher["karten"]:
    print(f"Kartenzahl gefallen: {vorher['karten']} -> {befund['karten']}", file=sys.stderr)
    sys.exit(1)
PY

echo "== 5. Abschliessende Partie =="
( cd bridge && java -Xmx4g -Dmtgplayer.data="$WURZEL/target/kartendaten-data" \
    -cp "target/classes:$(cat "$WURZEL/target/cp.txt")" \
    mtgplayer.Main --trimmed-check >/dev/null )

echo "fertig: $BERICHT"
```

- [ ] **Step 4: Ausführbar machen und Test laufen lassen**

```bash
chmod +x scripts/kartendaten-sync.sh && cd bridge && mvn test -Dtest=SyncSkriptTest -DfailIfNoSpecifiedTests=false
```

Erwartet: `Tests run: 2, Failures: 0, Errors: 0`.

- [ ] **Step 5: Committen**

```bash
git add scripts/kartendaten-sync.sh bridge/src/test/java/mtgplayer/carddata/SyncSkriptTest.java
git commit -m "build: skript fuer den kartendaten-abgleich mit upstream"
```

---

### Task 5: Der wöchentliche Ablauf

**Files:**
- Create: `.github/workflows/kartendaten.yml`
- Modify: `README.md` (Abschnitt über das benötigte Geheimnis)

**Interfaces:**
- Consumes: `scripts/kartendaten-sync.sh` aus Task 4.
- Produces: Zweig `kartendaten-abgleich` in `Haste-MC/forge` samt Pull Request.

- [ ] **Step 1: Den Ablauf schreiben**

`.github/workflows/kartendaten.yml`:

```yaml
name: Kartendaten

# Woechentlich und auf Knopfdruck. Kein Windows noetig: hier wird nichts gepackt.
on:
  schedule:
    - cron: '17 4 * * 1'
  workflow_dispatch: {}

# Nur ein Lauf gleichzeitig - zwei wuerden denselben Zweig gegeneinander schieben.
concurrency:
  group: kartendaten
  cancel-in-progress: false

jobs:
  abgleich:
    runs-on: ubuntu-latest
    steps:
      - name: Auschecken (mit Forge-Submodul)
        uses: actions/checkout@v5
        with:
          submodules: recursive

      - name: JDK 17 (Temurin) einrichten
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: maven

      - name: Forge bauen
        working-directory: forge
        run: mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true

      - name: Abgleich
        id: sync
        run: |
          set +e
          scripts/kartendaten-sync.sh
          echo "code=$?" >> "$GITHUB_OUTPUT"

      - name: Befund anhaengen (immer, auch bei Fehlschlag)
        if: always()
        uses: actions/upload-artifact@v5
        with:
          name: kartendaten-befund
          path: |
            kartendaten-befund*.json
            kartendaten-bericht.md
          if-no-files-found: ignore

      - name: Nichts Neues
        if: steps.sync.outputs.code == '2'
        run: echo "upstream hat keine neuen Kartendaten - kein Pull Request."

      - name: Fehlschlag
        if: steps.sync.outputs.code != '0' && steps.sync.outputs.code != '2'
        run: |
          echo "Der Abgleich ist nicht sauber durchgelaufen; der Befund haengt als Artefakt an." >&2
          exit 1

      - name: Zweig schieben und Pull Request anlegen
        if: steps.sync.outputs.code == '0'
        env:
          GH_TOKEN: ${{ secrets.FORK_TOKEN }}
        working-directory: forge
        run: |
          # Commit im Fork: Englisch, upstream-tauglich, ohne Verweis auf dieses Projekt -
          # wie jeder Commit dort. Der Bericht gehoert in den Pull Request, nicht hierher.
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git remote set-url origin "https://x-access-token:${GH_TOKEN}@github.com/Haste-MC/forge.git"
          git checkout -B kartendaten-abgleich
          git add forge-gui/res/cardsfolder forge-gui/res/editions forge-gui/res/tokenscripts
          git commit -m "Sync card data from upstream master"
          git push --force origin kartendaten-abgleich
          if gh pr view kartendaten-abgleich --repo Haste-MC/forge >/dev/null 2>&1; then
            gh pr edit kartendaten-abgleich --repo Haste-MC/forge \
               --body-file "${GITHUB_WORKSPACE}/kartendaten-bericht.md"
          else
            gh pr create --repo Haste-MC/forge --base mtg-player --head kartendaten-abgleich \
               --title "Sync card data from upstream master" \
               --body-file "${GITHUB_WORKSPACE}/kartendaten-bericht.md"
          fi

      - name: Ausschlussliste fortschreiben
        if: steps.sync.outputs.code == '0'
        run: |
          # Die Liste gehoert ins Hauptprojekt, nicht in den Fork - dafuer reicht das eingebaute Token.
          if ! git diff --quiet -- docs/kartendaten-ausgeschlossen.txt; then
            git config user.name "github-actions[bot]"
            git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
            git add docs/kartendaten-ausgeschlossen.txt
            git commit -m "docs: ausschlussliste aus dem kartendaten-abgleich fortgeschrieben"
            git push
          fi
```

- [ ] **Step 2: Das benötigte Geheimnis dokumentieren**

In `README.md` einen Abschnitt „Kartendaten-Abgleich" ergänzen:

```markdown
## Kartendaten-Abgleich

Der Ablauf `.github/workflows/kartendaten.yml` gleicht Forges Kartendaten wöchentlich mit upstream ab
und legt das Ergebnis als Pull Request in `Haste-MC/forge` vor.

Dafür braucht er ein Geheimnis `FORK_TOKEN` in diesem Repository: ein fein granuliertes
Personal Access Token auf `Haste-MC/forge` mit **Contents: Read and write** und
**Pull requests: Read and write**. Ohne das Token schlägt nur der letzte Schritt fehl — Abgleich und
Prüfung laufen trotzdem, und der Befund hängt als Artefakt am Lauf.
```

- [ ] **Step 3: Den Ablauf auf Gültigkeit prüfen**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/kartendaten.yml')); print('yaml ok, schritte:', len(d['jobs']['abgleich']['steps']))"
```

Erwartet: `yaml ok, schritte: 9`.

- [ ] **Step 4: Committen**

```bash
git add .github/workflows/kartendaten.yml README.md
git commit -m "build: woechentlicher ablauf fuer den kartendaten-abgleich"
```

---

### Task 6: Der Ablauf „Submodul anheben"

Ohne dieses Stück bliebe die Automatik auf halbem Weg stehen: der Fork wäre aktuell, das Paket aber nicht.

**Files:**
- Create: `.github/workflows/submodul.yml`

**Interfaces:**
- Consumes: den gemergten Stand von `Haste-MC/forge`, Branch `mtg-player`.
- Produces: Pull Request in `Haste-MC/MTG-Player` mit angehobenem Submodul-Zeiger.

- [ ] **Step 1: Den Ablauf schreiben**

`.github/workflows/submodul.yml`:

```yaml
name: Submodul anheben

# Nur auf Knopfdruck: gedacht fuer den Moment, in dem der Kartendaten-Pull-Request im Fork
# gemergt ist und der neue Stand ins Hauptprojekt soll.
on:
  workflow_dispatch: {}

jobs:
  anheben:
    runs-on: ubuntu-latest
    permissions:
      contents: write
      pull-requests: write
    steps:
      - uses: actions/checkout@v5
        with:
          submodules: recursive

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: maven

      - name: Neuen Fork-Stand holen
        id: stand
        run: |
          git -C forge fetch origin mtg-player
          git -C forge checkout --detach origin/mtg-player
          if git diff --quiet -- forge; then
            echo "geaendert=0" >> "$GITHUB_OUTPUT"
          else
            echo "geaendert=1" >> "$GITHUB_OUTPUT"
            echo "kurz=$(git -C forge rev-parse --short HEAD)" >> "$GITHUB_OUTPUT"
          fi

      - name: Forge bauen
        if: steps.stand.outputs.geaendert == '1'
        working-directory: forge
        run: mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true

      - name: Bridge-Tests
        if: steps.stand.outputs.geaendert == '1'
        working-directory: bridge
        run: mvn -q test

      - name: Pull Request anlegen
        if: steps.stand.outputs.geaendert == '1'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          zweig="submodul-${{ steps.stand.outputs.kurz }}"
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git checkout -B "$zweig"
          git add forge
          git commit -m "build: forge-submodul auf ${{ steps.stand.outputs.kurz }} angehoben"
          git push --force origin "$zweig"
          gh pr create --base main --head "$zweig" \
             --title "Forge-Submodul auf ${{ steps.stand.outputs.kurz }} anheben" \
             --body "Neuer Stand aus Haste-MC/forge, Branch mtg-player. Bridge-Testsuite in diesem Lauf gruen."

      - name: Nichts zu tun
        if: steps.stand.outputs.geaendert == '0'
        run: echo "Der Submodul-Zeiger ist bereits aktuell."
```

- [ ] **Step 2: Den Ablauf auf Gültigkeit prüfen**

```bash
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/submodul.yml')); print('yaml ok, schritte:', len(d['jobs']['anheben']['steps']))"
```

Erwartet: `yaml ok, schritte: 7`.

- [ ] **Step 3: Committen**

```bash
git add .github/workflows/submodul.yml
git commit -m "build: ablauf zum anheben des forge-submoduls"
```

---

## Was danach von Hand bleibt

- Kevin legt das Geheimnis `FORK_TOKEN` an (Task 5, Step 2 beschreibt es).
- Der erste Lauf wird über „Run workflow" von Hand angestoßen und sein Befund gelesen, bevor der
  wöchentliche Takt zum Tragen kommt. Erwartung für den ersten Lauf: eine **große** Zahl geänderter
  Skripte (2525 zum Zeitpunkt der Spezifikation) und eine Ausschlussliste, die von null auf einige
  Dutzend Einträge wächst.
- `docs/forge-fork.md` bekommt nach dem ersten erfolgreichen Durchgang einen Absatz über den neuen Weg.
