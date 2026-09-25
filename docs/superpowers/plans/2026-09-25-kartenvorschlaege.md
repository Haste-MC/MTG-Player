# Kartenvorschläge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zu den Schwächen eines Decks Karten vorschlagen, die sie schließen – mit Schnittkandidat und Begründung.

**Architecture:** Die Schwächenlogik bleibt im Client (`findings.ts`) und benennt Rollen; die Bridge kennt die Karten. Sie holt für den Commander die EDHREC-Seite (zwischengespeichert, mit Rückfall auf Forges Kartendatenbank), ordnet die Kandidaten mit **denselben** neun Regeln ein wie die Deckanalyse (`DeckAnalysis.categoriesOf`) und schlägt zu jedem Zugang einen Schnitt aus dem eigenen Deck vor.

**Tech Stack:** Java 21 (Records, `java.net.http`, Jackson, JUnit 5), Forge-Kartendatenbank (`StaticData`), React 18 + TypeScript, Zustand, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-25-kartenvorschlaege-design.md` – bei Widersprüchen gilt die Spec.

## Global Constraints

- **Niemals im Hauptbaum `/home/kevin/projects/MTG-Player` Maven laufen lassen oder kompilieren.** Kevins Bridge läuft dort als `mvn -q compile exec:java` aus `bridge/target/classes`; ein Neuübersetzen unter der laufenden JVM schießt seine Partie mit `NoSuchMethodError` ab. Java-Arbeit passiert im Worktree `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/kampf` (enthält bereits einen `forge`-Symlink auf den Hauptbaum und `bridge/assets/forge.profile.properties`); vor dem Arbeiten `git -C <worktree> checkout --detach <aktueller main-HEAD>`, danach im Worktree committen und im Hauptbaum `git cherry-pick <hash>` – dort nur Quelltext, kein Maven.
- Niemals `mvn clean`. Maven mit `timeout 590`, Tool-Timeout 600000 ms. Vor jedem Maven-Lauf prüfen: `ps -eo args= | grep -c '[c]lassworlds'` muss `1` oder `0` sein (die `1` ist Kevins Bridge).
- Kein `pkill`; Ports 8080/8081 gehören Kevins Bridge. Vite-Dev-Server für Screenshots auf **5199**, am Ende über die eigene PID beenden.
- Tests dürfen nie nach `~/.mtg-player` schreiben (surefire setzt `mtgplayer.data` auf `target/test-data`) und **nie ins Netz gehen**: der EDHREC-Abruf wird in Tests immer über eine eingesetzte Quelle ersetzt.
- Commits deutsch, klein geschrieben, Präfix `bridge:` / `ui:` / `docs:` / `test:`. Trailer exakt `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, kein weiterer Co-Author.
- Kommentare deutsch und erklären das Warum, wie im Nachbarcode.
- Nach außen geht nur der Commandername – keine Deckliste, keine Partiedaten, keine Kevin-Daten.
- Rollen sind genau die neun Namen aus `DeckAnalysis.rules()`: `ramp`, `draw`, `removal`, `wipes`, `counters`, `flyerDefense`, `wipeProtection`, `recursion`, `tutors`.

---

## Dateien

| Datei | Rolle |
|---|---|
| `bridge/src/main/java/mtgplayer/decks/Edhrec.java` | Slug, Abruf, Zwischenspeicher, Auswertung der EDHREC-Antwort |
| `bridge/src/test/java/mtgplayer/decks/EdhrecTest.java` | Tests dazu (ohne Netz) |
| `bridge/src/test/resources/edhrec-titania.json` | gekürzte echte Antwort als Prüfstein |
| `bridge/src/main/java/mtgplayer/decks/Suggestions.java` | Kandidaten, Rangfolge, Bracket, Schnitte, Rückfall |
| `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java` | Tests dazu |
| `bridge/src/main/java/mtgplayer/protocol/Messages.java` | Nachricht `cardSuggestions` |
| `bridge/src/main/java/mtgplayer/server/Bridge.java` | Behandlung von `suggestCards` |
| `web/src/findings.ts` | Rolle je Auffälligkeit, Richtwerte, `roleGaps` |
| `web/src/protocol.ts` | Typen `CardSuggestion`, `CardSuggestionsMsg` |
| `web/src/store.ts` | Ablage der Antwort je Deck |
| `web/src/components/Suggestions.tsx` | Anzeige im Statistik-Board |
| `web/src/components/Stats.tsx` | hängt den Abschnitt ein |
| `web/src/styles.css` | Styles |
| `web/fixtures/statboard-suggestions.json` | Fixture für den Screenshot |

---

### Task 1: EDHREC-Quelle

**Files:**
- Create: `bridge/src/main/java/mtgplayer/decks/Edhrec.java`
- Create: `bridge/src/test/java/mtgplayer/decks/EdhrecTest.java`
- Create: `bridge/src/test/resources/edhrec-titania.json`

