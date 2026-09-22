# Statistik Stück 1 – Partien erfassen, speichern, auswerten

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede beendete Partie (live und Zuschauer) wird als Datensatz erfasst und gespeichert; ein Statistik-Screen zeigt je Deck Kennzahlen und die Partienliste, in der Partien löschbar und als „nicht gewertet" markierbar sind.

**Architecture:** Bridge: `mtgplayer.stats.MatchRecorder` (Guava-`@Subscribe` an Forges `Game`, typisierte Events) → `MatchRecord` → `MatchStore` (`~/.mtg-player/matches.json`, atomar). Bridge-Nachrichten `matches` / `deleteMatch` / `setMatchCounted`. Web: reines Modul `matchStats.ts` (Aggregation inkl. Wilson-Intervall), Store-Zweig `matches`, Screen `Stats.tsx`. Spec: `docs/superpowers/specs/2026-09-22-statistik-stueck-1-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5, Jackson, Guava EventBus), Forge `Game`/`GameEvent*`, React 18/TS/Vitest/Playwright.

## Global Constraints

- Branch `feature/statistik-daten` (existiert, Spec committet). Commits deutsch, Kleinschreibung, Präfix `bridge:`/`ui:`/`docs:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- Kevins Bridge (8080/8081) nicht anfassen; nie `mvn clean`; ein Maven-Prozess gleichzeitig (Timeout 600000 ms); eigene Test-Bridge 18086/18097, PID am Ende killen (nicht `pkill -f java`). Tests, die eine echte Partie spielen, bekommen `@Timeout`.
- Speicherort in Tests immer über den Konstruktor (`new MatchStore(tempDir)`), **nie** `~/.mtg-player` beschreiben.
- Web: `npm test`, `npm run build` grün; Screenshots unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/stats/`, jede mit Read ansehen.
- Datensatzform und Protokoll exakt wie in der Spec §2/§4; Ausschlussregeln: `turns < 3` → „zu kurz", ein Sitz mit `lossReason == Conceded` → „aufgegeben", Absturz → „Absturz".

---

### Task 1: `MatchRecord` + `MatchStore`

**Files:**
- Create: `bridge/src/main/java/mtgplayer/stats/MatchRecord.java`, `MatchStore.java`
- Test: `bridge/src/test/java/mtgplayer/stats/MatchStoreTest.java`

**Interfaces:**
```java
public record Seat(String name, String deck, boolean human, Ai ai, boolean winner, String lossReason,
                   Integer eliminatedTurn, int mulligans, int lands, List<Integer> landsByTurn,
                   int missedLandDrops, Integer firstMissedLandDrop, int spells, int spellMana,
                   int commanderCasts, int commanderTax, Integer firstCommanderTurn,
                   int damageDealt, int damageTaken,
                   int combatDamageTaken, int lifeEnd, int poisonEnd) { }
