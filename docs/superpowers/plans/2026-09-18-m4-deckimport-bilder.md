# M4: Textlisten-Import und Kartenbilder – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eigene Decks als Textliste (Archidekt/Arena-Format) importieren, speichern und wiederverwenden; Karten im Browser als Bilder (Scryfall, lokal gecacht) statt Textboxen.

**Architecture:** Import über Forges `DeckRecognizer` (forge-core) – Zeilen → Tokens → `Deck`; Problemzeilen werden gesammelt und an den Browser gemeldet. Gespeicherte Decks liegen als `.dck` in `~/.mtg-player/decks/`. Bilder: der JDK-`HttpServer` bekommt einen `/img/`-Handler, der Forges `imageKey` (`c:Name|SET|art`, optional `$alt`) über `ImageUtil.getPaperCardFromImageKey` in Set + Sammlernummer auflöst, von Scryfall lädt (ein Worker, ≥ 100 ms Abstand), unter `~/.mtg-player/cache/images/` ablegt und ausliefert. Frontend: `<img>` mit Text-Fallback in `CardBox`, `CardDetail` und Dialog-Optionen; Lobby mit Textfeld und Liste gespeicherter Decks.

**Tech Stack:** wie M3. Neu: `java.net.http.HttpClient` (JDK), keine weiteren Dependencies.

## Global Constraints

- Forge-Submodule bleibt unverändert.
- Protokoll abwärtskompatibel; neue Felder optional. `lobby` bekommt `decks: [String]` (gespeicherte Decks). `startGame.humanDeck`/`opponents[]` akzeptieren genau eine der Formen `{precon}`, `{saved}`, `{text, name?}`; `name` ist der Speichername (Default: erste `Name:`/Deckname-Zeile, sonst Commander-Name, sonst `Import`).
- Bilder: `GET /img/{urlencoded imageKey}` → `image/jpeg` aus dem Cache oder von Scryfall; Tokens (`t:`), leere/unbekannte Keys → `404`. Scryfall-Regeln: höchstens 10 Requests/s, `User-Agent` gesetzt; hier: ein Worker-Thread, mindestens 100 ms zwischen zwei Requests, fehlgeschlagene Keys werden 10 Minuten lang nicht erneut angefragt. Cache-Verzeichnis `~/.mtg-player/cache/images/`, Dateiname = SHA-1 des Keys + `.jpg`.
- Kein Netzwerkzugriff in Unit-Tests: der Scryfall-Fetcher ist eine austauschbare Schnittstelle.
- Threading wie M2/M3: Deck-Parsing läuft auf dem Socket-Thread (reines CPU), Spielstart weiter über `invokeInEdtLater`.
- Commit-Messages: deutsch, Kleinschreibung, Präfix `m4:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` (verbatim, unabhängig vom Modell).
- Maven aus `bridge/`, Bash-Timeout 600000 ms, nie zwei Maven-Prozesse (`pgrep -f surefire`); ein Nutzer-Prozess auf 8080/8081 darf laufen (Tests nutzen 18081+).

---

## Dateistruktur

```
bridge/src/main/java/mtgplayer/
├── decks/DeckImport.java            NEU: Text → Deck + Problemliste (reine Funktion über Forge)
├── decks/DeckStore.java             NEU: speichern/laden/auflisten in ~/.mtg-player/decks/
├── decks/DeckSource.java            NEU: JSON {precon|saved|text} → Deck (nutzt Precons, DeckStore, DeckImport)
├── images/ImageKeys2Scryfall.java   NEU: imageKey → Scryfall-URL (reine Funktion)
├── images/ImageCache.java           NEU: Disk-Cache + Rate-Limit + Negativ-Cache; Fetcher-Schnittstelle
├── images/ImageHandler.java         NEU: HttpHandler für /img/
├── server/HttpStatic.java           MODIFY: zusätzlichen Context registrieren
├── server/Bridge.java               MODIFY: DeckSource, lobby.decks, Import-Fehler
├── protocol/Messages.java           MODIFY: Lobby.decks
└── Main.java                        MODIFY: ImageHandler einhängen
web/src/
├── protocol.ts                      MODIFY: Lobby.decks, DeckRef-Typ, startGame-Formen
├── components/Lobby.tsx             MODIFY: Textfeld, gespeicherte Decks, Fehleranzeige
├── components/CardBox.tsx           MODIFY: Bild mit Text-Fallback
├── components/CardDetail.tsx        MODIFY: großes Bild
├── components/ChoiceDialog.tsx      MODIFY: Bild in Option-Details
├── components/CardImage.tsx         NEU: <img> mit onError-Fallback
├── styles.css                       MODIFY
└── vite.config.ts                   MODIFY: Proxy /img → 8080
```

---

### Task 1: DeckImport und DeckStore

**Files:**
- Create: `bridge/src/main/java/mtgplayer/decks/DeckImport.java`
- Create: `bridge/src/main/java/mtgplayer/decks/DeckStore.java`
- Test: `bridge/src/test/java/mtgplayer/decks/DeckImportTest.java`, `bridge/src/test/java/mtgplayer/decks/DeckStoreTest.java`

**Interfaces:**
- Consumes: `ForgeBoot.init()`, `ForgeBoot.dataDir()`.
- Produces:
  - `DeckImport.Result(forge.deck.Deck deck, List<String> problems)`; `DeckImport.parse(String text) : Result` – `problems` leer = importierbar; `deck` ist auch bei Problemen gefüllt (mit den erkannten Karten).
  - `DeckImport.suggestName(String text, Deck deck) : String`.
  - `DeckStore(Path dir)`; `List<String> names()`, `Deck load(String name)` (wirft `IllegalArgumentException` bei unbekanntem Namen), `void save(String name, Deck deck)`, `static DeckStore standard()` (= `ForgeBoot.dataDir().resolve("decks")`).