**Interfaces:**
- Consumes: `mtgplayer.forge.ForgeBoot.dataDir()` für den Zwischenspeicher, Jackson (`mtgplayer.protocol.Json` zeigt, wie das Projekt Jackson benutzt).
- Produces:
  - `record Edhrec.Card(String name, double share, boolean gameChanger)`
  - `record Edhrec.Page(String slug, List<Card> cards, Instant fetched)` mit `Map<String, Card> byName()` (Schlüssel kleingeschrieben)
  - `static String slug(List<String> commanderNames)`
  - `static Page parse(String slug, String json, Instant fetched)`
  - `Edhrec(Function<String, String> source, Path cacheDir)` – `source` bekommt die URL und liefert den Text; im Betrieb der HTTP-Abruf, im Test eine eingesetzte Funktion
  - `Optional<Page> page(List<String> commanderNames)` – Zwischenspeicher, Abruf, Rückfall auf abgelaufenen Stand
  - `static final Duration MAX_AGE = Duration.ofDays(7)`

- [ ] **Step 1: Prüfstein anlegen**

Hol eine echte Antwort und kürze sie auf das, was der Test braucht:

```bash
cd /home/kevin/projects/MTG-Player
curl -s --max-time 20 "https://json.edhrec.com/pages/commanders/titania-protector-of-argoth.json" -o /tmp/edhrec-full.json
python3 - <<'PY'
import json
d = json.load(open('/tmp/edhrec-full.json'))
lists = d['container']['json_dict']['cardlists']
keep = [l for l in lists if l['header'] in ('Game Changers', 'High Synergy Cards', 'Creatures', 'Instants')]
for l in keep:
    l['cardviews'] = l['cardviews'][:8]
out = {'container': {'json_dict': {'cardlists': keep}}}
json.dump(out, open('bridge/src/test/resources/edhrec-titania.json', 'w'), indent=1)
print(sum(len(l['cardviews']) for l in keep), 'karten')
PY
```

Die Datei kommt so ins Repo. Sie enthält nur öffentliche Kartendaten (Name, Deckzahlen) – keine Nutzerdaten.

- [ ] **Step 2: Die fehlschlagenden Tests schreiben**

Datei `bridge/src/test/java/mtgplayer/decks/EdhrecTest.java`:

```java
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
```

- [ ] **Step 3: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=EdhrecTest -DfailIfNoSpecifiedTests=false test
```

Erwartet: Übersetzungsfehler „cannot find symbol: class Edhrec".

- [ ] **Step 4: `Edhrec.java` schreiben**

Aufbau (die Kommentare gehören in den Code, sie begründen die Entscheidungen der Spec):

```java
package mtgplayer.decks;

/** EDHREC als Quelle fuer Kartenvorschlaege (Spec 2026-09-25-kartenvorschlaege §3). Nach aussen geht nur
 *  der Commandername - keine Deckliste, keine Partiedaten. Jeder Fehlschlag endet in einem leeren
 *  Optional; der Aufrufer faellt dann auf Forges Kartendatenbank zurueck. */
public final class Edhrec {
    public static final Duration MAX_AGE = Duration.ofDays(7);
    private static final String BASE = "https://json.edhrec.com/pages/commanders/";

    public record Card(String name, double share, boolean gameChanger) { }
    public record Page(String slug, List<Card> cards, Instant fetched) {
        public Map<String, Card> byName() { /* kleingeschriebener Name -> Karte */ }
    }
    …
}
```

Verbindliche Einzelheiten:

- `slug(List<String> names)`: leere Liste → leerer String. Sonst: Namen nach `String::compareToIgnoreCase`
  sortieren, je Name kleinschreiben (`Locale.ROOT`), alles außer `a-z0-9` durch `-` ersetzen,
  Mehrfach-`-` zu einem zusammenziehen, führende/abschließende `-` entfernen, mit `-` verbinden.
- `parse(slug, json, fetched)`: Jackson liest `container.json_dict.cardlists`; je Eintrag `header` und
  `cardviews`. Eine Karte trägt `name`, `num_decks`, `potential_decks`; `share = num_decks /
  potential_decks` (bei `potential_decks <= 0`: `share = 0`). `gameChanger` ist wahr, wenn die Karte in der
  Liste mit `header` gleich `"Game Changers"` steht. Jede Karte kommt nur einmal in `cards`; taucht sie in
  mehreren Listen auf, gewinnt der höhere `share`, und `gameChanger` bleibt gesetzt, sobald es einmal
  gesetzt war. Fehlende Felder → Karte überspringen, nicht werfen.
- `page(names)`: leerer Slug → `Optional.empty()`. Sonst Datei `<cacheDir>/<slug>.json`:
  - existiert und jünger als `MAX_AGE` → daraus `parse` (mit der Dateizeit als `fetched`).
  - sonst Abruf über `source.apply(BASE + slug + ".json")`; Erfolg → Datei schreiben (erst in eine
    eindeutige Temp-Datei, dann `Files.move` mit `ATOMIC_MOVE`, wie `MatchStore` es macht) und `parse`.
  - Abruf wirft oder liefert unlesbares JSON → falls eine (abgelaufene) Datei da ist, die benutzen;
    sonst `Optional.empty()`.
  - Jede `IOException`/`RuntimeException` bleibt in der Methode; nach außen nur `Optional`.
- Der Betriebs-Konstruktor `public Edhrec()` benutzt `ForgeBoot.dataDir().resolve("edhrec")` und einen
  HTTP-Abruf mit denselben Werten wie `Archidekt` (10 s Verbindung, 20 s Antwort, `User-Agent`
  `MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)`, `Accept: application/json`); ein Antwortstatus
  ungleich 200 wirft, damit der Rückfall greift.

- [ ] **Step 5: Tests laufen lassen – jetzt grün**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=EdhrecTest -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit und Cherry-Pick**

```bash
cd <worktree> && git add -A bridge && git commit -m "bridge: edhrec-seite je commander holen und zwischenspeichern

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 2: Kandidaten, Rangfolge, Bracket

