# M6: Archidekt-URL – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In der Lobby eine Archidekt-Deck-URL (oder Deck-ID) einfügen; die Bridge holt das Deck über Archidekts JSON-API, baut daraus eine Textliste und importiert sie wie ein Text-Deck (Speicherung unter dem Archidekt-Namen).

**Architecture:** Neue Klasse `decks/Archidekt` mit reiner Übersetzung `JSON → Textliste` (testbar mit einer gespeicherten Beispielantwort) und austauschbarem `Fetcher` (kein Netz in Unit-Tests). `DeckSource` bekommt die vierte Form `{archidekt: "<url|id>", deckName?}` → `Archidekt.fetch` → `DeckImport.parse` → speichern. Frontend: Picker-Art "Archidekt" mit URL-Feld.

**Tech Stack:** wie M5; `java.net.http.HttpClient` (wie `ImageCache`).

## Global Constraints

- Forge-Submodule bleibt unverändert; Protokoll abwärtskompatibel.
- Archidekt-API: `GET https://archidekt.com/api/decks/{id}/` (JSON, `User-Agent` `MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)`, Timeout 20 s). Verifizierte Struktur (2026-09-19, Deck 1): `name`; `categories[] {name, includedInDeck, isPremier}`; `cards[] {quantity, categories: [String]|null, card: {oracleCard: {name}, edition: {editioncode}, collectorNumber}}`.
- Übersetzungsregeln: Karten, deren **erste** Kategorie eine Kategorie mit `includedInDeck=false` ist (Maybeboard u. ä.), werden weggelassen; Karten mit einer Kategorie, deren `isPremier=true` (Commander), landen in der Sektion `Commander`; Kategorie `Sideboard` → Sektion `Sideboard`; alles andere `Main`. Zeilenformat `N Name (SET) NUM`; `SET` klein, `NUM` wie geliefert; fehlt Set oder Nummer → nur `N Name`. Der Deckname wird als `deckName` verwendet, wenn der Nutzer keinen angibt.
- URL-Formen: `https://archidekt.com/decks/12345/irgendwas`, `https://www.archidekt.com/decks/12345`, `https://archidekt.com/api/decks/12345/`, oder nur `12345`. Alles andere → `IllegalArgumentException("Keine Archidekt-Deck-URL: …")`.
- Netzfehler/HTTP ≠ 200 → `IllegalArgumentException("Archidekt: <Status oder Fehler>")` → der Browser zeigt es als `error`, kein Spielstart.
- Commit-Messages: deutsch, Kleinschreibung, Präfix `m6:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` (verbatim, unabhängig vom Modell).
- Maven aus `bridge/` (Timeout 600000 ms, nie zwei Prozesse, Nutzer-Bridge auf 8080/8081 in Ruhe lassen); Web: `npm test`, `npm run build`, `npm run layout-check` grün.

---

### Task 1: Bridge – `Archidekt` und `DeckSource`-Form

**Files:**
- Create: `bridge/src/main/java/mtgplayer/decks/Archidekt.java`
- Modify: `bridge/src/main/java/mtgplayer/decks/DeckSource.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java` (Javadoc der Formen)
- Test: `bridge/src/test/java/mtgplayer/decks/ArchidektTest.java`, `bridge/src/test/java/mtgplayer/decks/DeckSourceTest.java`; Ressource `bridge/src/test/resources/archidekt-1.json` (liegt bereits vor: Deck 1 "Fun With Fungus", 17 Karten, Commander Thelon of Havenwood, eine DFC "Westvale Abbey // Ormendahl, Profane Prince")

**Interfaces:**
- Produces: `Archidekt.Fetcher { String get(String url) throws IOException; }`; `Archidekt(Fetcher)`; `static Archidekt standard()`; `static long parseDeckId(String urlOrId)`; `static Result toTextList(JsonNode deckJson)` mit `record Result(String name, String text)`; `Result fetch(String urlOrId)`.
- `DeckSource(DeckStore store, Archidekt archidekt)` (bestehender 1-Arg-Konstruktor bleibt und nutzt `Archidekt.standard()`); neue Form `{archidekt, deckName?}`.

- [ ] **Step 1: Failing Tests**

`bridge/src/test/java/mtgplayer/decks/ArchidektTest.java`:

