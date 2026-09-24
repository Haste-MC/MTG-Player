# Runde A – Statistik-Board, Deckanalyse, Auffälligkeiten, Serien-Automatik

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Best-of startet das nächste Spiel von selbst; das Statistik-Board wird breit, bebildert, nach 1vs1/Pod getrennt, erklärt seine Zahlen, zeigt eine Deckanalyse und daraus abgeleitete Auffälligkeiten.

**Architecture:** Bridge: `DeckAnalysis` (Kartendatenbank, Regel-Liste über Orakeltext) mit `analyzeDeck`/`deckAnalysis`, `matches` ohne `timeline` + `matchDetail`. Web: `matchStats` um Formatfilter und v2-Kennzahlen erweitert, neues reines Modul `findings.ts`, neuer Screen-Aufbau in `Stats.tsx` plus Unterkomponenten, Serien-Countdown im Spielende-Dialog. Spec: `docs/superpowers/specs/2026-09-24-statboard-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5), Forge-Kartendatenbank, React 18/TS/Vitest/Playwright.

## Global Constraints

- Branch `feature/statboard` (existiert, Spec committet). Commits deutsch, Kleinschreibung, Präfix `bridge:`/`ui:`/`docs:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- **Kevins Bridge läuft auf 8080/8081 aus `bridge/target/classes`.** Java-Arbeit nur im eigenen Arbeitsbaum (`git worktree add --detach <scratch>/<name> feature/statboard`), Maven dort mit `-Dmtgplayer.assets=/home/kevin/projects/MTG-Player/bridge/assets`, committen, dann im Hauptbaum `git cherry-pick`, Arbeitsbaum entfernen. Reine Web-Arbeit darf im Hauptbaum passieren. Nie `mvn clean`; ein Maven-Prozess gleichzeitig; Timeout 600000 ms.
- Web: `npm test`, `npm run build` grün; Layout-Check der Tisch-Fixturen unverändert grün. Screenshots gegen Kevins Bridge auf `http://127.0.0.1:8080` (liefert `web/dist`, Fixturen legen den WebSocket still) **nach** `npm run build`; nicht neu starten. Dateien unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/statboard/`, jede mit Read ansehen.
- Kennzahlen aus Runde B nur über Partien mit `v >= 2` rechnen; fehlen sie, „–" statt „0 %".
- Schwellen der Auffälligkeiten als benannte Konstanten, Mindeststichprobe 5 gewertete Partien im gewählten Format.

---

### Task 1: Serien-Automatik

**Files:** `web/src/store.ts` (+`store.test.ts`), `web/src/components/Table.tsx`, `web/src/components/PhaseBar.tsx`, `web/src/styles.css`

**Interfaces:** Store bekommt `seriesCountdown?: number` (Sekunden bis zum Start, `undefined` = kein Countdown) mit Aktionen `startSeriesCountdown(seconds)`, `tickSeriesCountdown()`, `cancelSeriesCountdown()`. Reine Entscheidungsfunktion in `series.ts`:
```ts
export function nextSeriesStep(series: Series | undefined, bestOf: number, lastMatchCounted: boolean):
  { kind: "countdown" } | { kind: "decided"; winner: string } | { kind: "none" }