**Files:**
- Create: `bridge/src/main/java/mtgplayer/decks/Suggestions.java`
- Create: `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java`

**Interfaces:**
- Consumes: `Edhrec.Page`/`Edhrec.Card` aus Task 1; `DeckAnalysis.categoriesOf(CardRules)`; Forges `StaticData.instance().getCommonCards()`, `Deck`, `DeckSection`, `PaperCard`, `CardRules.getColorIdentity()`.
- Produces:
  - `record Suggestions.Cut(String name, String reason)`
  - `record Suggestions.Item(String name, String role, String manaCost, int cmc, Double share, boolean gameChanger, String imageKey, String text, Cut cut)`
  - `record Suggestions.Result(String source, String note, List<Item> items)`
  - `static Result of(Deck deck, List<String> roles, Integer bracket, Edhrec.Page page)` – `page` darf `null` sein (Rückfall in Task 3)
  - `static final int PER_ROLE = 5`, `static final int MAX_ITEMS = 15`

In diesem Task bleibt `cut` immer `null` und `page` immer gesetzt; Schnitte und Rückfall kommen in Task 3.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

Datei `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java`. Die Szenen bauen ein kleines Deck aus
echten Karten und eine `Edhrec.Page` von Hand – kein Netz, keine Datei:

```java
package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import mtgplayer.forge.ForgeBoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class SuggestionsTest {

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    private static PaperCard card(String name) {
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        assertNotNull(pc, "Karte nicht in der Datenbank: " + name);
        return pc;
    }

    /** Gruenes Commander-Deck mit Titania: ein Schutzzauber ist bereits drin, damit "schon im Deck" greift. */
    private static Deck deck(String... mainCards) {
        Deck d = new Deck("Test");
        d.getOrCreate(DeckSection.Commander).add(card("Titania, Protector of Argoth"));
        for (String n : mainCards) {
            d.getMain().add(card(n));
        }
        d.getMain().add(card("Forest"), 30);
        return d;
    }

    private static Edhrec.Page page(Edhrec.Card... cards) {
        return new Edhrec.Page("titania-protector-of-argoth", List.of(cards), Instant.now());
    }

    private static Edhrec.Card ec(String name, double share) { return new Edhrec.Card(name, share, false); }
    private static Edhrec.Card gc(String name, double share) { return new Edhrec.Card(name, share, true); }

    @Test
    void schlaegtKartenDerGefragtenRolleVor() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("wipeProtection"), 4,
                page(ec("Heroic Intervention", 0.68), ec("Llanowar Elves", 0.4)));
        assertEquals(List.of("Heroic Intervention"), r.items().stream().map(Suggestions.Item::name).toList());
        assertEquals("wipeProtection", r.items().get(0).role());
        assertEquals(0.68, r.items().get(0).share(), 1e-9);
        assertEquals("edhrec", r.source());
    }

    @Test
    void laesstWegWasSchonImDeckIst() {
        Suggestions.Result r = Suggestions.of(deck("Heroic Intervention"), List.of("wipeProtection"), 4,
                page(ec("Heroic Intervention", 0.68)));
        assertTrue(r.items().isEmpty(), r.items().toString());
    }

    @Test
    void laesstWegWasNichtZurFarbidentitaetPasst() {
        // Counterspell ist blau, Titania gruen - darf nicht vorgeschlagen werden.
        Suggestions.Result r = Suggestions.of(deck(), List.of("counters"), 4, page(ec("Counterspell", 0.5)));
        assertTrue(r.items().isEmpty(), r.items().toString());
    }

    @Test
    void laesstLaenderWeg() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, page(ec("Ancient Tomb", 0.6)));
        assertTrue(r.items().stream().noneMatch(i -> i.name().equals("Ancient Tomb")), r.items().toString());
    }

    @Test
    void sortiertNachAnteilDannKosten() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4,
                page(ec("Cultivate", 0.5), ec("Llanowar Elves", 0.5), ec("Rampant Growth", 0.7)));
        // Rampant Growth (0.7) vor den beiden mit 0.5; dort zuerst die billigere Karte.
        assertEquals(List.of("Rampant Growth", "Llanowar Elves", "Cultivate"),
                r.items().stream().map(Suggestions.Item::name).toList());
    }

    @Test
    void hoechstensFuenfJeRolle() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"),  4,
                page(ec("Llanowar Elves", 0.9), ec("Elvish Mystic", 0.8), ec("Fyndhorn Elves", 0.7),
                     ec("Rampant Growth", 0.6), ec("Cultivate", 0.5), ec("Kodama's Reach", 0.4)));
        assertEquals(5, r.items().size());
    }

    @Test
    void bracketZweiLaesstGameChangerWeg() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 2,
                page(gc("Crop Rotation", 0.8), ec("Llanowar Elves", 0.4)));
        assertEquals(List.of("Llanowar Elves"), r.items().stream().map(Suggestions.Item::name).toList());
    }

    @Test
    void bracketDreiNimmtGameChangerMitHinweis() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 3, page(gc("Crop Rotation", 0.8)));
        assertEquals(1, r.items().size());
        assertTrue(r.items().get(0).gameChanger());
        assertNotNull(r.note());
        assertTrue(r.note().contains("Bracket 3"), r.note());
    }

    @Test
    void ohneRollenNichts() {
        Suggestions.Result r = Suggestions.of(deck(), List.of(), 4, page(ec("Llanowar Elves", 0.4)));
        assertTrue(r.items().isEmpty());
        assertNotNull(r.note());
    }

    @Test
    void traegtAnzeigedatenMit() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, page(ec("Llanowar Elves", 0.4)));
        Suggestions.Item i = r.items().get(0);
        assertEquals(1, i.cmc());
        assertEquals("{G}", i.manaCost());
        assertFalse(i.imageKey().isBlank());
        assertFalse(i.text().isBlank());
    }
}
```

