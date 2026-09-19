# KI-Paket Stufe 1 – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** KI-Modus (Standard/Hybrid/Simulation) und Profil je KI-Sitz, einstellbare KI-Bedenkzeit, headless Bench (1-gegen-1, N Spiele, Siegquote mit Konfidenzintervall) plus erster Messlauf Sim vs. Standard.

**Architecture:** Bridge: `mtgplayer.ai.AiConfig` (Record, baut `LobbyPlayerAi` mit `AIOption` + Profil), `HumanMatch`/`AiMatch` nehmen je Sitz eine `AiConfig` und den Timeout; Protokoll um `opponents[i].ai` und `aiTimeout` erweitert, `lobby` liefert Modi/Profile. `mtgplayer.bench.Bench` (Schleife um `AiMatch.play` mit Seed je Spiel) + `BenchStats` (rein) + CLI `--bench` in `Main`. Frontend: Lobby-Dropdowns je Slot + Timeout-Feld, Auswahl im `localStorage`. Spec: `docs/superpowers/specs/2026-09-19-ki-paket-stufe-1-design.md`.

**Tech Stack:** Java 17/Maven/JUnit 5/Jackson (Bridge), Forge 2.0.14 unverändert, React 18/TS/Vitest/Playwright (Web).

## Global Constraints

- Forge-Submodule unverändert. Protokoll abwärtskompatibel: `ai` und `aiTimeout` optional; Frontend sendet sie nur bei Abweichung vom Standard (`standard`/`Default`/5).
- Modus-Namen exakt `standard | hybrid | sim`; Profile aus `AiProfileUtil.getAvailableProfiles()` (erwartet `Default`, `Cautious`, `Reckless`, `Experimental`), Standardprofil `Default`; Timeout 1–60 s, Standard 5.
- Bench: sequenziell, Seed je Spiel `seed + i`, Ausgabe Markdown + JSON unter `~/.mtg-player/bench/` (überschreibbar mit `--out`), Wilson-Intervall 95 % (z = 1,96).
- Suiten grün: `cd bridge && mvn -q test` (97 + neue; Timeout 600000 ms, nie zwei Maven-Prozesse; Kevins Bridge auf 8080/8081 nicht anfassen, Tests nutzen 18081+), `cd web && npm test`, `npm run build`, `npm run layout-check` (unverändert grün), Lobby-Screenshots gelesen.
- Commit-Messages deutsch, Kleinschreibung, Präfix `feat:` (Bridge/Bench), `ui:` (Web), `docs:`; Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Branch `feature/ki-paket-1` ab `main`.

---

### Task 1: Bridge – `AiConfig`, Sitze, Timeout, Protokoll

**Files:**
- Create: `bridge/src/main/java/mtgplayer/ai/AiConfig.java`
- Modify: `bridge/src/main/java/mtgplayer/match/HumanMatch.java` (`start`, `startSpectator`), `bridge/src/main/java/mtgplayer/match/AiMatch.java` (`play`), `bridge/src/main/java/mtgplayer/server/Bridge.java` (`startGame`, Lobby-Nachricht), `bridge/src/main/java/mtgplayer/protocol/Messages.java` (`Lobby`), `bridge/src/main/java/mtgplayer/Main.java` (`aiDemo`-Aufruf anpassen)
- Test: `bridge/src/test/java/mtgplayer/ai/AiConfigTest.java`, `bridge/src/test/java/mtgplayer/server/BridgeEndToEndTest.java` (ein neuer Test), `bridge/src/test/java/mtgplayer/match/AiMatchTest.java` (Signatur)