```
`countdown` nur, wenn `bestOf > 0`, die Serie noch nicht entschieden ist und die letzte Partie gewertet wurde.

- [ ] **Step 1: Failing Tests** – `series.test.ts` für `nextSeriesStep` (alle drei Fälle, inkl. abgebrochene Partie → `none`); `store.test.ts` für die Countdown-Aktionen (setzen, herunterzählen, auf 0 → `undefined`, abbrechen).
- [ ] **Step 2–3: Rot → Implementieren.** `Table.tsx`: im Spielende-Dialog bei `kind === "countdown"` Text „Spiel N von M startet in X …" plus Knöpfe „Jetzt starten" (sendet sofort) und „Serie beenden" (`cancelSeriesCountdown`, Dialog bleibt mit „Zur Lobby"); Timer via `useEffect` mit `setInterval(1000)` und Aufräumen; bei 0 einmalig `noteStart(lastStart)` + `send(lastStart)`. Bei `kind === "decided"` wie bisher „Neue Serie". Serienstand zusätzlich in `PhaseBar` (eigene Ansicht) und in der Zuschauer-Fußzeile: „Serie 1:0 · Best of 3".
- [ ] **Step 4: Screenshot** `serie-countdown.png` (Tisch-Fixture + `gameOver` + Store per `window.mtgStore` auf `bestOf: 3` und eine Serie gesetzt), ansehen.
- [ ] **Step 5: Tests/Build/Commit** – `ui: best of startet das naechste spiel selbst`.

---

### Task 2: Bridge – Deckanalyse

**Files:** Create `bridge/src/main/java/mtgplayer/decks/DeckAnalysis.java`; Modify `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`; Test `bridge/src/test/java/mtgplayer/decks/DeckAnalysisTest.java`, `BridgeEndToEndTest.java`

**Interfaces:**
```java
public record DeckAnalysis(int cards, int lands, int basics, double avgCmc, Map<String,Integer> curve,
                           Map<String,Integer> sources, List<String> identity,
                           Map<String,Integer> categories, int unclassified) {
    public static DeckAnalysis of(Deck deck);
}
// Messages
public record DeckAnalysisMsg(String type, String deck, DeckAnalysis analysis) { public DeckAnalysisMsg(String deck, DeckAnalysis a) { this("deckAnalysis", deck, a); } }
```
Kategorien und Muster wie Spec §3; die Regel-Liste als private Konstante in `DeckAnalysis` (jede Regel: Name + Prädikat auf `CardRules`), damit Tests einzelne Regeln prüfen können. Kurve über Nicht-Länder, Schlüssel `"0".."6"` und `"7+"`. Farbquellen: Länder mit Basistyp (`Plains`…) oder `Add {W}`… im Text; `any` für „any color". `identity` aus den Commandern (`Deck.getCommanders()`, `CardRules.getColorIdentity()`).
`Bridge`: `case "analyzeDeck"` auf dem Hintergrund-Task-Muster – Deck über `Precons`/`DeckStore` auflösen (erst gespeichertes Deck, dann Precon), Antwort `DeckAnalysisMsg`, unbekannt → `error` „Deckanalyse <name>: unbekanntes Deck".

- [ ] **Step 1: Failing Tests** – `DeckAnalysisTest` (ForgeBoot, Precons): ein bekanntes Precon liefert plausible Werte (Länder > 30, Summe Kurve + Länder == Karten, `avgCmc` > 0); je Kategorie eine bekannte Karte über die Regel-Prädikate (`Cultivate` → ramp+tutor, `Divination` → draw, `Murder` → removal, `Day of Judgment` → wipes, `Counterspell` → counters, `Giant Spider` → flyerDefense, `Heroic Intervention` → wipeProtection, `Eternal Witness` → recursion); Farbquellen eines zweifarbigen Decks; `unclassified` > 0.
- [ ] **Step 2–4: Rot → Implementieren → Grün** (volle Suite im Arbeitsbaum).
- [ ] **Step 5: Commit** – `bridge: deckanalyse aus der kartendatenbank (analyzeDeck)`.

---

### Task 3: Bridge – schlanke Partienliste, `matchDetail`

**Files:** `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`, `bridge/src/main/java/mtgplayer/stats/MatchRecord.java` (Hilfsmethode), Test `BridgeEndToEndTest`, `MatchRecordTest` (neu, falls nicht vorhanden)

**Interfaces:**
```java
public record Matches(String type, List<MatchRecord> matches, int total) { public Matches(List<MatchRecord> m, int total) { this("matches", m, total); } }
public record MatchMsg(String type, MatchRecord match) { public MatchMsg(MatchRecord m) { this("match", m); } }
// MatchRecord
public MatchRecord withoutTimeline();   // jede Seat-timeline null -> Feld faellt per NON_NULL aus dem JSON
```
`Bridge`: `matches` schickt die letzten 300 Datensätze `withoutTimeline()` plus `total`; neu `case "matchDetail"` → `MatchMsg` mit dem vollständigen Datensatz, unbekannte Id → `error` „Partie <id>: unbekannte Partie".
**Achtung:** `MatchRecord`s Compact-Konstruktor ersetzt `null`-Timelines heute durch eine leere Liste – für `withoutTimeline()` muss das Feld tatsächlich fehlen; Weg dafür wählen (eigener Serialisierungs-View oder das Feld als `null` zulassen und die Ersetzung nur beim Lesen anwenden) und im Report begründen.

- [ ] **Step 1: Failing Tests** – `matches` enthält keine `timeline` und ein `total`; `matchDetail` liefert sie; unbekannte Id → `error`; Deckel 300 (301 Datensätze → 300 gesendet, `total == 301`).
- [ ] **Step 2–4: Rot → Implementieren → Grün.**
- [ ] **Step 5: Commit** – `bridge: partienliste ohne zeitachse, matchDetail auf anfrage`.

---

### Task 4: Web – Auswertung erweitern (Format, v2-Kennzahlen, Findings)

**Files:** `web/src/protocol.ts`, `web/src/matchStats.ts` (+Test), Create `web/src/findings.ts` (+Test), `web/src/store.ts` (+Test), `web/fixtures/matches.json`

**Interfaces:**
```ts
export type Format = "all" | "duel" | "pod";
export function formatOf(r: MatchRecord): "duel" | "pod";           // seats.length === 2 -> duel
export function deckGames(records, format?): { deck, games, capped }[];
export function summarize(records, deck, format?): DeckSummary | undefined;
// DeckSummary zusaetzlich:
//   v2Games: number                    // Partien mit v >= 2 (Basis der Vorfall-Kennzahlen)
//   manaScrewRate?, floodRate?, avgOpeningLands?, spellsPerTurn?, counteredRate?,
//   sweepGames?, avgBiggestSweep?, flyingShare?, tramplingShare?, avgAttacks?, avgAttackersFaced?,
//   avgHandEnd?, avgRemovalCast?, avgCounterspellsCast?, avgEliminationShare?   // alle undefined ohne v2-Partien
export interface Finding { level: "info" | "warn"; title: string; text: string; needs?: string }
export function findings(s: DeckSummary | undefined, deck: DeckAnalysis | undefined, format: Format): Finding[]
```
Schwellen als exportierte Konstanten. `findings` liefert bei < 5 gewerteten Partien genau einen `info`-Eintrag „zu wenige Partien".

- [ ] **Step 1: Failing Tests** – `matchStats.test.ts`: Formatfilter trennt Duell und Pod; v2-Kennzahlen sind `undefined`, wenn nur `v: 1`-Partien vorliegen, und gerechnet, sobald v2 dabei ist (Mischung: nur v2-Partien zählen). `findings.test.ts`: je Regel ein Fall über und einer unter der Schwelle, Mindeststichprobe, „nichts Auffälliges".
- [ ] **Step 2–4: Rot → Implementieren → Grün.** Fixture `matches.json` um zwei v2-Partien erweitern (eine Duell-, eine Pod-Partie mit gefüllten Vorfall-Feldern und Zeitachse); TS-Literal im Test synchron halten.
- [ ] **Step 5: Commit** – `ui: auswertung nach format, v2-kennzahlen, auffaelligkeiten-regeln`.

---

### Task 5: Web – Board-Umbau

**Files:** `web/src/components/Stats.tsx` (Umbau), Create `web/src/components/StatTiles.tsx`, `web/src/components/Findings.tsx`, `web/src/components/DeckAnalysisPanel.tsx`, `web/src/components/MatchTimeline.tsx`; Modify `web/src/styles.css`, `web/src/store.ts` (deckAnalysis/match-Reducer), `web/scripts/shot-lobby.mjs` (oder neues Skript)

Aufbau exakt nach Spec §4: volle Breite, Kopf mit Format-Chips und Erklärungs-Schalter, Deckliste mit Commander-Bild (Bild über `precons`/`decks` aus dem Store, Muster `DeckPicker`), Blöcke 1–7, Partienliste mit aufklappbarer Zeile (lädt `matchDetail`, zeigt `MatchTimeline` als kleine SVG-Kurve: Länder, Kreaturen, Leben je Zug), Auffälligkeiten-Block über den Kacheln.

- [ ] **Step 1: Komponenten + CSS** (Kacheln mit Erklärzeile, Kurve als reines SVG ohne Bibliothek).
- [ ] **Step 2: Store** – Reducer für `deckAnalysis` (je Deckname merken) und `match` (Detail je Id merken); Anfrage beim Deckwechsel bzw. beim Aufklappen. Tests.
- [ ] **Step 3: Screenshots** – `statboard.png` (1600×900), `statboard-1280.png`, `statboard-findings.png` (Fixture mit Befunden), `statboard-timeline.png` (aufgeklappte Partie). Ansehen und nachbessern, bis ruhig und lesbar.
- [ ] **Step 4: Tests/Build/Layout-Check/Commit** – `ui: statistik-board breit, bebildert, nach format getrennt, mit deckanalyse und auffaelligkeiten`.