`Crop Rotation` ist grün und ein Land-Tutor (Regel `ramp` über „search your library for … land"); prüfe beim
Schreiben mit einem kurzen Testlauf, dass die Karte die erwartete Rolle wirklich trifft, und tausche sie
sonst gegen eine andere grüne Game-Changer-Karte derselben Rolle (`DeckAnalysis.categoriesOf` sagt es dir).

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=SuggestionsTest -DfailIfNoSpecifiedTests=false test
```

Erwartet: „cannot find symbol: class Suggestions".

- [ ] **Step 3: `Suggestions.java` schreiben**

```java
package mtgplayer.decks;

/** Kartenvorschlaege zu den Luecken eines Decks (Spec 2026-09-25-kartenvorschlaege §4/§5).
 *  Kandidaten kommen aus der EDHREC-Seite des Commanders und werden mit denselben Regeln eingeordnet wie
 *  das Deck selbst (DeckAnalysis.categoriesOf) - Luecke und Vorschlag sprechen so dieselbe Sprache. */
public final class Suggestions {
    public static final int PER_ROLE = 5;
    public static final int MAX_ITEMS = 15;
    …
}
```

Verbindliche Einzelheiten:

- Farbidentität: Vereinigung der `getColorIdentity()` aller Karten in `DeckSection.Commander`; ohne
  Commander gilt keine Einschränkung. Ein Kandidat passt, wenn seine Farbidentität darin enthalten ist
  (`ColorSet.hasNoColorsExcept`).
- „Schon im Deck": Namensvergleich (kleingeschrieben) gegen Haupt- **und** Commandersektion.
- Land: `pc.getRules().getType().isLand()`.
- Rolle: `DeckAnalysis.categoriesOf(pc.getRules()).contains(role)`.
- Unbekannte Karte (`getCommonCards().getCard(name) == null`) → überspringen.
- Rangfolge je Rolle: `share` absteigend, dann `getRules().getManaCost().getCMC()` aufsteigend, dann Name.
- Eine Karte erscheint höchstens einmal in der ganzen Antwort, auch wenn sie zwei Rollen trifft
  (erste angefragte Rolle gewinnt).
- Bracket: `null`, 4 oder 5 → keine Einschränkung; 1 oder 2 → Game Changer raus; 3 → mitnehmen und
  `note` auf `"Game Changer sind in Bracket 3 auf drei Karten begrenzt."` setzen (nur wenn wirklich einer
  dabei ist).
- Leere Rollenliste → leere Liste und `note = "Keine Lücke gefunden."`.
- `imageKey` = `pc.getImageKey(false)`, `text` = `pc.getRules().getOracleText()`,
  `manaCost` = `pc.getRules().getManaCost().getShortString()`.
- `source` ist in diesem Task immer `"edhrec"`.

- [ ] **Step 4: Tests laufen lassen – jetzt grün**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=SuggestionsTest -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit und Cherry-Pick**

```bash
cd <worktree> && git add -A bridge && git commit -m "bridge: kandidaten je rolle aus der edhrec-seite waehlen

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 3: Schnittkandidaten und Rückfall ohne EDHREC

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/decks/Suggestions.java`
- Modify: `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java`

**Interfaces:**
- Consumes: alles aus Task 2.
- Produces: `Item.cut()` ist gefüllt, wo ein Kandidat existiert; `Result.source()` ist `"db"`, wenn `page == null`.

- [ ] **Step 1: Die fehlschlagenden Tests ergänzen**

```java
    @Test
    void schlaegtEinenSchnittMitBegruendungVor() {
        // Overrun ist gruen, teuer und trifft dieselbe Rolle wie der Vorschlag (Kampftrick/Pump).
        Suggestions.Result r = Suggestions.of(deck("Overrun"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.4)));
        Suggestions.Cut cut = r.items().get(0).cut();
        assertNotNull(cut, "ohne Schnittkandidat: " + r.items());
        assertEquals("Overrun", cut.name());
        assertFalse(cut.reason().isBlank());
    }

    @Test
    void schneidetJedeKarteHoechstensEinmal() {
        Suggestions.Result r = Suggestions.of(deck("Cultivate"), List.of("ramp"), 4,
                page(ec("Llanowar Elves", 0.9), ec("Rampant Growth", 0.8)));
        List<String> cuts = r.items().stream().map(i -> i.cut() == null ? null : i.cut().name()).toList();
        assertEquals(1, cuts.stream().filter(java.util.Objects::nonNull).distinct().count(), cuts.toString());
        assertTrue(cuts.contains(null), "der zweite Vorschlag haette keinen Schnitt mehr: " + cuts);
    }

    @Test
    void schneidetNieLandOderCommander() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, page(ec("Llanowar Elves", 0.9)));
        Suggestions.Cut cut = r.items().get(0).cut();
        if (cut != null) {
            assertFalse(cut.name().equals("Forest") || cut.name().equals("Titania, Protector of Argoth"),
                    "Land oder Commander als Schnitt: " + cut);
        }
    }

    @Test
    void ohneEdhrecKommenVorschlaegeAusDerDatenbank() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("wipeProtection"), 4, null);
        assertEquals("db", r.source());
        assertFalse(r.items().isEmpty(), "auch ohne EDHREC muss die Datenbank etwas liefern");
        assertTrue(r.items().stream().allMatch(i -> i.share() == null), "ohne EDHREC gibt es keinen Anteil");
        assertNotNull(r.note());
        // Farbidentitaet gilt auch hier.
        assertTrue(r.items().stream().noneMatch(i -> i.name().equals("Teferi's Protection")));
    }

    @Test
    void datenbankRueckfallSortiertNachKosten() {
        Suggestions.Result r = Suggestions.of(deck(), List.of("ramp"), 4, null);
        List<Integer> cmcs = r.items().stream().map(Suggestions.Item::cmc).toList();
        assertEquals(cmcs.stream().sorted().toList(), cmcs, cmcs.toString());
    }