- [ ] **Step 1: Failing Tests**

`bridge/src/test/java/mtgplayer/decks/DeckImportTest.java`:

```java
package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.DeckSection;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeckImportTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static final String ARCHIDEKT = String.join("\n",
            "Commander",
            "1x Felothar the Steadfast (tdc) 1 [Commander{top}]",
            "",
            "Main",
            "1x Sol Ring (c21) 263 [Ramp]",
            "3x Forest (tdc) 300 [Land]",
            "1x Swords to Plowshares [Removal]",
            "1x Gibtsnicht Karte [Test]",
            "");

    @Test
    void archidektExportWirdErkannt() {
        DeckImport.Result r = DeckImport.parse(ARCHIDEKT);
        assertEquals(1, r.deck().getCommanders().size(), "Commander erkannt");
        assertEquals("Felothar the Steadfast", r.deck().getCommanders().get(0).getName());
        assertEquals(5, r.deck().get(DeckSection.Main).countAll(), "1 Sol Ring + 3 Forest + 1 Swords");
        assertEquals(1, r.problems().size(), "eine unbekannte Karte");
        assertTrue(r.problems().get(0).contains("Gibtsnicht Karte"));
    }

    @Test
    void arenaFormatOhneSectionsNimmtErstenLegalenCommander() {
        String text = "1 Sol Ring\n1 Felothar the Steadfast\n2 Forest\n";
        DeckImport.Result r = DeckImport.parse(text);
        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertEquals(1, r.deck().getCommanders().size());
        assertEquals("Felothar the Steadfast", r.deck().getCommanders().get(0).getName());
        assertEquals(3, r.deck().get(DeckSection.Main).countAll(), "Commander nicht mehr im Main");
    }

    @Test
    void ohneCommanderIstEinProblem() {
        DeckImport.Result r = DeckImport.parse("1 Sol Ring\n2 Forest\n");
        assertFalse(r.problems().isEmpty());
        assertTrue(r.problems().get(0).toLowerCase().contains("commander"));
    }

    @Test
    void leererTextIstEinProblem() {
        DeckImport.Result r = DeckImport.parse("   \n\n");
        assertFalse(r.problems().isEmpty());
    }

    @Test
    void namensvorschlag() {
        DeckImport.Result r = DeckImport.parse(ARCHIDEKT);
        assertEquals("Felothar the Steadfast", DeckImport.suggestName(ARCHIDEKT, r.deck()));
        assertEquals("Mein Deck", DeckImport.suggestName("Name: Mein Deck\n" + ARCHIDEKT, r.deck()));
    }
}
```

`bridge/src/test/java/mtgplayer/decks/DeckStoreTest.java`:

```java
package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DeckStoreTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void speichernListenLaden(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        assertEquals(List.of(), store.names());
        Deck d = Precons.load("Abzan Armor [TDC] [2025]");
        store.save("Mein Abzan", d);
        assertEquals(List.of("Mein Abzan"), store.names());
        Deck loaded = store.load("Mein Abzan");
        assertEquals(1, loaded.getCommanders().size());
        assertEquals(d.get(forge.deck.DeckSection.Main).countAll(), loaded.get(forge.deck.DeckSection.Main).countAll());
    }

    @Test
    void unbekannterNameWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckStore(dir).load("nix"));
    }

    @Test
    void dateinameWirdBereinigt(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        store.save("A/B: C?", Precons.load("Abzan Armor [TDC] [2025]"));
        assertEquals(List.of("A/B: C?"), store.names(), "Anzeigename bleibt, nur der Dateiname wird bereinigt");
        assertTrue(dir.resolve("A_B_ C_.dck").toFile().exists());
    }
}
```

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='DeckImportTest,DeckStoreTest'`
Expected: COMPILATION ERROR.

- [ ] **Step 3: DeckImport schreiben**

`bridge/src/main/java/mtgplayer/decks/DeckImport.java`:

```java
package mtgplayer.decks;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Textliste (Archidekt-/Arena-Export, Forge-.dck-Sektionen) → Deck. Nutzt Forges DeckRecognizer;
 * davor werden Archidekt-Kategorien "[Ramp]" und Foil-Marker entfernt. Kein Commander in der
 * Liste → erster Commander-fähiger Eintrag aus dem Main wandert in die Commander-Sektion.
 */
public final class DeckImport {

    public record Result(Deck deck, List<String> problems) { }

    private static final Pattern CATEGORY = Pattern.compile("\\s*\\[[^\\]]*\\]\\s*$");
    private static final Pattern FOIL = Pattern.compile("\\s*\\*F\\*\\s*$");
    private static final Pattern NAME_LINE = Pattern.compile("^(?:name|deck)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private DeckImport() { }

    public static Result parse(String text) {
        List<String> problems = new ArrayList<>();
        Deck deck = new Deck();
        if (text == null || text.isBlank()) {
            problems.add("Leere Liste");
            return new Result(deck, problems);
        }
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            lines[i] = clean(lines[i]);
        }
        DeckRecognizer rec = new DeckRecognizer();
        rec.setAllowedDeckSections(List.of(DeckSection.Main, DeckSection.Sideboard, DeckSection.Commander));
        rec.forceImportBannedAndRestrictedCards();
        List<Token> tokens = rec.parseCardList(lines);

        for (Token t : tokens) {
            TokenType type = t.getType();
            if (type == TokenType.DECK_NAME) {
                deck.setName(t.getText());
                continue;
            }
            if (t.isTokenForDeck()) {
                DeckSection section = t.getTokenSection() == null ? DeckSection.Main : t.getTokenSection();
                deck.getOrCreate(section).add(t.getCard(), t.getQuantity());
                continue;
            }
            if (type == TokenType.UNKNOWN_CARD || type == TokenType.UNSUPPORTED_CARD
                    || type == TokenType.CARD_FROM_INVALID_SET || type == TokenType.CARD_FROM_NOT_ALLOWED_SET) {
                problems.add("Unbekannte Karte: " + t.getText());
            }
        }

        if (deck.getCommanders().isEmpty()) {
            PaperCard cmd = firstCommanderCandidate(deck);
            if (cmd == null) {
                problems.add("Kein Commander gefunden (Sektion 'Commander' oder legendäre Kreatur im Main)");
            } else {
                deck.get(DeckSection.Main).remove(cmd, 1);
                deck.getOrCreate(DeckSection.Commander).add(cmd, 1);
            }
        }
        return new Result(deck, problems);
    }