public record Ai(String mode, String profile) { }
public record MatchRecord(String id, String startedAt, String endedAt, long durationMs, String source,
                          int turns, String reason, boolean draw, boolean counted, String excludeReason,
                          List<Seat> seats) {
    public MatchRecord withCounted(boolean counted, String excludeReason) { … }
}
public final class MatchStore {
    public static final int MAX = 2000;
    public MatchStore(Path file) { }            // Datei, nicht Verzeichnis
    public static MatchStore standard() { }      // ForgeBoot.dataDir().resolve("matches.json")
    public List<MatchRecord> all() { }           // aelteste zuerst; kaputte Datei -> leer + CrashLog-Zeile
    public void add(MatchRecord r) { }           // haengt an, kappt auf MAX, schreibt atomar
    public void delete(String id) { }            // unbekannt -> IllegalArgumentException("unbekannte Partie: <id>")
    public void setCounted(String id, boolean counted) { }  // unbekannt -> dieselbe Ausnahme
}
```
`id`: `Instant.now()` ISO (Sekunden) + "-" + 4 Hex-Zeichen aus `ThreadLocalRandom`. JSON über `mtgplayer.protocol.Json.mapper()` (`NON_NULL`), Liste als Array.

- [ ] **Step 1: Failing Test** – `MatchStoreTest` mit `@TempDir`: `add` zweier Datensätze → `all()` in Reihenfolge; `delete` entfernt, unbekannt wirft; `setCounted(id, false)` setzt das Feld; Neuladen aus derselben Datei liefert dasselbe (Rundtrip); Deckel: `MAX + 5` Datensätze → `all().size() == MAX`, ältester weg; kaputte Datei (`Files.writeString(file, "{kaputt")`) → `all()` leer, danach funktioniert `add`.
- [ ] **Step 2: Rot** – `cd bridge && mvn -q test -Dtest=MatchStoreTest`.
- [ ] **Step 3: Implementieren** – Records + Store wie oben; Schreiben: `Files.writeString(tmp, json)` + `Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)`; Verzeichnis anlegen, falls nötig; Lesen: fehlende Datei → leere Liste.
- [ ] **Step 4: Grün** + **Step 5: Commit** `bridge: matchrecord und matchstore (partien speichern)`.

---

### Task 2: `MatchRecorder` und Anbindung an `HumanMatch`

**Files:**
- Create: `bridge/src/main/java/mtgplayer/stats/MatchRecorder.java`
- Modify: `bridge/src/main/java/mtgplayer/match/HumanMatch.java`, `bridge/src/main/java/mtgplayer/forge/CrashLog.java` (Absturz-Flag)
- Test: `bridge/src/test/java/mtgplayer/stats/MatchRecorderTest.java`, `MatchRecorderAiTest.java`

**Interfaces:**
```java
public final class MatchRecorder {
    public MatchRecorder(Game game, String source, Consumer<MatchRecord> sink) { }  // registriert sich via game.subscribeToEvents(this)
    public void markCrashed() { }     // CrashLog-Listener
    public MatchRecord finish() { }   // aus GameEventGameFinished heraus bzw. fuer Tests direkt; idempotent
}
```
Ereignisse und Zählungen wie Spec §1. Deckname je Sitz: `player.getRegisteredPlayer().getDeck().getName()`; menschlich: `player.getController()` ist kein KI-Controller – einfacher: `player.getLobbyPlayer() instanceof LobbyPlayerHuman`; `Ai` aus `AiConfig` – dafür reicht `LobbyPlayerAi`, das seine `AiConfig` kennt (falls es sie nicht hält: Feld ergänzen, Getter `config()`), sonst `null`.

Verpasste Landabgabe: bei `GameEventTurnBegan` den vorigen Zug abschließen – war der vorige Zugbesitzer noch im Spiel und hat in diesem Zug kein Land gespielt, zählt eine verpasste Abgabe (erster Zug jedes Sitzes zählt mit). Beim Spielende den laufenden Zug nicht mehr werten.

Commander-Steuer: Summe über die Commander des Sitzes von `2 * max(0, getCommanderCast(c) - 1)`.

`HumanMatch`: `start`/`startSpectator` bekommen einen `Consumer<MatchRecord> sink` (bzw. ein `MatchStore`), registrieren nach `hosted.startMatch(...)` den Recorder am laufenden `Game` (`hosted.getGame()`); Quelle `"live"` bzw. `"spectate"`. Da `HostedMatch` das `Game` erst beim Start erzeugt: Recorder in einem kleinen Adapter anmelden, der auf das erste `Game` wartet (z. B. in `WebGuiGame.setGameView`/`openView`-Pfad oder per `hosted.getGame()` nach `startMatch`; der einfachste Weg, der ohne Polling auskommt, wird beim Implementieren gewählt und im Report begründet).

`CrashLog`: zusätzlicher Listener-Kanal (`addCrashListener(Runnable)`) oder der bestehende `setListener` wird um einen zweiten Empfänger erweitert – Recorder setzt `crashed`.

- [ ] **Step 1: Failing Tests** – `MatchRecorderTest` (Szenen-Harness): 2 Spieler; Recorder an `s.game()`; Karten legen/spielen über die Szene (Land spielen per `game.getAction().moveToPlay` löst kein `LandPlayed`-Event aus → stattdessen die Szene bis zu einer Hauptphase laufen lassen und die KI spielen lassen, oder `player.playLand(card, true)` nutzen – der Weg, der das Event auslöst, ist beim Implementieren zu prüfen). Erwartungen: Länder je Zug, Mulligan-Zähler (per `GameEventMulligan` über `game.fireEvent(...)`, falls direkt auslösbar), Zauber/Mana, Schaden (`p.addDamage(...)`), Verlustgrund `LifeReachedZero` nach Leben auf 0, Sieger.
  **Wichtig:** Wo ein Event in der Szene nicht ohne echten Spielzug entsteht, wird es im Test über `game.fireEvent(new GameEventX(...))` eingespeist – der Recorder ist reine Event-Buchhaltung, das ist ein legitimer Unit-Test; mindestens zwei Fälle (Länder, Schaden) müssen aber aus einem echten Spielzug stammen (Szene bis MAIN1, KI spielt ein Land).
- [ ] **Step 2: `MatchRecorderAiTest`** – echtes `AiMatch` (zwei Precons, Zugdeckel 4, `@Timeout(5, MINUTES)`): Recorder liefert Datensatz mit 2 Sitzen, `turns >= 1`, Deckname gesetzt, Summe Länder > 0, keine Ausnahme. Der Test dient als Integrationsnachweis, nicht als exakte Zahlenprüfung.
- [ ] **Step 3: Implementieren** (inkl. `HumanMatch`-Anbindung und `AiMatch`-Hook, den Stück 3 später nutzt: `AiMatch.run(..., Consumer<MatchRecord> sink)` optional).
- [ ] **Step 4: Grün** – `mvn -q test -Dtest='MatchRecorder*'`, danach volle Suite.
- [ ] **Step 5: Commit** `bridge: matchrecorder (partien aus forge-events erfassen) und anbindung an humanmatch`.

---

### Task 3: Bridge-Protokoll `matches` / `deleteMatch` / `setMatchCounted`

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java`