```

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=SuggestionsTest -DfailIfNoSpecifiedTests=false test
```

Erwartet: Fehlschläge in genau den fünf neuen Tests.

- [ ] **Step 3: Schnittkandidaten umsetzen**

Regeln (Spec §7), als eigene Methode mit einem Merkzettel der schon vergebenen Namen:

1. Kandidaten: Karten der Hauptsektion mit derselben Rolle, ohne Länder, ohne Commander, noch nicht vergeben.
2. Rangfolge mit EDHREC: zuerst Karten, die die Seite gar nicht führt, dann aufsteigender `share`, dann
   absteigende Manakosten, dann Name. Ohne EDHREC: absteigende Manakosten, dann Name.
3. Leer → dieselbe Rangfolge über die Karten, die **keine** Rolle treffen (`categoriesOf` leer).
4. Immer noch leer → `cut` bleibt `null`.
5. Begründung nennt nur Geprüftes, zusammengesetzt aus: `"führt EDHREC für diesen Commander gar nicht"`,
   `"spielt in vergleichbaren Decks fast niemand (4 %)"` (Anteil auf ganze Prozent gerundet),
   `"ist mit {2}{G}{G}{G} die teuerste Karte dieser Rolle"` (nur, wenn es wirklich die teuerste der
   Kandidatenliste ist), `"trifft keine der neun Rollen"` für den Fall aus 3.

- [ ] **Step 4: Rückfall ohne EDHREC umsetzen**

Ist `page == null`, kommen die Kandidaten aus `FModel.getMagicDb().getCommonCards().getUniqueCards()`:
Rolle getroffen, Farbidentität passt, kein Land, nicht im Deck, Commander-legal
(`DeckFormat.Commander.isLegalCard(pc)`). Rangfolge: Manakosten aufsteigend, dann Name. `share` bleibt
`null`, `source` ist `"db"`, `note` erklärt den Grund (Text übergibt der Aufrufer nicht – `Suggestions`
setzt `"Ohne EDHREC-Daten: Vorschläge nur aus der Kartendatenbank."`). Obergrenzen wie sonst.

Der Durchlauf über alle Karten ist der teuerste Teil; er passiert nur im Rückfall und läuft im
Hintergrund-Task (Task 4).

- [ ] **Step 5: Tests laufen lassen – jetzt grün**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest=SuggestionsTest -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: `Tests run: 15, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit und Cherry-Pick**

```bash
cd <worktree> && git add -A bridge && git commit -m "bridge: schnittkandidat je vorschlag und rueckfall ohne edhrec

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 4: Protokoll und Bridge-Anbindung

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java`
- Modify: `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Modify: `bridge/src/test/java/mtgplayer/decks/SuggestionsTest.java` (nur falls eine Hilfsmethode wandert)