    private static PaperCard firstCommanderCandidate(Deck deck) {
        if (!deck.has(DeckSection.Main)) return null;
        for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Main)) {
            if (e.getKey().getRules().canBeCommander()) {
                return e.getKey();
            }
        }
        return null;
    }

    /** "Name: X"-Zeile, sonst Commander-Name, sonst "Import". */
    public static String suggestName(String text, Deck deck) {
        if (text != null) {
            for (String line : text.split("\\r?\\n")) {
                Matcher m = NAME_LINE.matcher(line.trim());
                if (m.matches()) return m.group(1).trim();
            }
        }
        if (deck != null && !deck.getCommanders().isEmpty()) {
            return deck.getCommanders().get(0).getName();
        }
        return "Import";
    }

    private static String clean(String line) {
        String s = FOIL.matcher(CATEGORY.matcher(line).replaceAll("")).replaceAll("");
        return s.trim();
    }
}
```

Hinweise für den Implementierer:
- `Deck.has(DeckSection)`, `Deck.get(DeckSection)` (CardPool, iterierbar als `Entry<PaperCard,Integer>`), `CardPool.remove(PaperCard, int)`, `Deck.getOrCreate`, `Deck.getCommanders()` und `CardRules.canBeCommander()` existieren in forge-core 2.0.14 – bei Compiler-Fehlern die Klassen `forge/forge-core/src/main/java/forge/deck/{Deck,CardPool,DeckSection}.java`, `forge/forge-core/src/main/java/forge/util/ItemPool.java` und `forge/forge-core/src/main/java/forge/card/CardRules.java` lesen und den Aufruf anpassen; jede Abweichung im Report nennen.
- Die `TokenType`-Konstanten `UNKNOWN_CARD`, `UNSUPPORTED_CARD`, `CARD_FROM_INVALID_SET`, `CARD_FROM_NOT_ALLOWED_SET` stehen in `forge/forge-core/src/main/java/forge/deck/DeckRecognizer.java` ab Zeile 47; fehlt eine, weglassen.
- Wenn `archidektExportWirdErkannt` scheitert, weil die Zeile `Commander` nicht als Sektion erkannt wird: die Sektionserkennung sitzt in `DeckRecognizer.recognizeLine` → `Token.DeckSection(...)`; dann `Name: `-freie Header vor dem Parsen selbst erkennen (Zeile, die nur aus einem Sektionsnamen besteht, → `"[Commander]"`-Schreibweise, die Forge sicher kennt). Im Report dokumentieren.

- [ ] **Step 4: DeckStore schreiben**

`bridge/src/main/java/mtgplayer/decks/DeckStore.java`:

```java
package mtgplayer.decks;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import mtgplayer.forge.ForgeBoot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** Gespeicherte eigene Decks als Forge-.dck unter einem Verzeichnis. Anzeigename = Deck-Name aus der Datei. */
public final class DeckStore {

    private final Path dir;

    public DeckStore(Path dir) {
        this.dir = dir;
    }

    public static DeckStore standard() {
        return new DeckStore(ForgeBoot.dataDir().resolve("decks"));
    }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".dck")).sorted().forEach(p -> {
                Deck d = DeckSerializer.fromFile(p.toFile());
                if (d != null) out.add(d.getName());
            });
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht lesen: " + dir, e);
        }
        Collections.sort(out);
        return out;
    }

    public Deck load(String name) {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("unbekanntes Deck: " + name);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".dck")).toList()) {
                Deck d = DeckSerializer.fromFile(p.toFile());
                if (d != null && name.equals(d.getName())) return d;
            }
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht lesen: " + dir, e);
        }
        throw new IllegalArgumentException("unbekanntes Deck: " + name);
    }

    public void save(String name, Deck deck) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("kann Deck-Verzeichnis nicht anlegen: " + dir, e);
        }
        deck.setName(name);
        File f = dir.resolve(fileName(name)).toFile();
        DeckSerializer.writeDeck(deck, f);
    }

    static String fileName(String name) {
        return name.replaceAll("[^A-Za-z0-9 _\\-\\[\\]().]", "_") + ".dck";
    }
}
```

Hinweis: `Deck.setName(String)` – falls es die Methode nicht gibt, `new Deck(name)` + Sektionen kopieren (`deck.get(section)` je `DeckSection`), im Report notieren.

- [ ] **Step 5: Tests laufen lassen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='DeckImportTest,DeckStoreTest'`
Expected: `Tests run: 5` und `Tests run: 3`, keine Fehler. Bei `arenaFormatOhneSectionsNimmtErstenLegalenCommander`: schlägt es fehl, weil Forge "Felothar the Steadfast" ohne Set in einem anderen Druck liefert – egal, der Name zählt. Schlägt `archidektExportWirdErkannt` bei `countAll()` mit 4 statt 5 fehl, hat Forge "Swords to Plowshares" ohne Set nicht aufgelöst → `rec.setArtPreference(...)`/Standard prüfen und im Report beschreiben, was Forge zurückgab.

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m4: deckimport ueber forges deckrecognizer, deckstore fuer eigene decks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: DeckSource, Lobby mit gespeicherten Decks, Spielstart aus Text