**Interfaces (Produces):**
```java
package mtgplayer.ai;
public record AiConfig(Mode mode, String profile) {
  public enum Mode { STANDARD, HYBRID, SIM;
    public String json() { return name().toLowerCase(); }                 // "standard" | "hybrid" | "sim"
    public static Mode parse(String s);                                     // "standard"|"std", "hybrid", "sim"|"simulation"; sonst IllegalArgumentException("Unbekannter KI-Modus: …")
  }
  public static final AiConfig DEFAULT = new AiConfig(Mode.STANDARD, "Default");
  public static final int DEFAULT_TIMEOUT = 5, MIN_TIMEOUT = 1, MAX_TIMEOUT = 60;
  public static List<String> profiles();      // AiProfileUtil.getAvailableProfiles() sortiert, "Default" zuerst
  public static List<String> modes();         // ["standard","hybrid","sim"]
  public static AiConfig parse(String spec);  // "sim:Reckless" | "std" | "hybrid:Cautious"; Profil-Teil fehlt → "Default"; unbekanntes Profil → IllegalArgumentException("Unbekanntes KI-Profil: …")
  public static AiConfig fromJson(JsonNode ai); // null/missing → DEFAULT; Felder mode/profile optional
  public static int timeout(JsonNode msg);    // msg.path("aiTimeout"): fehlt → 5; außerhalb 1..60 → IllegalArgumentException("KI-Bedenkzeit muss 1–60 s sein")
  public LobbyPlayerAi newLobbyPlayer(String name); // Set<AIOption> aus mode (STANDARD → null), setAiProfile(profile)
  public String spec();                       // "sim:Reckless" (für Dateinamen/Reports)
}
```
`HumanMatch.start(String humanName, Deck humanDeck, List<Deck> aiDecks, List<String> aiNames, List<AiConfig> aiConfigs, int aiTimeout, WebGuiGame gui)`, `HumanMatch.startSpectator(List<Deck>, List<String>, List<AiConfig>, int aiTimeout, WebGuiGame)`, `AiMatch.play(List<Deck> decks, List<String> names, List<AiConfig> configs, int aiTimeout, int maxTurns, Consumer<String> log)`; die alten Signaturen bleiben als Überladungen mit `AiConfig.DEFAULT` je Sitz und Timeout 5 (Aufrufer in Tests/`Main.aiDemo` laufen weiter).

- [ ] **Step 1: Test `AiConfigTest`** (braucht `ForgeBoot.init()` in `@BeforeAll` für Profile):

```java
package mtgplayer.ai;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.player.Player;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.protocol.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AiConfigTest {
    @BeforeAll static void boot() { ForgeBoot.init(); }

    @Test void profileSindGeladenDefaultZuerst() {
        assertEquals("Default", AiConfig.profiles().get(0));
        assertTrue(AiConfig.profiles().containsAll(java.util.List.of("Cautious", "Reckless", "Experimental")));
    }

    @Test void parseSpecs() {
        assertEquals(new AiConfig(AiConfig.Mode.SIM, "Reckless"), AiConfig.parse("sim:Reckless"));
        assertEquals(AiConfig.DEFAULT, AiConfig.parse("std"));
        assertEquals(new AiConfig(AiConfig.Mode.HYBRID, "Cautious"), AiConfig.parse("hybrid:Cautious"));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.parse("turbo"));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.parse("sim:Nope"));
    }

    @Test void fromJsonUndTimeout() {
        JsonNode msg = Json.parse("{\"aiTimeout\":12,\"opponents\":[{\"precon\":\"x\",\"ai\":{\"mode\":\"sim\"}},{\"precon\":\"y\"}]}");
        assertEquals(new AiConfig(AiConfig.Mode.SIM, "Default"), AiConfig.fromJson(msg.path("opponents").get(0).path("ai")));
        assertEquals(AiConfig.DEFAULT, AiConfig.fromJson(msg.path("opponents").get(1).path("ai")));
        assertEquals(12, AiConfig.timeout(msg));
        assertEquals(5, AiConfig.timeout(Json.parse("{}")));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.timeout(Json.parse("{\"aiTimeout\":0}")));
        assertThrows(IllegalArgumentException.class, () -> AiConfig.fromJson(Json.parse("{\"profile\":\"Nope\"}")));
    }

    @Test void lobbyPlayerTraegtOptionUndProfil() {
        LobbyPlayerAi lp = new AiConfig(AiConfig.Mode.SIM, "Reckless").newLobbyPlayer("KI 1");
        assertEquals("Reckless", lp.getAiProfile());
        // Option ist privat – ueber einen erzeugten Controller pruefen (createIngamePlayer braucht ein Game).
        Game game = new forge.game.Match(mtgplayer.match.CommanderRules.create(), java.util.List.of(), "t").createGame();
        Player p = lp.createIngamePlayer(game, 0);
        assertTrue(((PlayerControllerAi) p.getController()).getAi().usesFullSimulation());
        Player q = AiConfig.DEFAULT.newLobbyPlayer("KI 2").createIngamePlayer(game, 1);
        assertFalse(((PlayerControllerAi) q.getController()).getAi().usesFullSimulation());
        assertFalse(((PlayerControllerAi) q.getController()).getAi().usesHybridSimulation());
    }
}
```
Falls `Match.createGame()` mit leerer Spielerliste wirft: `RegisteredPlayer.forCommander(Precons.load("Abzan Armor [TDC] [2025]"))` mit `rp.setPlayer(lp)` registrieren und den Spieler über `game.getPlayers().get(0)` holen – im Report vermerken.