**Interfaces:**
- Consumes: `Suggestions.of(...)`, `Edhrec.page(...)`, `DeckStore.bracket(name)`, die vorhandene Methode `forAnalysis(String name)` in `Bridge.java`.
- Produces: Nachricht `{"type":"cardSuggestions","deck":…,"source":…,"note":…,"suggestions":[…]}`.

- [ ] **Step 1: Nachricht ergänzen**

In `Messages.java` neben `DeckAnalysisMsg`:

```java
    /** Antwort auf {"type":"suggestCards"} (Spec 2026-09-25-kartenvorschlaege §2). */
    public record CardSuggestionsMsg(String type, String deck, String source, String note,
                                     List<Suggestions.Item> suggestions) {
        public CardSuggestionsMsg(String deck, Suggestions.Result r) {
            this("cardSuggestions", deck, r.source(), r.note(), r.items());
        }
    }
```

- [ ] **Step 2: Behandlung in `Bridge.java`**

Neben `case "analyzeDeck"`:

```java
            case "suggestCards" -> suggestCards(msg.path("deck").asText(), roles(msg.path("roles")));
```

mit einer kleinen Hilfsmethode, die aus dem JSON-Array eine `List<String>` macht (unbekannte Rollennamen
fallen weg – der Client darf nichts erfinden), und:

```java
    /**
     * {"type":"suggestCards","deck":"&lt;Name&gt;","roles":[…]} → {@link Messages.CardSuggestionsMsg} oder
     * error "Kartenvorschläge &lt;name&gt;: unbekanntes Deck". Hintergrund-Task wie analyzeDeck: der
     * EDHREC-Abruf und der Rückfall über die ganze Kartendatenbank haben auf dem UI-Thread nichts verloren.
     */
    private void suggestCards(String name, List<String> roles) {
        GuiBase.getInterface().runBackgroundTask("suggest-cards", () -> {
            try {
                Deck deck = forAnalysis(name);
                if (deck == null) {
                    ws.send(new Messages.ErrorMsg("Kartenvorschläge " + name + ": unbekanntes Deck"));
                    return;
                }
                Edhrec.Page page = edhrec.page(commanderNames(deck)).orElse(null);
                ws.send(new Messages.CardSuggestionsMsg(name,
                        Suggestions.of(deck, roles, decks.bracket(name), page)));
            } catch (RuntimeException e) {
                e.printStackTrace();
                ws.send(new Messages.ErrorMsg("Kartenvorschläge " + name + ": " + e));
            }
        });
    }
```

`edhrec` ist ein Feld (`private final Edhrec edhrec = new Edhrec();`), `commanderNames(deck)` liest die
Namen aus `DeckSection.Commander`. Den Namen des vorhandenen Deck-Speichers (`decks`) und die genaue
Signatur von `bracket(...)` im Nachbarcode nachsehen.

- [ ] **Step 3: Übersetzen und die Protokolltests laufen lassen**