**Files:**
- Create: `bridge/src/main/java/mtgplayer/decks/DeckSource.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java`
- Modify: `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/decks/DeckSourceTest.java`

**Interfaces:**
- Consumes: `Precons`, `DeckStore`, `DeckImport`.
- Produces:
  - `Messages.Lobby(String type, List<String> precons, List<String> decks)` + Convenience `Lobby(List<String> precons, List<String> decks)`.
  - `DeckSource(DeckStore store)`; `Deck resolve(JsonNode node)` – wirft `IllegalArgumentException` mit lesbarer Meldung (bei Import-Problemen: alle Problemzeilen, mit `\n` verbunden). Text-Decks werden nach erfolgreichem Parsen unter `name` (oder `DeckImport.suggestName`) gespeichert.
  - Bridge: `lobby` enthält `decks`; nach jedem Spielstart mit Text-Deck wird ein frisches `lobby` gesendet; Import-Fehler → `error` mit den Problemzeilen, kein Spielstart.

- [ ] **Step 1: Failing Test**

`bridge/src/test/java/mtgplayer/decks/DeckSourceTest.java`:

```java
package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

class DeckSourceTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void precon(@TempDir Path dir) {
        Deck d = new DeckSource(new DeckStore(dir)).resolve(Json.parse("{\"precon\":\"Abzan Armor [TDC] [2025]\"}"));
        assertEquals(1, d.getCommanders().size());
    }

    @Test
    void textWirdGeparstUndGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        String text = "1 Sol Ring\\n1 Felothar the Steadfast\\n2 Forest\\n";
        Deck d = new DeckSource(store).resolve(Json.parse("{\"text\":\"" + text + "\",\"name\":\"Testdeck\"}"));
        assertEquals("Felothar the Steadfast", d.getCommanders().get(0).getName());
        assertEquals(List.of("Testdeck"), store.names());
        Deck again = new DeckSource(store).resolve(Json.parse("{\"saved\":\"Testdeck\"}"));
        assertEquals(1, again.getCommanders().size());
    }

    @Test
    void textOhneNamenNimmtVorschlag(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        new DeckSource(store).resolve(Json.parse("{\"text\":\"1 Sol Ring\\n1 Felothar the Steadfast\\n\"}"));
        assertEquals(List.of("Felothar the Steadfast"), store.names());
    }

    @Test
    void importProblemeWerdenGemeldetUndNichtGespeichert(@TempDir Path dir) {
        DeckStore store = new DeckStore(dir);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DeckSource(store).resolve(Json.parse("{\"text\":\"1 Sol Ring\\n1 Gibtsnicht\\n\"}")));
        assertTrue(e.getMessage().contains("Gibtsnicht"));
        assertTrue(e.getMessage().toLowerCase().contains("commander"));
        assertEquals(List.of(), store.names());
    }

    @Test
    void ohneBekannteFormWirft(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new DeckSource(new DeckStore(dir)).resolve(Json.parse("{}")));
    }
}
```

- [ ] **Step 2: Test laufen lassen, muss fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest=DeckSourceTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: DeckSource und Lobby**

`bridge/src/main/java/mtgplayer/decks/DeckSource.java`:

```java
package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import forge.deck.Deck;
import mtgplayer.forge.Precons;

/** Übersetzt die Deck-Angabe aus startGame ({precon} | {saved} | {text, name?}) in ein Deck. */
public final class DeckSource {

    private final DeckStore store;

    public DeckSource(DeckStore store) {
        this.store = store;
    }

    public Deck resolve(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            throw new IllegalArgumentException("Deck fehlt");
        }
        if (node.hasNonNull("precon")) {
            return Precons.load(node.get("precon").asText());
        }
        if (node.hasNonNull("saved")) {
            return store.load(node.get("saved").asText());
        }
        if (node.hasNonNull("text")) {
            String text = node.get("text").asText();
            DeckImport.Result r = DeckImport.parse(text);
            if (!r.problems().isEmpty()) {
                throw new IllegalArgumentException("Deck-Import:\n" + String.join("\n", r.problems()));
            }
            String name = node.hasNonNull("name") && !node.get("name").asText().isBlank()
                    ? node.get("name").asText().trim() : DeckImport.suggestName(text, r.deck());
            store.save(name, r.deck());
            return r.deck();
        }
        throw new IllegalArgumentException("Deck braucht 'precon', 'saved' oder 'text'");
    }
}
```

`Messages.Lobby` ersetzen:

```java
    public record Lobby(String type, List<String> precons, List<String> decks) {
        public Lobby(List<String> precons, List<String> decks) { this("lobby", precons, decks); }
    }
```

- [ ] **Step 4: Bridge anpassen**

In `Bridge`:
- Feld `private final DeckSource decks = new DeckSource(DeckStore.standard());` und `private final DeckStore store = DeckStore.standard();` (die `DeckSource` bekommt dasselbe `store`-Objekt: `new DeckSource(store)`).
- `onClientConnected` und alle weiteren `new Messages.Lobby(Precons.names())` → `new Messages.Lobby(Precons.names(), store.names())`.
- `startGame(JsonNode)`: die statische `deck(...)`-Methode entfernen; stattdessen

```java
        Deck human;
        List<Deck> ai = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            human = decks.resolve(msg.path("humanDeck"));
            int i = 1;
            for (JsonNode o : msg.path("opponents")) {
                ai.add(decks.resolve(o));
                names.add(o.path("name").asText("KI " + i++));
            }
        } catch (IllegalArgumentException e) {
            ws.send(new Messages.ErrorMsg(e.getMessage()));
            return;
        }
        ws.send(new Messages.Lobby(Precons.names(), store.names())); // ggf. neu gespeichertes Deck
```

und danach wie bisher `ui(() -> match.start(...))`. Javadoc der Methode auf die drei Formen aktualisieren.