- [ ] **Step 2: Test laufen lassen** – `cd bridge && mvn -q test -Dtest=AiConfigTest` → Kompilierfehler (Klasse fehlt).

- [ ] **Step 3: `AiConfig` implementieren** (Jackson `JsonNode`, `forge.ai.AIOption`, `forge.ai.AiProfileUtil`, `forge.ai.LobbyPlayerAi`):

```java
package mtgplayer.ai;

import com.fasterxml.jackson.databind.JsonNode;
import forge.ai.AIOption;
import forge.ai.AiProfileUtil;
import forge.ai.LobbyPlayerAi;
import java.util.*;

/** KI-Einstellungen eines Sitzes: Forge-Modus (Standard/Hybrid/Voll-Simulation) und Profil (res/ai/*.ai). */
public record AiConfig(Mode mode, String profile) {
    public enum Mode {
        STANDARD, HYBRID, SIM;
        public String json() { return name().toLowerCase(Locale.ROOT); }
        public static Mode parse(String s) {
            return switch (s == null ? "" : s.trim().toLowerCase(Locale.ROOT)) {
                case "standard", "std", "" -> STANDARD;
                case "hybrid" -> HYBRID;
                case "sim", "simulation" -> SIM;
                default -> throw new IllegalArgumentException("Unbekannter KI-Modus: " + s);
            };
        }
    }
    public static final AiConfig DEFAULT = new AiConfig(Mode.STANDARD, "Default");
    public static final int DEFAULT_TIMEOUT = 5, MIN_TIMEOUT = 1, MAX_TIMEOUT = 60;

    public AiConfig {
        Objects.requireNonNull(mode);
        if (!profiles().contains(profile)) throw new IllegalArgumentException("Unbekanntes KI-Profil: " + profile);
    }

    public static List<String> profiles() {
        List<String> all = new ArrayList<>(AiProfileUtil.getAvailableProfiles());
        Collections.sort(all);
        if (all.remove("Default")) all.add(0, "Default");
        return all;
    }
    public static List<String> modes() { return Arrays.stream(Mode.values()).map(Mode::json).toList(); }

    public static AiConfig parse(String spec) {
        String[] parts = spec.split(":", 2);
        return new AiConfig(Mode.parse(parts[0]), parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : "Default");
    }
    public static AiConfig fromJson(JsonNode ai) {
        if (ai == null || ai.isMissingNode() || ai.isNull()) return DEFAULT;
        return new AiConfig(Mode.parse(ai.path("mode").asText("standard")), ai.path("profile").asText("Default"));
    }
    public static int timeout(JsonNode msg) {
        int t = msg.path("aiTimeout").asInt(DEFAULT_TIMEOUT);
        if (t < MIN_TIMEOUT || t > MAX_TIMEOUT) throw new IllegalArgumentException("KI-Bedenkzeit muss 1–60 s sein");
        return t;
    }
    public LobbyPlayerAi newLobbyPlayer(String name) {
        Set<AIOption> opts = switch (mode) {
            case STANDARD -> null;
            case HYBRID -> Set.of(AIOption.USE_HYBRID_SIMULATION);
            case SIM -> Set.of(AIOption.USE_FULL_SIMULATION);
        };
        LobbyPlayerAi lp = new LobbyPlayerAi(name, opts);
        lp.setAiProfile(profile);
        return lp;
    }
    public String spec() { return mode.json() + ":" + profile; }
}
```
Prüfen, dass `AiProfileUtil.getAvailableProfiles()` nach `ForgeBoot.init()` Profile liefert (FModel lädt sie aus `ForgeConstants.AI_PROFILE_DIR`); falls leer: in `ForgeBoot.init` nach `FModel.initialize` `AiProfileUtil.loadAllProfiles(ForgeConstants.AI_PROFILE_DIR)` aufrufen.