```bash
cd <worktree>/bridge && timeout 590 mvn -q -Dtest='mtgplayer.decks.*Test,mtgplayer.protocol.*Test,mtgplayer.server.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: keine Fehler.

- [ ] **Step 4: Commit und Cherry-Pick**

```bash
cd <worktree> && git add -A bridge && git commit -m "bridge: nachricht suggestCards beantwortet kartenvorschlaege

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
cd /home/kevin/projects/MTG-Player && git cherry-pick $(git -C <worktree> rev-parse HEAD)
```

---

### Task 5: Rollen und Lücken im Client

**Files:**
- Modify: `web/src/findings.ts`
- Modify: `web/src/findings.test.ts`
- Modify: `web/src/protocol.ts`
- Modify: `web/src/store.ts`

**Interfaces:**
- Consumes: `DeckAnalysis` (TypeScript-Typ in `protocol.ts`), die vorhandene `findings(...)`-Funktion.
- Produces:
  - `Finding.role?: Role` mit `type Role = "ramp" | "draw" | "removal" | "wipes" | "counters" | "flyerDefense" | "wipeProtection" | "recursion" | "tutors"`
  - `export const ROLE_TARGETS: Partial<Record<Role, number>>`
  - `export function roleGaps(deck: DeckAnalysis | undefined, found: Finding[]): Role[]`
  - `CardSuggestion`/`CardSuggestionsMsg` in `protocol.ts`, Ablage `suggestions: Record<string, CardSuggestionsMsg>` im Store

Alles im Hauptbaum unter `web/`, kein Java.

- [ ] **Step 1: Die fehlschlagenden Tests schreiben**

In `web/src/findings.test.ts` ergänzen (die vorhandenen Hilfsfunktionen der Datei benutzen):

```ts
describe("roleGaps", () => {
  it("nimmt zuerst die Rollen aus den Auffaelligkeiten", () => {
    const found: Finding[] = [
      { level: "warn", title: "Massenentfernung", text: "", role: "wipeProtection" },
      { level: "warn", title: "Flieger", text: "", role: "flyerDefense" },
    ];
    expect(roleGaps(analysis({ ramp: 0 }), found)).toEqual(["wipeProtection", "flyerDefense", "ramp"]);
  });

  it("fuellt aus den Richtwerten auf, ohne zu doppeln", () => {
    const found: Finding[] = [{ level: "warn", title: "Mana-Screw", text: "", role: "ramp" }];
    const gaps = roleGaps(analysis({ ramp: 0, draw: 0 }), found);
    expect(gaps[0]).toBe("ramp");
    expect(gaps.filter((r) => r === "ramp")).toHaveLength(1);
    expect(gaps).toContain("draw");
  });

  it("nennt hoechstens drei Rollen", () => {
    expect(roleGaps(analysis({ ramp: 0, draw: 0, removal: 0, wipes: 0 }), []).length).toBeLessThanOrEqual(3);
  });

  it("ist leer, wenn das Deck alle Richtwerte erfuellt und nichts auffaellt", () => {
    expect(roleGaps(analysis({ ramp: 12, draw: 10, removal: 10, wipes: 3, flyerDefense: 6, wipeProtection: 3 }), []))
      .toEqual([]);
  });

  it("kommt ohne Deckanalyse aus", () => {
    const found: Finding[] = [{ level: "warn", title: "Flieger", text: "", role: "flyerDefense" }];
    expect(roleGaps(undefined, found)).toEqual(["flyerDefense"]);
  });
});
```

`analysis(...)` ist ein kleiner Helfer, den du in der Testdatei anlegst: eine `DeckAnalysis` mit allen neun
Kategorien auf einem hohen Wert und den übergebenen Überschreibungen – so prüft jeder Test genau die Rolle,
um die es ihm geht. Ergänze außerdem je einen Test, der für „Massenentfernung", „Flieger",
„Gekonterte Zauber", „Mana-Screw", „Landflut" und „Früh raus" die erwartete `role` am Befund prüft und für
„Viele Mulligans" und „Zugdeckel" `undefined`.

- [ ] **Step 2: Tests laufen lassen – sie müssen scheitern**

```bash
cd /home/kevin/projects/MTG-Player/web && npx vitest run src/findings.test.ts
```

Erwartet: `roleGaps is not a function` bzw. Typfehler.

- [ ] **Step 3: `findings.ts` erweitern**

```ts
export type Role = "ramp" | "draw" | "removal" | "wipes" | "counters"
  | "flyerDefense" | "wipeProtection" | "recursion" | "tutors";

/** Richtwerte fuer den Deckcheck ohne Partien (Spec §1). counters/recursion/tutors haben bewusst keinen:
 *  sie sind Deckabsicht, kein Mangel - ein blaues Deck ohne Gegenzauber ist eine Entscheidung. */
export const ROLE_TARGETS: Partial<Record<Role, number>> = {
  ramp: 10, draw: 8, removal: 8, wipes: 2, flyerDefense: 4, wipeProtection: 2,
};