- [ ] **Step 5: Tests**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='DeckSourceTest,BridgeEndToEndTest' 2>&1 | tail -5`
Expected: grün (DeckSourceTest 5; E2E unverändert grün – die Lobby-Nachricht hat jetzt zusätzlich `decks`).

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m4: decksource – spielstart aus precon, gespeichertem deck oder textliste; lobby listet eigene decks

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Bilder – Scryfall-URL, Cache, HTTP-Handler

**Files:**
- Create: `bridge/src/main/java/mtgplayer/images/ImageKeys2Scryfall.java`
- Create: `bridge/src/main/java/mtgplayer/images/ImageCache.java`
- Create: `bridge/src/main/java/mtgplayer/images/ImageHandler.java`
- Modify: `bridge/src/main/java/mtgplayer/server/HttpStatic.java`
- Modify: `bridge/src/main/java/mtgplayer/Main.java`
- Test: `bridge/src/test/java/mtgplayer/images/ImageKeys2ScryfallTest.java`, `bridge/src/test/java/mtgplayer/images/ImageCacheTest.java`

**Interfaces:**
- Produces:
  - `ImageKeys2Scryfall.url(String imageKey) : Optional<String>` – reine Funktion (braucht Forge-Kartendatenbank); `Optional.empty()` für Tokens/unbekannt.
  - `ImageCache.Fetcher { byte[] fetch(String url) throws IOException; }` (null = 404).
  - `ImageCache(Path dir, Fetcher fetcher, long minGapMs)`; `Optional<byte[]> get(String imageKey)` blockiert bis Cache-Treffer oder Download; `static ImageCache standard()` (Dir `~/.mtg-player/cache/images`, HttpClient-Fetcher mit User-Agent `MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)`, 100 ms).
  - `ImageHandler implements com.sun.net.httpserver.HttpHandler` für `/img/{key}`.
  - `HttpStatic(int port, Path dir)` bekommt `void addContext(String path, HttpHandler h)`.

- [ ] **Step 1: Failing Tests**

`bridge/src/test/java/mtgplayer/images/ImageKeys2ScryfallTest.java`:

```java
package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import forge.item.PaperCard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class ImageKeys2ScryfallTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    @Test
    void kartenKeyWirdZuSetUndSammlernummer() {
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        String key = felothar.getImageKey(false);
        Optional<String> url = ImageKeys2Scryfall.url(key);
        assertTrue(url.isPresent(), key);
        assertEquals("https://api.scryfall.com/cards/tdc/" + felothar.getCollectorNumber() + "?format=image&version=normal", url.get());
    }

    @Test
    void rueckseiteBekommtFaceBack() {
        PaperCard felothar = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0);
        String key = felothar.getImageKey(false) + "$alt";
        assertTrue(ImageKeys2Scryfall.url(key).get().endsWith("&face=back"));
    }

    @Test
    void tokensUndUnbekanntesSindLeer() {
        assertTrue(ImageKeys2Scryfall.url("t:goblin_r_1_1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url("c:Gibtsnicht|XXX|1").isEmpty());
        assertTrue(ImageKeys2Scryfall.url(null).isEmpty());
        assertTrue(ImageKeys2Scryfall.url("").isEmpty());
    }
}
```

`bridge/src/test/java/mtgplayer/images/ImageCacheTest.java`:

```java
package mtgplayer.images;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import mtgplayer.forge.ForgeBoot;
import mtgplayer.forge.Precons;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class ImageCacheTest {

    private static String key;

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
        key = Precons.load("Abzan Armor [TDC] [2025]").getCommanders().get(0).getImageKey(false);
    }

    @Test
    void ladeEinmalDannAusDemCache(@TempDir Path dir) throws Exception {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return new byte[] {1, 2, 3}; }, 0);
        assertArrayEquals(new byte[] {1, 2, 3}, cache.get(key).get());
        assertArrayEquals(new byte[] {1, 2, 3}, cache.get(key).get());
        assertEquals(1, calls.size(), "zweiter Zugriff kommt aus dem Cache");
        assertTrue(calls.get(0).startsWith("https://api.scryfall.com/cards/tdc/"));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".jpg")).count());
        }
    }

    @Test
    void fehlschlagWirdNegativGecacht(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return null; }, 0);
        assertTrue(cache.get(key).isEmpty());
        assertTrue(cache.get(key).isEmpty());
        assertEquals(1, calls.size(), "404 wird nicht sofort erneut angefragt");
    }

    @Test
    void tokenOhneUrlFragtNichtAn(@TempDir Path dir) {
        List<String> calls = new ArrayList<>();
        ImageCache cache = new ImageCache(dir, url -> { calls.add(url); return new byte[] {1}; }, 0);
        assertTrue(cache.get("t:goblin_r_1_1").isEmpty());
        assertEquals(0, calls.size());
    }

    @Test
    void mindestabstandZwischenRequests(@TempDir Path dir) {
        ImageCache cache = new ImageCache(dir, url -> new byte[] {1}, 150);
        String key2 = Precons.load("Adaptive Enchantment [C18] [2018]").getCommanders().get(0).getImageKey(false);
        long t0 = System.currentTimeMillis();
        cache.get(key);
        cache.get(key2);
        assertTrue(System.currentTimeMillis() - t0 >= 150, "zweiter Download wartet den Mindestabstand ab");
    }
}
```

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='ImageKeys2ScryfallTest,ImageCacheTest'`
Expected: COMPILATION ERROR.

- [ ] **Step 3: ImageKeys2Scryfall**

`bridge/src/main/java/mtgplayer/images/ImageKeys2Scryfall.java`:

