# Archidekt-Konto – Deckliste holen und aktualisieren

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Im Deck-Panel ein Reiter „Archidekt": Benutzername eingeben, die Commander-Decks des Kontos sehen (neu / aktuell / geändert), ausgewählte mit einem Klick importieren bzw. aktualisieren.

**Architecture:** Bridge: `Archidekt.listDecks` (paginierte v3-Liste), zweites Deck-Tag `archidekt-updated:<updatedAt>`, `DeckSource.importArchidekt(id)` (neu oder Resync), Nachrichten `archidektList` → `archidektDecks`, `archidektImport` → `archidektProgress`-Folge + `lobby` je Deck. Web: Store-Zweig `archidekt`, reines Modul `archidektPlan.ts` (Klassifikation, Vorauswahl), `archidektSettings.ts` (Benutzername), vierter Reiter in `DeckPicker`. Spec: `docs/superpowers/specs/2026-09-21-archidekt-konto-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5, Jackson), React 18/TS/Vitest/Playwright.

## Global Constraints

- Branch `feature/archidekt-konto` (existiert; Fixtures `bridge/src/test/resources/archidekt-list-1.json` (Seite 1, `next` gesetzt, 3 Einträge: 2 Commander + 1 `deckFormat: 1`, einer mit `customFeatured`) und `archidekt-list-2.json` (Seite 2, `next: null`, 1 Commander-Deck `9592911`) liegen schon im Arbeitsverzeichnis, uncommittet).
- Commits deutsch, Kleinschreibung, Präfix `bridge:`/`ui:`/`docs:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- Kevins Bridge (8080/8081) nicht anfassen; nie `mvn clean`; ein Maven-Prozess gleichzeitig (Timeout 600000 ms); eigene Test-Bridge auf 18086/18097 (`-Dmtgplayer.httpPort=18086 -Dmtgplayer.wsPort=18097`), PID am Ende killen (nicht `pkill -f java`); 18080/18084 sind von Tests belegt.
- Web: `cd web && npm test`, `npm run build` grün; Screenshots unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/archidekt/`, jede mit Read ansehen.
- Protokoll (verbindlich): `DeckInfo.archidektUpdated?: string | null`; Outbound `{ type: "archidektList", username }`, `{ type: "archidektImport", ids: number[] }`; Inbound `{ type: "archidektDecks", username, decks: [{ id: number, name, updatedAt, art?: string }] }`, `{ type: "archidektProgress", done, total, current?: string | null, errors: string[] }`. Tags: `archidekt:<id>`, `archidekt-updated:<updatedAt>`. Nur `deckFormat == 3`; max. 10 Seiten; ≥ 1 s zwischen Anfragen im Hintergrund-Task (Fetcher ist ein Interface – im Test ohne Wartezeit).
- Marken: **neu** (kein eigenes Deck mit der Id), **aktuell** (`archidektUpdated === updatedAt`), **geändert** (sonst). Vorauswahl = alle *geändert*. „Alle aktualisieren" = alle *aktuell* + *geändert*.

---

### Task 1: Bridge – Liste, zweites Tag, `importArchidekt`, Nachrichten

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/decks/Archidekt.java`, `DeckStore.java`, `DeckSource.java`, `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/forge/Precons.java` (`info` bekommt `archidektUpdated`), `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/decks/ArchidektTest.java`, `DeckStoreTest.java`, `DeckSourceTest.java`, `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`; Fixtures s. o. (mit committen)

**Interfaces:**
```java
// Archidekt
public record Result(String name, String text, String updatedAt) { }          // updatedAt aus dem Deck-Detail (kann null sein)
public record Entry(long id, String name, String updatedAt, String art) { }
public List<Entry> listDecks(String username)   // v3-Liste, nur deckFormat 3, art = customFeatured wenn nicht leer sonst featured (leer → null), folgt "next" bis 10 Seiten; IllegalArgumentException("Archidekt: …") bei HTTP/JSON-Fehler
// DeckStore
public static final String ARCHIDEKT_UPDATED_TAG = "archidekt-updated:";
public String byArchidektId(String id)          // gespeicherter Deckname oder null
// Messages
public record DeckInfo(String name, List<Commander> commanders, String archidekt, String archidektUpdated) { }
public record ArchidektEntry(long id, String name, String updatedAt, String art) { }
public record ArchidektDecks(String type, String username, List<ArchidektEntry> decks) { public ArchidektDecks(String u, List<ArchidektEntry> d) { this("archidektDecks", u, d); } }
public record ArchidektProgress(String type, int done, int total, String current, List<String> errors) { public ArchidektProgress(int done, int total, String current, List<String> errors) { this("archidektProgress", done, total, current, errors); } }
// DeckSource
public Resolved importArchidekt(long id)        // vorhandenes Deck mit Tag archidekt:<id> → Resync unter dem gespeicherten Namen, sonst Neuimport mit Archidekt-Namen; setzt beide Tags
```