```java
package mtgplayer.decks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ArchidektTest {

    private static JsonNode sample() throws IOException {
        return Json.parse(Files.readString(Path.of("src/test/resources/archidekt-1.json"), StandardCharsets.UTF_8));
    }

    @Test
    void deckIdAusUrlFormen() {
        assertEquals(12345L, Archidekt.parseDeckId("https://archidekt.com/decks/12345/mein-deck"));
        assertEquals(12345L, Archidekt.parseDeckId("https://www.archidekt.com/decks/12345"));
        assertEquals(12345L, Archidekt.parseDeckId("https://archidekt.com/api/decks/12345/"));
        assertEquals(12345L, Archidekt.parseDeckId(" 12345 "));
        assertThrows(IllegalArgumentException.class, () -> Archidekt.parseDeckId("https://moxfield.com/decks/abc"));
        assertThrows(IllegalArgumentException.class, () -> Archidekt.parseDeckId(""));
    }

    @Test
    void beispielDeckWirdZurTextliste() throws IOException {
        Archidekt.Result r = Archidekt.toTextList(sample());
        assertEquals("Fun With Fungus", r.name());
        List<String> lines = r.text().lines().toList();
        assertEquals("Commander", lines.get(0));
        assertEquals("1 Thelon of Havenwood (tsp) 227", lines.get(1));
        assertTrue(lines.contains("Main"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("1 Westvale Abbey // Ormendahl, Profane Prince (")));
        assertEquals(17, lines.stream().filter(l -> Character.isDigit(l.charAt(0))).mapToInt(l -> Integer.parseInt(l.split(" ")[0])).sum());
    }

    @Test
    void maybeboardWirdWeggelassenUndSideboardErkannt() {
        String json = """
            {"name":"T","categories":[
               {"name":"Commander","includedInDeck":true,"isPremier":true},
               {"name":"Maybeboard","includedInDeck":false,"isPremier":false},
               {"name":"Sideboard","includedInDeck":true,"isPremier":false}],
             "cards":[
               {"quantity":1,"categories":["Commander"],"card":{"oracleCard":{"name":"A"},"edition":{"editioncode":"xyz"},"collectorNumber":"1"}},
               {"quantity":2,"categories":["Maybeboard"],"card":{"oracleCard":{"name":"B"},"edition":{"editioncode":"xyz"},"collectorNumber":"2"}},
               {"quantity":3,"categories":["Sideboard"],"card":{"oracleCard":{"name":"C"},"edition":{"editioncode":"xyz"},"collectorNumber":"3"}},
               {"quantity":4,"categories":null,"card":{"oracleCard":{"name":"D"},"edition":{},"collectorNumber":null}}]}
            """;
        Archidekt.Result r = Archidekt.toTextList(Json.parse(json));
        String t = r.text();
        assertTrue(t.contains("Commander\n1 A (xyz) 1"));
        assertFalse(t.contains("B"));
        assertTrue(t.contains("Sideboard\n3 C (xyz) 3"));
        assertTrue(t.contains("Main\n4 D\n") || t.contains("Main\n4 D"));
    }

    @Test
    void fetchNutztDenFetcherUndMeldetFehler() throws IOException {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"), StandardCharsets.UTF_8);
        List<String> urls = new java.util.ArrayList<>();
        Archidekt a = new Archidekt(url -> { urls.add(url); return body; });
        Archidekt.Result r = a.fetch("https://archidekt.com/decks/1/fun-with-fungus");
        assertEquals(List.of("https://archidekt.com/api/decks/1/"), urls);
        assertEquals("Fun With Fungus", r.name());

        Archidekt broken = new Archidekt(url -> { throw new IOException("HTTP 404"); });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> broken.fetch("1"));
        assertTrue(e.getMessage().contains("404"));
    }
}
```

`DeckSourceTest` ergänzen (bestehende Tests unverändert, sie nutzen den 1-Arg-Konstruktor):

```java
    @Test
    void archidektFormHoltParstUndSpeichert(@TempDir Path dir) throws Exception {
        String body = java.nio.file.Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        DeckSource.Resolved r = src.resolve(Json.parse("{\"archidekt\":\"https://archidekt.com/decks/1/x\"}"));
        r.save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        assertEquals("Thelon of Havenwood", r.deck().getCommanders().get(0).getName());
    }

    @Test
    void archidektFehlerWirdGemeldet(@TempDir Path dir) {
        DeckSource src = new DeckSource(new DeckStore(dir), new Archidekt(url -> { throw new java.io.IOException("HTTP 500"); }));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> src.resolve(Json.parse("{\"archidekt\":\"42\"}")));
        assertTrue(e.getMessage().contains("Archidekt"));
    }
```

(Hinweis: `DeckSource.resolve` liefert seit M4 ein `Resolved(Deck deck, Runnable save)` – Namen der Accessoren in `DeckSource.java` nachlesen und im Test anpassen, falls sie abweichen.)