```java
package mtgplayer.images;

import forge.ImageKeys;
import forge.item.PaperCard;
import forge.util.ImageUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Forge-imageKey ("c:Name|SET|art", optional "$alt") → Scryfall-Bild-URL. */
public final class ImageKeys2Scryfall {

    private ImageKeys2Scryfall() { }

    public static Optional<String> url(String imageKey) {
        if (imageKey == null || !imageKey.startsWith(ImageKeys.CARD_PREFIX)) {
            return Optional.empty();
        }
        boolean back = imageKey.endsWith(ImageKeys.BACKFACE_POSTFIX);
        String key = back ? imageKey.substring(0, imageKey.length() - ImageKeys.BACKFACE_POSTFIX.length()) : imageKey;
        PaperCard pc;
        try {
            pc = ImageUtil.getPaperCardFromImageKey(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (pc == null || pc.getEdition() == null || pc.getCollectorNumber() == null || pc.getCollectorNumber().isBlank()) {
            return Optional.empty();
        }
        String set = pc.getEdition().toLowerCase();
        String num = URLEncoder.encode(pc.getCollectorNumber(), StandardCharsets.UTF_8);
        String url = "https://api.scryfall.com/cards/" + set + "/" + num + "?format=image&version=normal";
        return Optional.of(back ? url + "&face=back" : url);
    }
}
```

Hinweis: `ImageUtil.getPaperCardFromImageKey` liegt in `forge-core` (`forge/forge-core/src/main/java/forge/util/ImageUtil.java:25`). Liefert es für den Test-Key `null`, den Key mit `System.err` ausgeben und `StaticData.instance().getCommonCards().getCard(name, set)` als Fallback verwenden (Name/Set aus dem Key mit `|` splitten).

- [ ] **Step 4: ImageCache**

`bridge/src/main/java/mtgplayer/images/ImageCache.java`:

```java
package mtgplayer.images;

import mtgplayer.forge.ForgeBoot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-Cache für Kartenbilder. Downloads laufen serialisiert mit Mindestabstand (Scryfall-Etikette);
 * Fehlschläge werden 10 Minuten lang nicht wiederholt.
 */
public final class ImageCache {

    public interface Fetcher {
        /** @return Bilddaten oder null bei 404/Fehler */
        byte[] fetch(String url) throws IOException;
    }

    private static final long NEGATIVE_TTL_MS = 10 * 60 * 1000L;

    private final Path dir;
    private final Fetcher fetcher;
    private final long minGapMs;
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private final Object downloadLock = new Object();
    private long lastRequestAt;

    public ImageCache(Path dir, Fetcher fetcher, long minGapMs) {
        this.dir = dir;
        this.fetcher = fetcher;
        this.minGapMs = minGapMs;
    }

    public static ImageCache standard() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        Fetcher http = url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                    .header("Accept", "image/jpeg,image/*")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            try {
                HttpResponse<byte[]> res = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                return res.statusCode() == 200 ? res.body() : null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
        return new ImageCache(ForgeBoot.dataDir().resolve("cache").resolve("images"), http, 100);
    }

    public Optional<byte[]> get(String imageKey) {
        Optional<String> url = ImageKeys2Scryfall.url(imageKey);
        if (url.isEmpty()) return Optional.empty();
        Path file = dir.resolve(sha1(imageKey) + ".jpg");
        if (Files.isRegularFile(file)) {
            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                // Datei kaputt → neu laden
            }
        }
        Long until = failedUntil.get(imageKey);
        if (until != null && until > System.currentTimeMillis()) return Optional.empty();

        synchronized (downloadLock) {
            if (Files.isRegularFile(file)) { // ein anderer Thread hat es gerade geladen
                try { return Optional.of(Files.readAllBytes(file)); } catch (IOException ignored) { }
            }
            long wait = lastRequestAt + minGapMs - System.currentTimeMillis();
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return Optional.empty(); }
            }
            lastRequestAt = System.currentTimeMillis();
            byte[] data = null;
            try {
                data = fetcher.fetch(url.get());
            } catch (IOException e) {
                System.err.println("[images] " + url.get() + ": " + e);
            }
            if (data == null || data.length == 0) {
                failedUntil.put(imageKey, System.currentTimeMillis() + NEGATIVE_TTL_MS);
                return Optional.empty();
            }
            try {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(sha1(imageKey) + ".part");
                Files.write(tmp, data);
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[images] cache schreiben: " + e);
            }
            return Optional.of(data);
        }
    }

    static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 5: ImageHandler, HttpStatic, Main**

`bridge/src/main/java/mtgplayer/images/ImageHandler.java`:

```java
package mtgplayer.images;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** GET /img/{urlencoded imageKey} → JPEG aus dem ImageCache, sonst 404. */
public final class ImageHandler implements HttpHandler {

    private final ImageCache cache;

    public ImageHandler(ImageCache cache) {
        this.cache = cache;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String key = path.length() > 5 ? URLDecoder.decode(path.substring(5), StandardCharsets.UTF_8) : "";
        Optional<byte[]> img = key.isBlank() ? Optional.empty() : cache.get(key);
        if (img.isEmpty()) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "image/jpeg");
        ex.getResponseHeaders().add("Cache-Control", "public, max-age=604800");
        ex.sendResponseHeaders(200, img.get().length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(img.get());
        }
    }
}
```

`HttpStatic`: Methode ergänzen

```java
    public void addContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler);
    }
```

und in der `/`-Context-Lambda nichts ändern (JDK-HttpServer wählt den längsten passenden Prefix, `/img/` gewinnt).

`Main`: nach `HttpStatic http = new HttpStatic(httpPort, web);` und vor `http.start()`:

```java
        http.addContext("/img/", new mtgplayer.images.ImageHandler(mtgplayer.images.ImageCache.standard()));
```

(Imports statt FQ-Namen verwenden.) Der HttpServer nutzt standardmäßig einen einzigen Thread; damit langsame Scryfall-Downloads die Seite nicht blockieren, in `HttpStatic` nach `HttpServer.create(...)`: `server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));` – die Downloads selbst bleiben durch den `downloadLock` serialisiert.

- [ ] **Step 6: Tests, Rauchtest gegen Scryfall**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='ImageKeys2ScryfallTest,ImageCacheTest'`
Expected: `Tests run: 3` und `Tests run: 4`, grün.