- [ ] **Step 4: Sitze und Timeout einbauen.** `HumanMatch`: neue Signaturen, `rp.setPlayer(aiConfigs.get(i).newLobbyPlayer(aiNames.get(i)))`; vor `hosted.startMatch` `FModel.getPreferences().setPref(FPref.MATCH_AI_TIMEOUT, String.valueOf(aiTimeout));` (Kommentar: HostedMatch liest die Preference beim Spielstart; nicht `save()`). `AiMatch.play`: neue Signatur, nach `match.createGame()` `game.AI_TIMEOUT = aiTimeout;`. Alte Signaturen als Überladung (`Collections.nCopies(n, AiConfig.DEFAULT)`, `AiConfig.DEFAULT_TIMEOUT`).

- [ ] **Step 5: Protokoll.** `Messages.Lobby(String type, List<String> precons, List<String> decks, List<String> aiModes, List<String> aiProfiles, int aiTimeout)` – Konstruktor-Überladung `(precons, decks)` füllt `AiConfig.modes()`, `AiConfig.profiles()`, 5. `Bridge.startGame`: je Gegner `configs.add(AiConfig.fromJson(o.path("ai")))`, `int timeout = AiConfig.timeout(msg)` – beides im vorhandenen `try` (IllegalArgumentException → `ErrorMsg`), an `match.start(...)`/`startSpectator(...)` durchreichen. Javadoc-Beispiel um `"ai":{"mode":"sim","profile":"Reckless"}` und `"aiTimeout":10` ergänzen.

- [ ] **Step 6: E2E-Test** in `BridgeEndToEndTest` (Muster von `lobbyStartKeepPrioConcede` übernehmen, Port bleibt 18081):

```java
@Test
void aiConfigUndTimeoutWerdenAngenommenUnbekanntesProfilAbgelehnt() throws Exception {
    send("{\"type\":\"startGame\",\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
        + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\",\"ai\":{\"mode\":\"sim\",\"profile\":\"Nope\"}}]}");
    JsonNode err = awaitType("error");
    assertTrue(err.path("text").asText().contains("Nope"), err.toString());
    send("{\"type\":\"startGame\",\"aiTimeout\":3,\"humanDeck\":{\"precon\":\"Abzan Armor [TDC] [2025]\"},"
        + "\"opponents\":[{\"precon\":\"Adaptive Enchantment [C18] [2018]\",\"name\":\"KI 1\",\"ai\":{\"mode\":\"hybrid\",\"profile\":\"Cautious\"}}]}");
    JsonNode state = awaitType("state");   // Spiel laeuft an
    assertEquals(2, state.path("players").size());
    send("{\"type\":\"concede\"}");
    awaitType("gameOver");
}
```
Vorhandene Hilfen (`awaitType` o. ä.) der Testklasse nutzen; Namen anpassen, falls sie anders heißen. Lobby-Nachricht-Assertion im bestehenden Lobby-Test: `aiProfiles` enthält `Default`.

- [ ] **Step 7: Suite** – `mvn -q test` (alle grün, Laufzeit wie bisher + ~1 min).

- [ ] **Step 8: Commit** – `feat: ki-modus und profil je sitz, ki-bedenkzeit, protokoll ai/aiTimeout`.

---

### Task 2: Bench – `Bench`, `BenchStats`, CLI

