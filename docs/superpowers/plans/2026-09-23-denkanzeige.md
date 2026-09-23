# Denk-Anzeige, Wachhund, Sim-Hinweis

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Während die KI rechnet, sagt die Oberfläche das (mit Sekundenzähler); bleibt es über 120 s stehen, landet der Stack des Spiel-Threads im Log; die Lobby warnt bei mehreren Sim-Sitzen; die Bedenkzeit steht im Partie-Datensatz.

**Architecture:** `mtgplayer.gui.ThinkingTicker` (Daemon-Thread, injizierbare Zeitquelle) wird von `WebGuiGame` bei jeder Log-Zeile und jedem `pushState` berührt und schickt `thinking`-Nachrichten bzw. schreibt eine Wachhund-Zeile über `CrashLog.warn`. Web: Store-Zweig `thinking`, eine Anzeige-Komponente für Tisch und Zuschauer-Modus, Hinweiszeile in der Lobby. `MatchRecord` bekommt `aiTimeout`. Spec: `docs/superpowers/specs/2026-09-23-denkanzeige-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5), React 18/TS/Vitest/Playwright.

## Global Constraints

- Branch `feature/denkanzeige` (existiert, Spec committet). Commits deutsch, Kleinschreibung, Präfix `bridge:`/`ui:`/`docs:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- **Kevins Bridge läuft auf 8080/8081 aus `bridge/target/classes`.** Nicht im Hauptbaum kompilieren: eigener Arbeitsbaum (`git worktree add --detach <scratch>/<name> HEAD`), dort bauen und committen, danach im Hauptbaum `git cherry-pick`. Maven im Arbeitsbaum braucht `-Dmtgplayer.assets=/home/kevin/projects/MTG-Player/bridge/assets`. Nie `mvn clean`; ein Maven-Prozess gleichzeitig (Timeout 600000 ms). Eigene Test-Bridge für Screenshots auf 18086/18097, PID am Ende killen.
- Web: `npm test`, `npm run build` grün; Layout-Check der Tisch-Fixturen unverändert grün.
- Schwellen: Anzeige ab **3 s**, Zusatzhinweis ab **60 s**, Wachhund bei **120 s** (je Vorfall genau eine Zeile).
- Protokoll: `{ "type": "thinking", "player": <id|null>, "seconds": n }`, `seconds: 0` beendet die Anzeige.

---

### Task 1: `ThinkingTicker` + Anbindung + `aiTimeout` im Datensatz

**Files:**
- Create: `bridge/src/main/java/mtgplayer/gui/ThinkingTicker.java`
- Modify: `bridge/src/main/java/mtgplayer/gui/WebGuiGame.java`, `bridge/src/main/java/mtgplayer/protocol/Messages.java`, `bridge/src/main/java/mtgplayer/stats/MatchRecord.java`, `MatchRecorder.java`, `bridge/src/main/java/mtgplayer/match/HumanMatch.java`, `AiMatch.java`, `bridge/src/main/java/mtgplayer/server/Bridge.java`
- Test: `bridge/src/test/java/mtgplayer/gui/ThinkingTickerTest.java`, `bridge/src/test/java/mtgplayer/stats/MatchRecorderTest.java`