- [ ] **Step 2: Tests laufen lassen, müssen fehlschlagen**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='ArchidektTest,DeckSourceTest'` → COMPILATION ERROR.

- [ ] **Step 3: Archidekt schreiben**

`bridge/src/main/java/mtgplayer/decks/Archidekt.java`:

```java
package mtgplayer.decks;

import com.fasterxml.jackson.databind.JsonNode;
import mtgplayer.protocol.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Archidekt-Deck (URL oder ID) → Textliste für {@link DeckImport}. Die JSON-Übersetzung ist rein;
 * der Fetcher ist austauschbar (kein Netz in Tests).
 */
public final class Archidekt {

    public interface Fetcher {
        /** @return Antwort-Body bei HTTP 200; sonst IOException mit Status/Grund */
        String get(String url) throws IOException;
    }

    public record Result(String name, String text) { }

    private static final Pattern ID_IN_URL = Pattern.compile("archidekt\\.com/(?:api/)?decks/(\\d+)");
    private static final Pattern BARE_ID = Pattern.compile("^\\d+$");

    private final Fetcher fetcher;

    public Archidekt(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    public static Archidekt standard() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new Archidekt(url -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "MTG-Player/0.1 (github.com/Haste-MC/MTG-Player)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            try {
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    throw new IOException("HTTP " + res.statusCode());
                }
                return res.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("unterbrochen", e);
            }
        });
    }

    public static long parseDeckId(String urlOrId) {
        String s = urlOrId == null ? "" : urlOrId.trim();
        if (BARE_ID.matcher(s).matches()) {
            return Long.parseLong(s);
        }
        Matcher m = ID_IN_URL.matcher(s);
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        throw new IllegalArgumentException("Keine Archidekt-Deck-URL: " + urlOrId);
    }

    public Result fetch(String urlOrId) {
        long id = parseDeckId(urlOrId);
        String url = "https://archidekt.com/api/decks/" + id + "/";
        String body;
        try {
            body = fetcher.get(url);
        } catch (IOException e) {
            throw new IllegalArgumentException("Archidekt: " + e.getMessage(), e);
        }
        try {
            return toTextList(Json.parse(body));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Archidekt: Antwort nicht lesbar (" + e.getMessage() + ")", e);
        }
    }

    /** Reine Übersetzung der API-Antwort. Regeln siehe Plan (includedInDeck, isPremier, Sideboard). */
    public static Result toTextList(JsonNode deck) {
        Set<String> excluded = new HashSet<>();
        Set<String> commanderCats = new HashSet<>();
        for (JsonNode cat : deck.path("categories")) {
            String name = cat.path("name").asText("");
            if (!cat.path("includedInDeck").asBoolean(true)) excluded.add(name);
            if (cat.path("isPremier").asBoolean(false)) commanderCats.add(name);
        }
        List<String> commander = new ArrayList<>();
        List<String> main = new ArrayList<>();
        List<String> side = new ArrayList<>();
        for (JsonNode c : deck.path("cards")) {
            List<String> cats = new ArrayList<>();
            for (JsonNode k : c.path("categories")) cats.add(k.asText());
            if (!cats.isEmpty() && excluded.contains(cats.get(0))) continue;
            String line = line(c);
            if (line == null) continue;
            if (cats.stream().anyMatch(commanderCats::contains)) commander.add(line);
            else if (cats.contains("Sideboard")) side.add(line);
            else main.add(line);
        }
        StringBuilder sb = new StringBuilder();
        if (!commander.isEmpty()) { sb.append("Commander\n"); commander.forEach(l -> sb.append(l).append('\n')); }
        sb.append("Main\n"); main.forEach(l -> sb.append(l).append('\n'));
        if (!side.isEmpty()) { sb.append("Sideboard\n"); side.forEach(l -> sb.append(l).append('\n')); }
        return new Result(deck.path("name").asText("Archidekt"), sb.toString());
    }

    private static String line(JsonNode c) {
        int qty = c.path("quantity").asInt(1);
        String name = c.path("card").path("oracleCard").path("name").asText(null);
        if (name == null || name.isBlank()) return null;
        String set = c.path("card").path("edition").path("editioncode").asText("");
        String num = c.path("card").path("collectorNumber").asText("");
        if (set.isBlank() || num.isBlank() || "null".equals(num)) {
            return qty + " " + name;
        }
        return qty + " " + name + " (" + set.toLowerCase() + ") " + num;
    }
}
```

- [ ] **Step 4: DeckSource**

Zweiten Konstruktor `DeckSource(DeckStore store, Archidekt archidekt)` ergänzen; der bestehende delegiert mit `Archidekt.standard()`. In `resolve` vor der `text`-Form:

```java
        if (node.hasNonNull("archidekt")) {
            Archidekt.Result r = archidekt.fetch(node.get("archidekt").asText());
            String name = node.hasNonNull("deckName") && !node.get("deckName").asText().isBlank()
                    ? node.get("deckName").asText().trim() : r.name();
            return resolveText(r.text(), name); // dieselbe Logik wie die text-Form: parse → Probleme → Resolved mit Save
        }