**Files:**
- Create: `bridge/src/main/java/mtgplayer/bench/BenchStats.java`, `bridge/src/main/java/mtgplayer/bench/Bench.java`, `bridge/src/main/java/mtgplayer/bench/BenchArgs.java`
- Modify: `bridge/src/main/java/mtgplayer/Main.java` (`--bench`), `README.md` (Abschnitt „Bench")
- Test: `bridge/src/test/java/mtgplayer/bench/BenchStatsTest.java`, `bridge/src/test/java/mtgplayer/bench/BenchArgsTest.java`, `bridge/src/test/java/mtgplayer/bench/BenchSmokeTest.java`

**Interfaces:**
- Consumes: `AiConfig`, `AiMatch.play(decks, names, configs, timeout, maxTurns, log)`, `Precons.load/names`, `DeckStore` (für `saved:`), `forge.util.MyRandom.setRandom`.
- Produces:
```java
public record GameRecord(int index, long seed, String firstSeat /* "A"|"B"|"?" */, String winner /* "A"|"B"|null */, String reason, int turns, long millis) {}
public record Summary(int games, int winsA, int winsB, int draws, int drawsByTurnCap, double winRateA, double ciLow, double ciHigh, double avgTurns, double medianTurns, double avgMillis) {}
public final class BenchStats { public static Summary summarize(List<GameRecord> games); public static double[] wilson(int wins, int n, double z /*1.96*/); }
public record BenchArgs(int games, AiConfig a, AiConfig b, String deckA, String deckB, int turns, int timeout, long seed, Path out) { public static BenchArgs parse(String[] argsAfterBench); }
public final class Bench { public static Summary run(BenchArgs args, PrintStream progress); /* schreibt md+json, liefert Summary */ }
```

- [ ] **Step 1: Tests `BenchStatsTest` und `BenchArgsTest`:**

```java
class BenchStatsTest {
    private static GameRecord g(int i, String w, int turns) { return new GameRecord(i, 1 + i, i % 2 == 0 ? "A" : "B", w, w == null ? "Draw" : "AllOpponentsLost", turns, 1000); }

    @Test void wilsonBekannteWerte() {
        double[] ci = BenchStats.wilson(30, 40, 1.96);      // p = 0.75 → ca. [0.597, 0.859]
        assertEquals(0.597, ci[0], 0.005); assertEquals(0.859, ci[1], 0.005);
        double[] none = BenchStats.wilson(0, 0, 1.96);
        assertEquals(0, none[0]); assertEquals(1, none[1]);
    }
    @Test void summaryZaehltSiegeUnentschiedenUndZuege() {
        var s = BenchStats.summarize(List.of(g(0, "A", 20), g(1, "B", 30), g(2, "A", 40), g(3, null, 200)));
        assertEquals(4, s.games()); assertEquals(2, s.winsA()); assertEquals(1, s.winsB()); assertEquals(1, s.draws());
        assertEquals(2.0 / 3, s.winRateA(), 1e-9);          // nur entschiedene Spiele
        assertEquals(72.5, s.avgTurns(), 1e-9); assertEquals(35, s.medianTurns(), 1e-9);
    }
}
class BenchArgsTest {
    @BeforeAll static void boot() { ForgeBoot.init(); }
    @Test void defaultsUndParsing() {
        BenchArgs d = BenchArgs.parse(new String[0]);
        assertEquals(40, d.games()); assertEquals(AiConfig.parse("sim"), d.a()); assertEquals(AiConfig.DEFAULT, d.b());
        assertEquals(200, d.turns()); assertEquals(5, d.timeout());
        BenchArgs p = BenchArgs.parse("--games 3 --a hybrid:Cautious --b std:Reckless --deck-a precon:Abzan Armor [TDC] [2025] --turns 30 --timeout 2 --seed 7 --out /tmp/x".split(" (?=--)"));
        assertEquals(3, p.games()); assertEquals("precon:Abzan Armor [TDC] [2025]", p.deckA()); assertEquals(7, p.seed()); assertEquals(Path.of("/tmp/x"), p.out());
        assertThrows(IllegalArgumentException.class, () -> BenchArgs.parse(new String[] {"--games", "0"}));
    }
}
```
(`BenchArgs.parse` bekommt die Argumente paarweise `--key value`; Werte mit Leerzeichen kommen als ein Argument – im Test deshalb der Split an ` --`. Im `Main` kommen sie aus `exec.args` bereits getrennt; Precon-Namen mit Leerzeichen müssen in `-Dexec.args` in Anführungszeichen stehen – README-Hinweis.)

- [ ] **Step 2: Laufen lassen → Kompilierfehler.**

- [ ] **Step 3: Implementieren.** `BenchStats.wilson`: `p = wins/n; denom = 1 + z²/n; center = (p + z²/(2n))/denom; half = z*sqrt(p(1-p)/n + z²/(4n²))/denom` → `[max(0, center-half), min(1, center+half)]`; `n == 0` → `[0, 1]`. `summarize`: Draws = `winner == null`, `drawsByTurnCap` = `reason` enthält „Draw" und `turns >= turns-Deckel`? – der Deckel ist im Record nicht bekannt → `reason` aus `AiMatch` ist `"Draw"` bei Zugdeckel; zähle `drawsByTurnCap` = Draws mit `reason.equals("Draw")` (Kommentar: AiMatch beendet nur per Zugdeckel mit Draw). Median über sortierte Züge.
  `BenchArgs.parse`: Schleife über Paare; Deck-Refs `precon:<Name>` | `saved:<Name>`; Defaults `deckA/deckB` = erste zwei `Precons.names()` sortiert; `--out` Default `ForgeBoot.dataDir().resolve("bench")` (prüfen, dass `dataDir()` `~/.mtg-player` ist).
  `Bench.run`: Decks laden (`Precons.load` bzw. `new DeckStore(...)`-Pfad wie `DeckSource`), Schleife `i < games`: `MyRandom.setRandom(new Random(seed + i))`; Sitzreihenfolge `[A,B]` bei geradem i, `[B,A]` bei ungeradem (Namen „A"/„B" als Spielernamen, damit `Result.winner()` direkt „A"/„B" ist); `firstSeat` = Name des Spielers, der Zug 1 hatte – aus der ersten Logzeile mit „Turn 1" (Forge-Log: `Turn 1 (A)`), sonst `"?"`; Zeit messen; `progress.printf("#%d %s (Zug %d, %d s)  A %d – B %d – U %d%n", …)`. Shutdown-Hook, der bei Abbruch die bisherigen Records schreibt (Flag, damit nicht doppelt geschrieben wird). Am Ende `writeReports(out, args, records, summary)`: Markdown

  ```
  # Bench <a> vs <b>
  Decks: A = …, B = … · Spiele: N · Zugdeckel: 200 · Timeout: 5 s · Seed: 1 · Datum
  | | A | B | Unentschieden |
  |---|---|---|---|
  | Siege | 23 | 15 | 2 (Zugdeckel 2) |
  Siegquote A (entschiedene Spiele): 60,5 % · 95-%-Intervall 44,7–74,4 % · Ø Züge 41,3 (Median 39) · Ø Dauer 71 s
  ## Spiele
  | # | Seed | Erster | Sieger | Grund | Züge | Dauer s |
  ```
  und JSON (`{args, summary, games}`) via `Json` (Jackson) – Dateiname `yyyy-MM-dd-HHmm-<a.spec()>-vs-<b.spec()>` mit `:` → `-`.
  `Main`: `if (args[0].equals("--bench")) { Bench.run(BenchArgs.parse(Arrays.copyOfRange(args, 1, args.length)), System.out); return; }` (nach `ForgeBoot.init()`).

- [ ] **Step 4: `BenchSmokeTest`** (`@Timeout(6, MINUTES)`): `BenchArgs` mit `games=2, turns=30, timeout=2, seed=1, out=@TempDir`, `a = sim:Default`, `b = std:Default`, Precons `Abzan Armor [TDC] [2025]` / `Adaptive Enchantment [C18] [2018]` → `Summary.games()==2`, im TempDir je eine `.md` und `.json`, Markdown enthält „Siegquote".

- [ ] **Step 5: README** Abschnitt „Bench (KI gegen KI)": Aufruf `cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1"`, Optionstabelle aus der Spec, Ausgabeort, Hinweis „läuft Minuten bis Stunden; Ctrl-C schreibt den Zwischenstand".

- [ ] **Step 6: Suite grün, Commit** – `feat: bench – n spiele ki gegen ki mit seed, wilson-intervall, markdown und json`.

---

### Task 3: Frontend – Lobby-Einstellungen

**Files:**
- Modify: `web/src/protocol.ts`, `web/src/lobbyPayload.ts`, `web/src/components/Lobby.tsx`, `web/src/store.ts` (Lobby-Felder `aiModes/aiProfiles/aiTimeout`), `web/src/styles.css` (Lobby-Zeile), `web/fixtures/lobby.json`
- Create: `web/src/aiSettings.ts` (+ Test) – localStorage-Laden/Speichern, rein testbar mit injiziertem Storage
- Test: `web/src/lobbyPayload.test.ts`, `web/src/aiSettings.test.ts`

**Interfaces:**
```ts
export interface AiPick { mode: "standard" | "hybrid" | "sim"; profile: string }
export const DEFAULT_AI: AiPick = { mode: "standard", profile: "Default" };
export function buildStartGame(spectate, human, ais, aiPicks: AiPick[], aiTimeout: number): Outbound | undefined
// aiSettings.ts
export interface AiSettings { picks: AiPick[]; timeout: number }
export function loadAiSettings(storage: Pick<Storage,"getItem">, profiles: string[]): AiSettings   // ungültig → Defaults; unbekanntes Profil → "Default"
export function saveAiSettings(storage: Pick<Storage,"setItem">, s: AiSettings): void              // Key "mtg.lobby.ai"; try/catch
```

- [ ] **Step 1: Tests.** `lobbyPayload.test.ts` ergänzen: (a) Standard-Picks + Timeout 5 → Payload ohne `ai`/`aiTimeout` (bestehende Erwartungen unverändert); (b) `[{mode:"sim",profile:"Reckless"}, DEFAULT_AI]`, Timeout 10 → `opponents[0].ai = {mode:"sim",profile:"Reckless"}`, `opponents[1]` ohne `ai`, `aiTimeout: 10`. `aiSettings.test.ts`: leeres Storage → Defaults; gespeichertes JSON mit unbekanntem Profil → `Default`; `getItem` wirft → Defaults; `saveAiSettings` schreibt JSON.

- [ ] **Step 2: Laufen lassen → fehlschlagen.**

- [ ] **Step 3: Implementieren.** `protocol.ts`: `Lobby { …; aiModes?: string[]; aiProfiles?: string[]; aiTimeout?: number }`, `startGame` `opponents: (DeckRef & { name: string; ai?: AiPick })[]`, `aiTimeout?: number`. `store.ts`: Felder übernehmen (Defaults `["standard","hybrid","sim"]`, `["Default"]`, 5). `Lobby.tsx`: State `aiPicks: AiPick[]` parallel zu `ais` (beim Hinzufügen/Entfernen eines Slots mitführen), `aiTimeout`; beim ersten `lobby`-Empfang `loadAiSettings(localStorage, aiProfiles)`; bei jeder Änderung `saveAiSettings`. Je KI-Slot hinter dem Deck-Picker: `<select title="Standard: Forges Regel-KI. Hybrid: simuliert nur die Zauberwahl. Simulation: rechnet Züge vor – stärker, langsamer">` mit Optionen `standard → "Standard"`, `hybrid → "Hybrid"`, `sim → "Simulation"`, und `<select>` Profil. Oberhalb der Slots: `KI-Bedenkzeit <input type="number" min=1 max=60> s` mit Hinweis „Simulation braucht 10–20 s". Klassen `.ai-pick` (Flex-Zeile, gap 6px, `select` schmal).
  `fixtures/lobby.json`: `aiModes`, `aiProfiles`, `aiTimeout` ergänzen.

- [ ] **Step 4: Screenshots** `npm run build`, `node scripts/shot-lobby.mjs` (Aufruf im Script nachlesen) bei `VIEWPORT=1280x720` und `1600x900` → `polish-ki/lobby-1280.png`, `lobby-1600.png` im Scratchpad, mit Read ansehen: Dropdowns dunkel, Zeile bricht bei 1280 nicht in den Start-Button. `npm run layout-check` (Tisch-Fixtures, unverändert) grün.

- [ ] **Step 5: `npm test`, Build, Commit** – `ui: lobby – ki-modus und profil je slot, ki-bedenkzeit, auswahl im localStorage`.

---

### Task 4: Messlauf Sim vs. Standard

**Files:**
- Create: `docs/bench/2026-09-19-sim-vs-standard.md`
- Modify: `README.md` („Offen"/„KI"-Abschnitt: Verweis auf den Bench-Bericht)

- [ ] **Step 1:** `cd bridge && mvn -q compile exec:java -Dexec.args="--bench --games 40 --a sim:Default --b std:Default --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' --seed 1 --timeout 5"` – im Hintergrund starten (`run_in_background`, Log in Datei), Laufzeit erwartungsgemäß 1–3 h; Kevins Bridge auf 8080/8081 läuft parallel weiter (getrennter JVM, kein Port). Fortschritt gelegentlich prüfen.
- [ ] **Step 2:** Bench-Markdown nach `docs/bench/2026-09-19-sim-vs-standard.md` kopieren, darunter „Einordnung" (3–5 Sätze): Siegquote, Intervall, Entscheidungsregel aus der Spec (Untergrenze > 50 % → Stufe 2 lohnt; sonst nächster Schritt Hybrid/Profile), Auffälligkeiten (Timeouts im Log? Unentschieden?).
- [ ] **Step 3:** README-Verweis, Commit `docs: bench sim vs standard, 40 spiele`.

---

## Abschluss (Controller)

Whole-Branch-Review (Bridge-Threading: Preference-Setzen vor `startMatch` auf dem UI-Thread; `MyRandom` global; Shutdown-Hook), Trailer prüfen, `git checkout main && git merge --ff-only feature/ki-paket-1 && git push`, Ledger und Memory.