- [ ] **Step 1: Failing Tests** – `ArchidektTest`:

```java
    @Test
    void listeFiltertCommanderUndFolgtNext() throws IOException {
        String p1 = Files.readString(Path.of("src/test/resources/archidekt-list-1.json"));
        String p2 = Files.readString(Path.of("src/test/resources/archidekt-list-2.json"));
        List<String> urls = new ArrayList<>();
        Archidekt a = new Archidekt(url -> { urls.add(url); return url.contains("page=2") ? p2 : p1; });
        List<Archidekt.Entry> decks = a.listDecks("plssssss");
        assertEquals(List.of(26595870L, 26637620L, 9592911L), decks.stream().map(Archidekt.Entry::id).toList());
        assertEquals("https://card-images.archidekt.com/art/front/5/f/5feba5d6-99a6-4e9b-8a7d-90d955868fc3.webp?1783911263", decks.get(0).art());
        assertTrue(urls.get(0).contains("ownerUsername=plssssss"), urls.get(0));
        assertEquals(2, urls.size());
    }

    @Test
    void listeFehlerWirdGemeldet() {
        Archidekt a = new Archidekt(url -> { throw new IOException("HTTP 404"); });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> a.listDecks("x"));
        assertTrue(e.getMessage().startsWith("Archidekt:"));
    }

    @Test
    void fetchLiefertUpdatedAt() throws IOException {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        assertEquals("2018-03-12T05:12:58Z", new Archidekt(url -> body).fetch("1").updatedAt());
    }
```

(Prüfen, ob `archidekt-list-1.json` Eintrag 3 die Id `26637620` hat – sonst die Ids aus der Datei nehmen.) `DeckStoreTest`: Deck mit beiden Tags speichern → `infos().get(0).archidektUpdated()` = Wert, `byArchidektId("12345")` = Name, `byArchidektId("0")` = null. `DeckSourceTest`: `archidektFormHoltParstUndSpeichert` zusätzlich `assertEquals("2018-03-12T05:12:58Z", store.infos().get(0).archidektUpdated())`; neu:

```java
    @Test
    void importArchidektNeuUndDannResyncUnterGespeichertemNamen(@TempDir Path dir) throws Exception {
        String body = Files.readString(Path.of("src/test/resources/archidekt-1.json"));
        DeckStore store = new DeckStore(dir);
        DeckSource src = new DeckSource(store, new Archidekt(url -> body));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Fun With Fungus"), store.names());
        // umbenannt gespeichert (Name aus einem frueheren Import mit deckName) – Resync behaelt den Namen
        Deck d = store.load("Fun With Fungus");
        store.save("Pilze", d);
        Files.delete(dir.resolve(DeckStore.fileName("Fun With Fungus")));
        src.importArchidekt(1).save().run();
        assertEquals(List.of("Pilze"), store.names());
        assertEquals("1", store.archidektId("Pilze"));
    }
```

`BridgeEndToEndTest` (Order-Schema beachten): `{"type":"archidektImport","ids":[]}` → `archidektProgress` mit `done 0, total 0, current null, errors []`; `{"type":"archidektList","username":""}` → `error` (leerer Name).

- [ ] **Step 2: Rot** – `cd bridge && mvn -q test -Dtest='ArchidektTest,DeckStoreTest,DeckSourceTest'`.