**Interfaces:**
```java
public record Matches(String type, List<MatchRecord> matches) { public Matches(List<MatchRecord> m) { this("matches", m); } }
```
`Bridge`: Feld `MatchStore matches = MatchStore.standard()` (package-private Konstruktor mit eigenem Store für Tests, wie bei `DeckStore`/`Archidekt`); `onClientConnected` schickt `Matches` nach `Lobby`; `HumanMatch`-Start übergibt den Sink `r -> { matches.add(r); ws.send(new Messages.Matches(matches.all())); }`; `case "deleteMatch"`/`case "setMatchCounted"` auf dem Hintergrund-Task-Muster mit `RuntimeException`-Fang → `error` „Partie <id>: …", sonst `Matches`.

- [ ] **Step 1: Failing Test** – `BridgeEndToEndTest` (Order beachten, eigener Store über den Testkonstruktor): nach Verbindung kommt eine `matches`-Nachricht (Array, ggf. leer); `{"type":"deleteMatch","id":"gibt es nicht"}` → `error` beginnend mit `Partie gibt es nicht:`; `setMatchCounted` mit unbekannter Id ebenso.
- [ ] **Step 2–4: Rot → Implementieren → Grün** (volle Suite am Ende).
- [ ] **Step 5: Commit** `bridge: protokoll matches, deleteMatch, setMatchCounted`.

---

### Task 4: Web – Protokoll, Store, `matchStats.ts`

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/store.ts`, `web/src/store.test.ts`
- Create: `web/src/matchStats.ts`, `web/src/matchStats.test.ts`, `web/fixtures/matches.json`

**Interfaces:**
```ts
export interface MatchSeat { name: string; deck: string; human: boolean; ai?: { mode: string; profile: string } | null;
  winner: boolean; lossReason?: string | null; eliminatedTurn?: number | null; mulligans: number; lands: number;
  landsByTurn: number[]; missedLandDrops: number; firstMissedLandDrop?: number | null; spells: number; spellMana: number;
  commanderCasts: number; commanderTax: number; firstCommanderTurn?: number | null; damageDealt: number; damageTaken: number; combatDamageTaken: number;
  lifeEnd: number; poisonEnd: number }
export interface MatchRecord { id: string; startedAt: string; endedAt: string; durationMs: number;
  source: "live" | "spectate" | "sparring"; turns: number; reason: string; draw: boolean; counted: boolean;
  excludeReason?: string | null; seats: MatchSeat[] }
export interface Matches { type: "matches"; matches: MatchRecord[] }
Outbound |= { type: "deleteMatch"; id: string } | { type: "setMatchCounted"; id: string; counted: boolean }
// matchStats.ts
export interface DeckRef2 { deck: string; games: number }            // Name: DeckGames
export function deckGames(records: MatchRecord[]): { deck: string; games: number }[]   // nur gewertete, absteigend nach games, dann Name
export interface DeckSummary { games: number; wins: number; losses: number; draws: number; winRate: number;
  ci: [number, number]; avgTurns: number; avgDurationMs: number; mulliganRate: number; avgMulligans: number;
  avgLandsTurn3: number; avgLandsTurn5: number; missedLandDropRate: number; avgMissedLandDrops: number;
  avgSpells: number; avgSpellMana: number; avgCommanderTurn?: number; avgCommanderTax: number;
  avgDamageDealt: number; avgDamageTaken: number; lossReasons: Record<string, number>;
  opponents: { deck: string; games: number; wins: number }[] }