Rauchtest mit echtem Netz (einmalig, kein Unit-Test): Bridge im Hintergrund starten (`mvn -q compile exec:java > …/scratchpad/bridge-m4.log 2>&1 &`, auf `Bereit.` warten; falls der Nutzer-Prozess auf 8080/8081 läuft, mit `-Dmtgplayer.httpPort=18080 -Dmtgplayer.wsPort=18091` starten), dann:

Run: `curl -s -o /tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/felothar.jpg -w '%{http_code} %{content_type} %{size_download}\n' "http://127.0.0.1:18080/img/$(python3 -c "import urllib.parse;print(urllib.parse.quote('c:Felothar the Steadfast|TDC|1'))")"`
Expected: `200 image/jpeg <mehr als 20000>`. Zweiter Aufruf identisch, aber ohne Netz-Latenz. `ls ~/.mtg-player/cache/images/` zeigt eine `.jpg`. Danach den Hintergrundprozess beenden (`pkill -f 'httpPort=18080'`).

Falls Scryfall `404` liefert: den Key/URL aus `bridge-m4.log` prüfen – Forge-Set-Codes weichen selten von Scryfall ab; dann in `ImageKeys2Scryfall` ein Fallback auf `https://api.scryfall.com/cards/named?exact=<name>&format=image&version=normal` (Name URL-encodiert) einbauen, wenn `getCollectorNumber()` leer ist **oder** der Set-Code nicht dreistellig/vierstellig alphanumerisch ist, und den Fall im Report beschreiben.

- [ ] **Step 7: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m4: kartenbilder – scryfall-url aus imageKey, disk-cache mit rate-limit, /img-handler

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Frontend – Import-Lobby und Bilder

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/components/{Lobby,CardBox,CardDetail,ChoiceDialog}.tsx`, `web/src/styles.css`, `web/vite.config.ts`
- Create: `web/src/components/CardImage.tsx`
- Test: `web/src/store.test.ts` (Lobby-Reducer mit `decks`)

**Interfaces:**
- Consumes: `lobby.decks`, `startGame` mit `{precon}|{saved}|{text,name?}`, `GET /img/{key}`.

- [ ] **Step 1: Typen**

`protocol.ts`:

```ts
export interface Lobby { type: "lobby"; precons: string[]; decks?: string[]; }
export type DeckRef = { precon: string } | { saved: string } | { text: string; name?: string };
```

und in `Outbound` die `startGame`-Variante zu `{ type: "startGame"; humanDeck: DeckRef; opponents: { deck: DeckRef; name: string }[] }` – **Achtung Wire-Format:** die Bridge liest `opponents[i]` direkt als Deck-Objekt mit zusätzlichem `name`. Deshalb stattdessen: `opponents: (DeckRef & { name: string })[]`.

`store.ts`: `AppState` bekommt `decks: string[]` (initial `[]`); `reduce` für `lobby`: `decks: m.decks ?? []`.

Store-Test ergänzen:

```ts
  it("lobby setzt gespeicherte decks", () => {
    const s = reduce(initialState, { type: "lobby", precons: ["A"], decks: ["Mein Deck"] });
    expect(s.decks).toEqual(["Mein Deck"]);
    const s2 = reduce(s, { type: "lobby", precons: ["A"] });
    expect(s2.decks).toEqual([]);
  });
```

- [ ] **Step 2: Lobby**

`Lobby.tsx` komplett ersetzen:

```tsx
import { useState } from "react";
import type { DeckRef } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

type Pick = { kind: "precon" | "saved" | "text"; value: string; name: string };

const EMPTY: Pick = { kind: "precon", value: "", name: "" };

function toRef(p: Pick): DeckRef | undefined {
  if (p.kind === "text") return p.value.trim() ? { text: p.value, name: p.name.trim() || undefined } : undefined;
  if (!p.value) return undefined;
  return p.kind === "precon" ? { precon: p.value } : { saved: p.value };
}

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const [human, setHuman] = useState<Pick>(EMPTY);
  const [ais, setAis] = useState<Pick[]>([EMPTY]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const ready = humanRef !== undefined && aiRefs.every((r) => r !== undefined);
  const lastError = [...log].reverse().find((l) => l.startsWith("⚠"));

  const start = () => {
    if (!ready) return;
    send({
      type: "startGame",
      humanDeck: humanRef!,
      opponents: aiRefs.map((r, i) => ({ ...(r as DeckRef), name: `KI ${i + 1}` })),
    });
  };

  const picker = (p: Pick, onChange: (n: Pick) => void) => (
    <div className="pick">
      <select value={p.kind} onChange={(e) => onChange({ ...p, kind: e.target.value as Pick["kind"], value: "" })}>
        <option value="precon">Precon</option>
        <option value="saved">Eigenes Deck</option>
        <option value="text">Textliste</option>
      </select>
      {p.kind === "precon" && (
        <select value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })}>
          <option value="">– Precon wählen –</option>
          {precons.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
      )}
      {p.kind === "saved" && (
        <select value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })}>
          <option value="">– gespeichertes Deck –</option>
          {decks.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
      )}
      {p.kind === "text" && (
        <div className="textdeck">
          <input placeholder="Name (optional)" value={p.name} onChange={(e) => onChange({ ...p, name: e.target.value })} />
          <textarea rows={8} placeholder={"Archidekt/Arena-Export einfügen, z. B.\n1 Sol Ring (c21) 263\nCommander\n1 Felothar the Steadfast"}
            value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })} />
        </div>
      )}
    </div>
  );

  return (
    <div className="lobby">
      <h1>MTG-Player</h1>
      {precons.length === 0 && <p>Verbinde mit der Bridge …</p>}
      <label>Dein Deck</label>
      {picker(human, setHuman)}
      {ais.map((a, i) => (
        <div key={i}>
          <label>KI {i + 1} {ais.length > 1 && <button onClick={() => setAis(ais.filter((_, j) => j !== i))}>–</button>}</label>
          {picker(a, (n) => setAis(ais.map((x, j) => (j === i ? n : x))))}
        </div>
      ))}
      {ais.length < 5 && <button onClick={() => setAis([...ais, EMPTY])}>+ KI</button>}
      <button className="primary" disabled={!ready} onClick={start}>Spiel starten</button>
      {lastError && <pre className="import-error">{lastError}</pre>}
    </div>
  );
}
```

- [ ] **Step 3: CardImage und Einbau**

`web/src/components/CardImage.tsx`:

```tsx
import { useState } from "react";