```

Dazu die bestehende `text`-Verarbeitung in eine private Methode `resolveText(String text, String deckNameOrNull)` ziehen, die beide Formen nutzen (bei `null` → `DeckImport.suggestName`). `Bridge.startGame`-Javadoc: vierte Form `{"archidekt":"https://archidekt.com/decks/…","deckName":"…"}`.

- [ ] **Step 5: Tests und Rauchtest**

Run: `cd /home/kevin/projects/MTG-Player/bridge && mvn -q test -Dtest='ArchidektTest,DeckSourceTest'` → grün (4 + 7). Rauchtest mit Netz (einmalig): kleines Java-Programm über `jshell` ist umständlich – stattdessen `mvn -q compile exec:java -Dexec.mainClass=mtgplayer.decks.Archidekt`? Nein: in `ArchidektTest` einen Test `@EnabledIfSystemProperty(named = "mtgplayer.net", matches = "true")` ergänzen, der `Archidekt.standard().fetch("1")` aufruft und `"Fun With Fungus"` erwartet; einmal mit `mvn -q test -Dtest=ArchidektTest -Dmtgplayer.net=true` laufen lassen (Ergebnis im Report), in der normalen Suite ist er übersprungen.

- [ ] **Step 6: Commit**

```bash
cd /home/kevin/projects/MTG-Player
git add bridge/src
git commit -m "m6: archidekt-deck per url oder id – json zur textliste, vierte decksource-form

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Frontend – Archidekt in der Lobby

**Files:**
- Modify: `web/src/protocol.ts` (`DeckRef` + `{ archidekt: string; deckName?: string }`), `web/src/deckref.ts` (+ `deckref.test.ts`), `web/src/components/Lobby.tsx`, `web/src/styles.css`
- Fixture: `web/fixtures/lobby.json` unverändert nutzbar

- [ ] **Step 1:** `deckref.test.ts`: neuer Test – `Pick { kind: "archidekt", value: "https://archidekt.com/decks/1/x", name: "" }` → `{ archidekt: "https://…/1/x" }`; mit `name: "Mein"` → `deckName: "Mein"`; leerer Wert → `undefined`. Run → rot.
- [ ] **Step 2:** `deckref.ts`: `Pick.kind` um `"archidekt"` erweitern, `toRef` entsprechend. `Lobby.tsx`: Picker-Option "Archidekt-URL" mit Text-Input (Placeholder `https://archidekt.com/decks/12345/…`) und optionalem Namensfeld (wie bei Textliste); Fehler kommen wie bisher als `error`-Nachricht in die Lobby.
- [ ] **Step 3:** `npm test` (31), `npm run build`, Screenshot `lobby-archidekt.png` (Bridge auf 18080/18091, Lobby mit gewählter Archidekt-Option – dafür `fixtures/lobby-archidekt.json` anlegen? Die Lobby hat keinen Fixture-Zustand für Picker; stattdessen im Screenshot-Skript per `page.selectOption('select', 'archidekt')` klicken – einfacher: `web/scripts/shot-lobby.mjs` mit den nötigen Klicks, analog zu `lobby-spectate-shot.mjs` aus M4, falls vorhanden im Scratchpad; sonst neu). Read, nachbessern. `npm run layout-check` grün.
- [ ] **Step 4:** Commit `m6: frontend – archidekt-url in der lobby` + Trailer.

---

### Task 3: README, Suiten

- [ ] README "Spielen": Absatz "Archidekt: Deck-URL (oder nur die ID) einfügen – öffentliche Decks; der Deckname wird übernommen." "Was noch fehlt (M6)" → `## Offen`: gleiche Länder stapeln, UI-Feinschliff (Hover-Panel feste Höhe), Moxfield bewusst nicht.
- [ ] Suiten: bridge `mvn -q test` → `Tests run: 96` (89 + 4 + 2 + 1 übersprungen zählt als run? Nein: `@EnabledIf…` zählt als skipped – erwarte `Tests run: 96, Skipped: 1`), web `npm test` 31, build, layout-check.
- [ ] Commit `m6: readme – archidekt` + Trailer.

## Nach diesem Plan

Ausgiebige UI-Runde (Kevins Liste: Hover-Panel verschiebt das Log, gleiche Länder stapeln, getappte Karten in Überlappung, allgemeiner Feinschliff).