- [ ] **Step 3: Implementieren**
  - `Archidekt.fetch`: `Result` um `deck.path("updatedAt").asText(null)` erweitern; `listDecks`: Schleife über `url = "https://archidekt.com/api/decks/v3/?ownerUsername=" + URLEncoder.encode(username, UTF_8) + "&pageSize=50"`, dann `next` (als Text; `http://` → `https://` normalisieren), max. 10 Seiten; leerer Name → `IllegalArgumentException("Archidekt: Benutzername fehlt")`.
  - `DeckStore`: `ARCHIDEKT_UPDATED_TAG`, `archidektUpdated(Deck)`, `infos()` gibt beides an `Precons.info(name, d, id, updated)`; `byArchidektId`.
  - `DeckSource.resolveText(text, name, id, updatedAt)`: Tags setzen (vorher beide alten Tags entfernen, damit kein Duplikat entsteht); `resync` nutzt `fetch(id)` → `updatedAt`; `importArchidekt(id)`: `String existing = store.byArchidektId(String.valueOf(id))` → `existing != null ? resync(existing) : resolveText(r.text(), r.name(), id, r.updatedAt())`.
  - `Bridge`: `case "archidektList"` → Hintergrund-Task: `listDecks` → `ArchidektDecks`, Fehler → `ErrorMsg`; `case "archidektImport"`: wenn `importRunning` (AtomicBoolean) → `ErrorMsg("Archidekt: Import läuft noch")`; sonst Hintergrund-Task: `total = ids.size()`; `send(Progress(0,total,null,[]))`; je Id: `send(Progress(done,total,"<Name aus DeckStore oder 'Deck ' + id>",errors))`, `importArchidekt(id).save().run()` in try/catch (`RuntimeException` → `errors.add(...)`), `done++`, `send(Lobby)`, `Thread.sleep(1000)` außer nach dem letzten; am Ende `send(Progress(done,total,null,errors))`, Flag zurück. Der Name für `current` vor dem Import: `store.byArchidektId(id)` oder `"Deck " + id` (die Archidekt-Liste liegt nur beim Client).
  - `Messages.Lobby` unverändert; `DeckInfo` mit viertem Feld – `Precons.info` und alle Aufrufer anpassen.

- [ ] **Step 4: Grün** – benannte Klassen, dann `mvn -q test` komplett (~180 Tests). Fixtures `git add`.

- [ ] **Step 5: Commit** – `bridge: archidekt-konto – deckliste, updated-tag, importArchidekt, archidektList/-Import`.

---