/** Kartenbild über die Bridge; bei 404 (Token, unbekannt) rendert der Aufrufer den Text-Fallback. */
export default function CardImage({ imageKey, className, onFail }: { imageKey?: string; className?: string; onFail?: () => void }) {
  const [failed, setFailed] = useState(false);
  if (!imageKey || failed) return null;
  return (
    <img className={className} src={`/img/${encodeURIComponent(imageKey)}`} alt="" draggable={false}
      onError={() => { setFailed(true); onFail?.(); }} />
  );
}
```

`CardBox.tsx`: im Karten-Div zuerst `<CardImage imageKey={card.imageKey} className="art" onFail={() => setNoImg(true)} />` rendern (mit `const [noImg, setNoImg] = useState(!card.imageKey);` – `useState` importieren, vor dem `faceDown`-Return aufrufen); die Text-Elemente (`name`, `meta`, `type`) nur rendern, wenn `noImg`; `pt` und `counters` immer (als Overlay unten). Klasse `has-img` ans Div, wenn `!noImg`.

`CardDetail.tsx`: über dem Namen `<CardImage imageKey={card.imageKey} className="art-large" />`.

`ChoiceDialog.tsx` `optionView`: wenn `o.detail?.imageKey`, links ein `<CardImage imageKey={o.detail.imageKey} className="art-small" />` vor dem Textblock (Container `opt-with-img` als flex).

- [ ] **Step 4: Styles und Vite-Proxy**

`styles.css` ergänzen/ändern:

```css
.card { position: relative; width: 96px; height: 134px; overflow: hidden; }
.card .art { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; border-radius: 4px; }
.card.has-img .pt, .card.has-img .counters { position: absolute; right: 3px; bottom: 2px; background: rgba(0,0,0,0.75); padding: 1px 4px; border-radius: 4px; }
.card.has-img .counters { left: 3px; right: auto; }
.card.tapped { transform: rotate(90deg); margin: 0 19px; }
.player.compact .card { width: 72px; height: 100px; }
.detail .art-large { width: 100%; max-width: 300px; border-radius: 10px; display: block; margin-bottom: 6px; }
.opt-with-img { display: flex; gap: 8px; align-items: flex-start; }
.opt-with-img .art-small { width: 60px; border-radius: 4px; flex: none; }
.pick { display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap; }
.textdeck { display: flex; flex-direction: column; gap: 4px; flex: 1; min-width: 320px; }
.textdeck textarea { font-family: ui-monospace, monospace; font-size: 12px; }
.import-error { white-space: pre-wrap; color: #ff8a8a; background: #2a1a1a; padding: 8px; border-radius: 6px; }
```

(Die bestehenden `.card`-Regeln für `min-height`/`padding` bleiben; `height` und `overflow` kommen dazu.)

`vite.config.ts`: `server: { port: 5173, proxy: { "/img": "http://localhost:8080" } }`.

- [ ] **Step 5: Tests und Build**

Run: `cd /home/kevin/projects/MTG-Player/web && npm test 2>&1 | tail -4 && npm run build 2>&1 | tail -3`
Expected: 18 Tests grün, Build sauber.

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add web/src web/vite.config.ts
git commit -m "m4: frontend – deck-import in der lobby, kartenbilder mit text-fallback

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Suiten, README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Gesamtsuiten**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test 2>&1 | tail -3 && cd ../web && npm test 2>&1 | tail -3 && npm run build 2>&1 | tail -1`
Expected: bridge `Tests run: 63, Failures: 0, Errors: 0` (43 + 5 + 3 + 5 + 3 + 4); web `18 passed`; Build sauber.

- [ ] **Step 2: README**

Abschnitt "Spielen": nach dem ersten Absatz ergänzen:

```markdown
Eigene Decks: in der Lobby "Textliste" wählen und einen Archidekt- oder Arena-Export einfügen
(`1 Sol Ring (c21) 263`, Sektion `Commander` oder erste legendäre Kreatur als Commander). Das Deck wird
unter `~/.mtg-player/decks/` gespeichert und erscheint danach unter "Eigenes Deck". Unbekannte Karten
werden mit Zeile gemeldet, das Spiel startet dann nicht.

Kartenbilder kommen von Scryfall und werden unter `~/.mtg-player/cache/images/` gecacht (erstes Spiel mit
neuen Karten lädt ein paar Sekunden nach; ohne Internet bleiben es Textboxen).
```

"Was noch fehlt" → `## Was noch fehlt (M5+)` mit `Spiel-Log im Browser, Spieler-Markierung als Ziel, Archidekt-URL (M6).`

- [ ] **Step 3: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add README.md
git commit -m "m4: readme – deck-import und kartenbilder

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Nach diesem Plan

Kevin importiert ein eigenes Deck und spielt mit Bildern. Offen für M5: Spiel-Log im Browser (`GameLog`-Observer → `log`-Nachrichten), Spieler als Ziel hervorheben; M6: Archidekt-URL (`https://archidekt.com/api/decks/{id}/` → Textliste → `DeckImport`).