export const MAX_GAPS = 3;
```

`Finding` bekommt `role?: Role`; die sechs Befunde aus der Spec tragen sie (Mana-Screw → `ramp`,
Landflut → `draw`, Massenentfernung → `wipeProtection`, Flieger → `flyerDefense`,
Gekonterte Zauber → `counters`, Früh raus → `removal`).

`roleGaps(deck, found)`: erst die Rollen der Befunde in ihrer Reihenfolge, dann die Rollen, deren Zahl in
`deck.categories` unter dem Richtwert liegt – sortiert nach der größten Unterschreitung (`target - ist`),
bei Gleichstand in der Reihenfolge von `ROLE_TARGETS`. Ohne Deckanalyse nur die Rollen der Befunde. Keine
Dopplungen, höchstens `MAX_GAPS`.

- [ ] **Step 4: Protokoll und Store**

`protocol.ts`:

```ts
export interface CardCut { name: string; reason: string }
export interface CardSuggestion {
  name: string; role: string; manaCost?: string; cmc: number;
  /** Anteil der vergleichbaren Decks (0..1); fehlt ohne EDHREC-Daten. */
  share?: number;
  gameChanger?: boolean; imageKey?: string; text?: string; cut?: CardCut;
}
export interface CardSuggestionsMsg {
  type: "cardSuggestions"; deck: string; source: "edhrec" | "db"; note?: string;
  suggestions: CardSuggestion[];
}
```

`store.ts`: `suggestions: Record<string, CardSuggestionsMsg>` plus die Behandlung der Nachricht
(nach dem Muster von `deckAnalysis`), Schlüssel ist der Deckname.

- [ ] **Step 5: Tests und Typen prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

Erwartet: keine Typfehler, alle Suiten grün.

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player && git add web/src/findings.ts web/src/findings.test.ts web/src/protocol.ts web/src/store.ts
git commit -m "ui: auffaelligkeiten tragen ihre rolle, luecken je deck bestimmen

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: Anzeige im Statistik-Board

**Files:**
- Create: `web/src/components/Suggestions.tsx`
- Modify: `web/src/components/Stats.tsx`
- Modify: `web/src/findings.ts` (Platzhalter `SUGGESTIONS_HINT` entfernen)
- Modify: `web/src/components/Findings.tsx` (falls der Platzhalter dort steht)
- Modify: `web/src/styles.css`
- Create: `web/fixtures/statboard-suggestions.json`

**Interfaces:**
- Consumes: `roleGaps`, `CardSuggestionsMsg` aus Task 5; `send` aus `ws.ts`; `CardImage`.
- Produces: Komponente `Suggestions` (Default-Export, Props `{ deck: string; analysis?: DeckAnalysis; found: Finding[] }`).

- [ ] **Step 1: Komponente schreiben**

Verhalten (Spec §8):

- Kein Abruf beim Öffnen. Der Abschnitt zeigt zuerst einen Knopf „Vorschläge laden"; erst der Klick
  schickt `{ type: "suggestCards", deck, roles: roleGaps(analysis, found) }`. Während der Abruf läuft, ist
  der Knopf gesperrt und trägt „lädt …". Kommt eine Antwort für dieses Deck in den Store, wird sie gezeigt.
- Überschrift „Kartenvorschläge" mit Zähler, darunter je Rolle eine Gruppe. Deutsche Rollennamen:
  `ramp` → „Ramp", `draw` → „Kartenzug", `removal` → „Entfernung", `wipes` → „Massenentfernung",
  `counters` → „Gegenzauber", `flyerDefense` → „Antwort auf Flieger",
  `wipeProtection` → „Schutz vor Massenentfernung", `recursion` → „Rückholer", `tutors` → „Suchkarten".
- Je Vorschlag: Kartenbild (`CardImage`, `key={imageKey}`), Name, Manakosten, bei vorhandenem `share`
  „68 % der vergleichbaren Decks spielen sie", Marke „Game Changer" wo gesetzt, rechts
  „raus: <Name> – <Grund>", wenn `cut` da ist.
- Herkunftszeile: bei `source: "edhrec"` „Quelle: EDHREC", bei `"db"` „Quelle: Kartendatenbank"; `note`
  steht als eigener Satz daneben, wenn vorhanden.
- Ein fester Satz zur Datenweitergabe am Abschnitt: „Für die Vorschläge fragt die App EDHREC nach deinem
  Commander – übertragen wird nur sein Name."
- Leere Rollenliste: kein Knopf, stattdessen „Keine Lücke gefunden."

- [ ] **Step 2: In `Stats.tsx` einhängen und den Platzhalter entfernen**

Der Abschnitt steht direkt unter den Auffälligkeiten desselben Decks und bekommt dieselben Daten, aus
denen die Auffälligkeiten berechnet werden. Die Zeile `SUGGESTIONS_HINT` („Kartenvorschläge folgen.")
entfällt samt Konstante; entferne auch ihren Test in `findings.test.ts`, falls es einen gibt.

- [ ] **Step 3: Styles ergänzen**

Ans Ende von `web/src/styles.css`, im Stil der vorhandenen Statboard-Regeln (`.findings`, `.stat-tiles`):
eine Gruppe je Rolle, Zeilen mit Miniatur links (wie `.combat-card .stack-thumb`, 34×48), Name und Kosten
daneben, Schnitt rechts in `var(--muted)`, Marke „Game Changer" in `var(--gold)`. Keine neue Farbvariable
anlegen; vorhandene aus `:root` benutzen.

- [ ] **Step 4: Fixture und Screenshot**

`web/fixtures/statboard-suggestions.json` enthält eine `cardSuggestions`-Nachricht mit mindestens zwei
Rollen, einem Vorschlag mit `share` und Schnitt, einem Game Changer und einem Vorschlag ohne `cut`.
Kartennamen und `imageKey` von echten Karten nehmen (z. B. `c:Heroic Intervention`-Schreibweise aus einem
bestehenden Fixture abschauen), damit die Bilder laden.

```bash
cd /home/kevin/projects/MTG-Player/web && (npx vite --port 5199 --strictPort > /tmp/vite-5199.log 2>&1 &) && sleep 5
node scripts/shot-stats.mjs http://localhost:5199 /tmp/vorschlaege.png fixtures/statboard.json fixtures/statboard-suggestions.json
```

Das Bild mit dem Read-Tool **ansehen**: Gruppen, Bilder, Anteil, Schnittzeile, Herkunft, Datensatz-Hinweis;
kein Überlauf, keine abgeschnittenen Begründungen. Danach den Vite-Prozess über seine PID beenden.
Schau vorher in `scripts/shot-stats.mjs`, wie der Aufruf dort genau aussieht, und passe ihn an.

- [ ] **Step 5: Typen und Tests prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player && git add web/src/components/Suggestions.tsx web/src/components/Stats.tsx web/src/components/Findings.tsx web/src/findings.ts web/src/findings.test.ts web/src/styles.css web/fixtures/statboard-suggestions.json
git commit -m "ui: kartenvorschlaege im statistik-board

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Abschluss

- Ledger-Zeile in `.superpowers/sdd/progress.md`, dann `git push`.
- Kevin braucht für den Bridge-Teil einen Neustart seiner Bridge.