### Task 2: Web – Protokoll, Store, `archidektPlan.ts`, `archidektSettings.ts`

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/store.ts`, `web/src/store.test.ts`
- Create: `web/src/archidektPlan.ts` (+`.test.ts`), `web/src/archidektSettings.ts` (+`.test.ts`), `web/fixtures/archidekt-decks.json`, `web/fixtures/archidekt-progress.json`

**Interfaces:**
```ts
// protocol.ts
export interface ArchidektEntry { id: number; name: string; updatedAt: string; art?: string | null }
export interface ArchidektDecks { type: "archidektDecks"; username: string; decks: ArchidektEntry[] }
export interface ArchidektProgress { type: "archidektProgress"; done: number; total: number; current?: string | null; errors: string[] }
DeckInfo.archidektUpdated?: string | null;  Inbound |= ArchidektDecks | ArchidektProgress
Outbound |= { type: "archidektList"; username: string } | { type: "archidektImport"; ids: number[] }
// archidektPlan.ts
export type EntryState = "neu" | "aktuell" | "geändert";
export function classify(e: ArchidektEntry, own: DeckInfo[]): EntryState
export function defaultSelection(entries: ArchidektEntry[], own: DeckInfo[]): number[]   // alle "geändert"
export function updateAllIds(entries: ArchidektEntry[], own: DeckInfo[]): number[]      // "aktuell" + "geändert"
// archidektSettings.ts
export function loadArchidektUser(getStorage): string          // Key "mtg.lobby.archidekt", Default ""
export function saveArchidektUser(getStorage, username: string): void
// store.ts
archidekt: { username?: string; decks?: ArchidektEntry[]; loading: boolean; progress?: ArchidektProgress }
requestArchidektList(username): void   // setzt loading, sendet archidektList  (Aktion im Store, send aus ws)
Reducer: archidektDecks → { username, decks, loading: false }; archidektProgress → progress (bei done === total && current == null: progress bleibt als "fertig"); error → loading: false
```

- [ ] **Step 1: Failing Tests** – `archidektPlan.test.ts` (Klassifikation der drei Zustände über `archidekt`/`archidektUpdated` der eigenen Decks – Vergleich der Id als String, weil `DeckInfo.archidekt` String ist; `defaultSelection` nur geändert; `updateAllIds` aktuell+geändert, Reihenfolge wie `entries`); `archidektSettings.test.ts` (Rundtrip, kaputt → ""); `store.test.ts` (`archidektDecks` setzt Liste und beendet loading; `archidektProgress` landet in `archidekt.progress`; `error` beendet loading).
- [ ] **Step 2: Rot**, **Step 3: Implementieren** (Store-Aktion `requestArchidektList` ruft `send` aus `./ws` – prüfen, dass `store.ts` `ws` importieren darf ohne Zyklus; sonst die Aktion in `DeckPicker` belassen und im Store nur `setArchidektLoading(true)` anbieten). Fixtures: `archidekt-decks.json` = `{ "type": "archidektDecks", "username": "kevin", "decks": [ … 5 Einträge mit echten `art`-URLs aus `bridge/src/test/resources/archidekt-list-*.json`, einer mit Id `12345` (= `Felothar Landfall` in `lobby.json`, `updatedAt` neuer als ein in `lobby.json` zu ergänzendes `archidektUpdated`) und einer mit passendem `updatedAt` (= aktuell) ] }`; `archidekt-progress.json` = `{ "type": "archidektProgress", "done": 2, "total": 5, "current": "Koma, World-Eater", "errors": ["HOBBITS CREATE MONSTERS: Archidekt: HTTP 500"] }`. `lobby.json`: `Felothar Landfall` bekommt `archidektUpdated`.
- [ ] **Step 4: Grün** – `npm test`, `npm run build`.
- [ ] **Step 5: Commit** – `ui: protokoll archidekt-konto, store-zweig, klassifikation und benutzername (module)`.

---

### Task 3: `DeckPicker` Reiter „Archidekt", Screenshots, README

**Files:**
- Modify: `web/src/components/DeckPicker.tsx`, `web/src/styles.css`, `web/scripts/shot-lobby.mjs` (`--tab=archidekt`, `--apply=<fixture>` wendet nach dem Öffnen weitere Fixtures an), `README.md`

- [ ] **Step 1: Reiter** – `Tab |= "archidekt"`, `TAB_LABEL.archidekt = "Archidekt"`. Zustand: `username` (aus `loadArchidektUser` initial), `selected: Set<number>` (bei neuer Liste = `defaultSelection`). Kopf des Reiters (statt Suchfeld): `<input placeholder="Archidekt-Benutzername" value={username} onKeyDown Enter → laden>` + `<button className="primary small">Decks laden</button>` (disabled bei leerem Namen oder `loading`); beim Laden `saveArchidektUser`. Raster: `.deck-card` je Eintrag mit `<img className="art" src={e.art} referrerPolicy="no-referrer">` (Fallback-Schraffur ohne `art`), Name, Marke `.state-badge.{neu|aktuell|geändert}` (Text: neu / aktuell / geändert), Checkbox oben links (`.deck-check`, Klick auf die Karte toggelt, Checkbox selbst auch). Fußzeile `.deck-actions`: „Ausgewählte holen (n)" (`send({ type: "archidektImport", ids })`, disabled bei n = 0 oder laufendem Import = `progress && progress.current != null`), „Alle aktualisieren" (`updateAllIds`, disabled wenn leer), daneben die Fortschritts-/Ergebniszeile: laufend „{done}/{total} · {current} …", fertig „{done}/{total} fertig", Fehlerliste rot mehrzeilig (`.deck-status.warn`, `white-space: pre-line`). Leere Liste nach Laden: „keine öffentlichen Commander-Decks gefunden". Hinweis-Zeile klein: „Nur öffentliche/ungelistete Decks; private sieht Archidekt ohne Login nicht."
- [ ] **Step 2: CSS** – `.state-badge` (Pille rechts oben in der Karte; neu = accent-soft/blau, aktuell = grün-soft, geändert = gold-soft), `.deck-check` (Checkbox links oben, `accent-color`), `.deck-card.checked { border-color: var(--accent) }`, `.deck-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }`, `.archidekt-head { display: flex; gap: 8px; flex: 1; }`.
- [ ] **Step 3: Screenshots** – `npm run build`, eigene Bridge 18086/18097, `node scripts/shot-lobby.mjs http://127.0.0.1:18086 <scratch>/archidekt/list.png --open-panel --tab=archidekt --apply=fixtures/archidekt-decks.json`, dann `progress.png` zusätzlich `--apply=fixtures/archidekt-progress.json`. Ansehen: Marken lesbar, Häkchen bei „geändert", Bilder von Archidekt geladen (externe URLs – wenn sie im Headless-Browser fehlen, Fallback sichtbar und im Report vermerkt), Fortschritts- und Fehlerzeile.
- [ ] **Step 4: README** – Absatz Archidekt-Konto (Reiter, Marken, Häkchen, „Alle aktualisieren", nur öffentliche Decks, Tags `archidekt:` und `archidekt-updated:`), Protokollzeilen (`archidektList`/`archidektDecks`, `archidektImport`/`archidektProgress`).
- [ ] **Step 5: Tests/Build/Commit** – `npm test`, `npm run build`; Commit `ui: reiter archidekt im deck-panel – konto-deckliste, auswahl, import/aktualisieren mit fortschritt, readme`.