export function summarize(records: MatchRecord[], deck: string): DeckSummary | undefined  // undefined bei 0 gewerteten Partien
export function wilson(wins: number, games: number): [number, number]                    // 95 %, [0,0] bei games 0
```
`avgCommanderTurn` mittelt `Seat.firstCommanderTurn` über die Partien, in denen der Commander gewirkt wurde (fehlt er überall: Feld weglassen/`undefined`).

- [ ] **Step 1: Failing Tests** – `matchStats.test.ts`: `wilson(7, 10)` ≈ `[0.397, 0.892]` (auf 3 Stellen), `wilson(0, 0)` → `[0, 0]`; `deckGames` ignoriert nicht gewertete Partien und sortiert; `summarize` über eine kleine Fixture-Liste (3 Partien, eine nicht gewertet) prüft Bilanz, Ø Züge, Mulligan-Quote, Ø Länder bis Zug 3, Todesursachen-Verteilung und Gegner-Tabelle; `summarize` für ein Deck ohne gewertete Partien → `undefined`. `store.test.ts`: `matches`-Reducer.
- [ ] **Step 2–4: Rot → Implementieren → Grün** (`npm test`, `npm run build`). Fixture `web/fixtures/matches.json` = `{ "type": "matches", "matches": [ … 4 realistische Datensätze, zwei Decks, einer nicht gewertet („aufgegeben") … ] }`.
- [ ] **Step 5: Commit** `ui: protokoll matches, store-zweig, auswertung (matchStats)`.

---

### Task 5: Screen „Statistik", Lobby-Knopf, Screenshots, README

**Files:**
- Create: `web/src/components/Stats.tsx`
- Modify: `web/src/App.tsx` (Screen-Weiche), `web/src/components/Lobby.tsx` (Knopf), `web/src/store.ts` (`screen: "stats"`, Aktionen `openStats`/`backToLobby`), `web/src/styles.css`, `web/scripts/shot-lobby.mjs` (oder neues `shot-stats.mjs`), `README.md`

- [ ] **Step 1: Screen** – Aufbau nach Spec §6: Kopf „Statistik" + Knopf „Zur Lobby"; links `.stat-decks` (Liste der Decks mit Partienzahl, Auswahl merken in `useState`, erste Zeile vorausgewählt); rechts Kacheln (`.stat-grid` mit `.stat-tile`: Wert groß, Label klein, bei Quoten das Intervall klein darunter); darunter `.match-list` mit Kopfzeile und je Partie: Datum (lokal, `toLocaleString`), Quelle-Chip (live/Zuschauer/Sparring), Gegner-Decks, Ergebnis („Sieg"/„Niederlage"/„Remis", farbig), Züge, Dauer (mm:ss), rechts Schalter „gewertet" (Checkbox) und Papierkorb mit Zwei-Klick-Bestätigung (Muster aus `DeckPicker`: `confirmDelete`, 4 s, Klick daneben). Filter „nur gewertete" (Checkbox, Standard an) über der Liste. Leerer Zustand wie Spec.
- [ ] **Step 2: Lobby-Knopf** – neben „Spiel starten" ein `button.ghost` „Statistik" → `openStats()`; im Statistik-Screen „Zur Lobby" zurück. `App.tsx`: `screen === "stats"` rendert `<Stats />`.
- [ ] **Step 3: CSS** – `.stats` (Karte wie `.lobby-card`, Breite `min(1100px, 100%)`), `.stat-decks` (Liste, aktive Zeile mit Akzentkante), `.stat-grid` (`repeat(auto-fit, minmax(150px, 1fr))`), `.stat-tile` (Panel, Wert 22 px fett, Label 11 px `--muted`), `.match-list` (Zeilen mit `display: grid`, `grid-template-columns`), `.match-row.uncounted { opacity: .55 }`, Ergebnis-Farben (`--green`/`--red`/`--muted`).
- [ ] **Step 4: Screenshots** – `npm run build`, eigene Bridge 18086/18097; Skript: Lobby-Fixture + `fixtures/matches.json` per `mtgApply`, dann Klick auf „Statistik"; Dateien `stats.png` (Kennzahlen + Liste) und `stats-confirm.png` (Papierkorb im Bestätigungszustand). Ansehen, bis ruhig und lesbar.
- [ ] **Step 5: README** – Abschnitt „Statistik": was erfasst wird, wo es liegt (`~/.mtg-player/matches.json`), automatische Ausschlüsse, Löschen/Werten, Ausblick Sparring und Schwächen-Analyse; Protokollzeilen `matches`/`deleteMatch`/`setMatchCounted`.
- [ ] **Step 6: Tests/Build/Commit** – `npm test`, `npm run build`, Layout-Check der Tisch-Fixtures unverändert grün; Commit `ui: statistik-screen mit kennzahlen und partienliste, readme`.