**Interfaces:**
```java
public record Thinking(String type, Integer player, int seconds) { public Thinking(Integer player, int seconds) { this("thinking", player, seconds); } }

public final class ThinkingTicker implements AutoCloseable {
    public ThinkingTicker(Consumer<Object> out, LongSupplier nanos, Supplier<Integer> priorityPlayer,
                          Supplier<String> phaseInfo, Supplier<Thread> gameThread) { }
    public void start();            // Daemon-Thread, 1 s Takt; mehrfaches start() ist wirkungslos
    public void touch();            // Aktivität gesehen: laufende Anzeige beenden (einmalig seconds 0)
    public void tick();             // ein Takt, fuer Tests direkt aufrufbar (start() ruft dasselbe)
    @Override public void close();  // Thread beenden, letzte Anzeige beenden
}
```
Schwellen als Konstanten (`SHOW_AFTER_S = 3`, `WATCHDOG_S = 120`). Der Wachhund schreibt über
`CrashLog.warn("Wachhund", text)` – Text wie in der Spec, mit Stacktrace des Spiel-Threads (`Supplier<Thread>`;
`null` → Zeile „(Spiel-Thread unbekannt)").

`WebGuiGame`: Feld `ThinkingTicker ticker`, `touch()` am Anfang von `sendLog(...)` und in `pushState()`;
`gameThread` merkt sich `Thread.currentThread()` im Log-Observer; `priorityPlayer`/`phaseInfo` aus dem zuletzt
gebauten Snapshot (Feld `lastSnapshot`, bereits vorhanden oder klein nachrüsten). Start beim neuen Spiel
(`onNewGame`), Stopp in `finishGame` und `resetForNewMatch`.

`MatchRecord`: neues Feld `Integer aiTimeout` direkt nach `source`; `MatchRecorder`-Konstruktor bekommt es und
schreibt es in `build()`. `HumanMatch.start`/`startSpectator` und `AiMatch.play` reichen ihren Timeout durch.
`Bridge` unverändert (der Wert kommt aus `AiConfig.timeout(msg)`, den `HumanMatch` schon bekommt).

- [ ] **Step 1: Failing Tests** – `ThinkingTickerTest` mit gesteuerter Zeitquelle (`long[] now`, `LongSupplier`):
  - nach 3 s Stille meldet `tick()` `Thinking(player, 3)`, danach je Sekunde mit wachsendem Wert;
  - `touch()` → nächster `tick()` meldet genau einmal `seconds == 0`, danach nichts mehr bis zur nächsten Stille;
  - bei 120 s Stille genau **eine** `CrashLog`-Zeile (Datei über `CrashLog.setFile(@TempDir …)`), auch wenn weitere
    Ticks folgen; nach `touch()` und erneuter Stille von 120 s eine zweite Zeile;
  - ohne laufendes Spiel (Ticker nicht gestartet) passiert nichts.
  `MatchRecorderTest`: ein Datensatz trägt den übergebenen `aiTimeout`.
- [ ] **Step 2: Rot** – `mvn -q test -Dtest='ThinkingTickerTest,MatchRecorderTest' -Dmtgplayer.assets=…` im Arbeitsbaum.
- [ ] **Step 3: Implementieren** wie oben.
- [ ] **Step 4: Grün** – benannte Klassen, dann volle Suite (Zahlen aus `target/surefire-reports`).
- [ ] **Step 5: Commit** – `bridge: denk-anzeige und wachhund, bedenkzeit im partie-datensatz`.

---

### Task 2: Web – Anzeige, Lobby-Hinweis, Chip im Statistik-Screen

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/store.ts` (+`store.test.ts`), `web/src/components/Table.tsx`, `web/src/components/Lobby.tsx`, `web/src/components/Stats.tsx`, `web/src/styles.css`, `web/fixtures/matches.json`
- Create: `web/src/components/Thinking.tsx`

**Interfaces:**
```ts
export interface Thinking { type: "thinking"; player?: number | null; seconds: number }
Inbound |= Thinking
// store
thinking?: { player?: number | null; seconds: number }   // seconds 0 -> Feld loeschen
MatchRecord.aiTimeout?: number | null
```

- [ ] **Step 1: Failing Tests** – `store.test.ts`: `thinking` mit `seconds: 5` setzt das Feld; `seconds: 0` löscht es; ein `state`-Snapshot löscht es ebenfalls (Aktivität).
- [ ] **Step 2: Implementieren**
  - `Thinking.tsx`: `{ compact?: boolean }`; rendert nichts ohne `thinking`. Text: „<Name> denkt …" (Name aus `state.players`, sonst „KI"), rechts die Sekunden (`n s`), drei animierte Punkte (CSS `@keyframes`), ab 60 s zweite Zeile „Simulation in einer 4er-Runde kann Minuten dauern".
  - `Table.tsx`: in der eigenen Ansicht über `<Prompt>`, im Zuschauer-Modus in der Fußzeile neben den Steuerknöpfen (`compact`).
  - `Lobby.tsx`: `const simSeats = aiPicks.filter(p => p.mode === "sim").length;` → bei `> 1` eine `.hint`-Zeile unter der Bedenkzeit mit dem Text aus der Spec (Zahl einsetzen).
  - `Stats.tsx`: in der Partienzeile hinter dem Quelle-Chip ein dezenter Chip `{aiTimeout} s`, wenn gesetzt; Fixture um `aiTimeout` ergänzen (beide Kopien: `web/fixtures/matches.json` und das TS-Literal im Test).
  - CSS: `.thinking` (Zeile, `--muted`, 13 px, Punkte-Animation), `.thinking.compact` (kleiner, inline), `.thinking .hint` (11 px, `--faint`).
- [ ] **Step 3: Screenshots** – eigene Bridge 18086/18097; Tisch-Fixture + `{"type":"thinking","player":2,"seconds":42}` per `mtgApply` → `thinking-table.png`; Zuschauer-Fixture + `seconds: 75` → `thinking-spectator.png` (zweite Zeile sichtbar); Lobby mit zwei Sim-Sitzen → `lobby-simhinweis.png`. Alle mit Read ansehen, bis ruhig und lesbar.
- [ ] **Step 4: Tests/Build/Layout-Check/Commit** – `npm test`, `npm run build`, Layout-Check `fixtures/table.json` bei 1600x900; Commit `ui: denk-anzeige im tisch und zuschauer-modus, sim-hinweis in der lobby, bedenkzeit im statistik-screen`.
