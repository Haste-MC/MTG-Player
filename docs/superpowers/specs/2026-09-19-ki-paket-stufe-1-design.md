# KI-Paket Stufe 1 – Simulations-KI, Profile, Timeout, Bench – Design

Stand: 2026-09-19. Kevins Beschluss (Sidechat): „Ein KI-Paket nach M5/M6, in zwei Stufen. Ohne Fork:
Simulations-Modus + Profil pro Slot in der Lobby, Timeout einstellbar. Dazu AiMatch als Bench: N Spiele
Sim-KI vs. Standard-KI headless, Siegquote messen." Stufe 2 (Fork, `GameStateEvaluator`, Threat-Assessment)
nur, wenn der Bench aus Stufe 1 überzeugt. Bench-Matchup zum Start: **1-gegen-1** (Beschluss A).

## Ziel

Jeder KI-Sitz bekommt einen wählbaren **Modus** und ein **Profil**, die KI-Bedenkzeit ist einstellbar, und
ein headless **Bench** misst reproduzierbar, ob die Simulations-KI gegen die Standard-KI gewinnt.
Forge bleibt unverändert (Submodule 2.0.14).

## Forge-Bausteine (vorhanden)

| Baustein | Forge-API |
|---|---|
| Modus | `new LobbyPlayerAi(name, Set<AIOption>)`: `null` = Standard, `USE_HYBRID_SIMULATION` (Simulation nur für Zauber-Wahl), `USE_FULL_SIMULATION` (auch Angriff/Block/Ziele/Modi) |
| Profil | `LobbyPlayerAi.setAiProfile("Default"\|"Cautious"\|"Reckless"\|"Experimental")`; Dateien `forge-gui/res/ai/*.ai`, geladen durch `FModel.initialize` → `AiProfileUtil.loadAllProfiles` (prüfen, dass das über `bridge/assets/res` greift; sonst in `ForgeBoot.init` nachladen) |
| Timeout | `Game.AI_TIMEOUT` (Sekunden, Default 5); `HostedMatch.startGame` überschreibt ihn aus `FPref.MATCH_AI_TIMEOUT`, `AiMatch` (Match direkt) nicht |
| Zufall | `forge.util.MyRandom.setRandom(Random)` – global, vor jedem Spiel setzbar |

## Bridge

### `mtgplayer.ai.AiConfig`

```java
public record AiConfig(Mode mode, String profile) {
  public enum Mode { STANDARD, HYBRID, SIM }             // JSON/CLI-Namen: standard | hybrid | sim
  public static final AiConfig DEFAULT = new AiConfig(Mode.STANDARD, "Default");
  public static List<String> profiles();                  // AiProfileUtil.getAvailableProfiles(), sortiert, "Default" zuerst
  public static AiConfig parse(String spec);              // "sim:Reckless", "std", "hybrid:Cautious" – unbekannt → IllegalArgumentException
  public static AiConfig fromJson(JsonNode ai);           // {"mode":"sim","profile":"Reckless"}; fehlende Felder → DEFAULT-Werte
  public LobbyPlayerAi newLobbyPlayer(String name);       // Set<AIOption> aus mode, setAiProfile(profile)
}
```

`HumanMatch.start/startSpectator` und `AiMatch.play` bekommen je KI-Sitz eine `AiConfig` (Liste parallel zu
Decks/Namen) und bauen die `LobbyPlayerAi` darüber. Vorhandene Aufrufer übergeben `AiConfig.DEFAULT`.

### Timeout

- `HumanMatch`: vor `startMatch` `FModel.getPreferences().setPref(FPref.MATCH_AI_TIMEOUT, String.valueOf(sek))`
  (HostedMatch liest die Preference beim Spielstart; nicht persistieren – `save()` nicht aufrufen).
- `AiMatch`: nach `match.createGame()` `game.AI_TIMEOUT = sek`.
- Bereich 1–60 s, Standard 5; außerhalb → `IllegalArgumentException` mit Text für die Lobby.

### Protokoll

- `startGame.opponents[i].ai?: { mode?: "standard"|"hybrid"|"sim", profile?: string }` – fehlt → Standard/Default.
- `startGame.aiTimeout?: number` (Sekunden) – fehlt → 5.
- `lobby`-Nachricht zusätzlich: `aiModes: ["standard","hybrid","sim"]`, `aiProfiles: string[]`, `aiTimeout: 5`
  (Standardwert für das Feld).
- Unbekanntes Profil oder Modus → `error`-Nachricht, kein Spielstart.

### Bench (`mtgplayer.bench`)

CLI in `Main`: `--bench [Optionen]`

| Option | Bedeutung | Standard |
|---|---|---|
| `--games N` | Zahl der Spiele | 40 |
| `--a spec` / `--b spec` | KI-Sitze, `AiConfig.parse` | `sim:Default` / `std:Default` |
| `--deck-a ref` / `--deck-b ref` | Deck: `precon:<Name>` oder `saved:<Name>` | erste zwei Precons alphabetisch |
| `--turns N` | Zugdeckel (Spielerzüge, wie `AiMatch`) → Unentschieden | 200 |
| `--timeout s` | KI-Bedenkzeit | 5 |
| `--seed n` | Basis-Seed; Spiel i nutzt `seed + i` | aktuelle Zeit |
| `--out dir` | Ausgabeverzeichnis | `~/.mtg-player/bench/` |

Ablauf: Spiel i: Startspieler = A bei geradem i, B bei ungeradem i (Sitzreihenfolge in `RegisteredPlayer`-Liste;
Forge lost den Startspieler zusätzlich – deshalb Seed setzen und die tatsächliche Reihenfolge aus dem Log/`Game`
mitschreiben). `MyRandom.setRandom(new Random(seed + i))` vor `AiMatch.play`. Pro Spiel: Sieger (A/B/–), Grund,
Züge, Dauer ms. Nach jedem Spiel eine Zeile auf stdout (`#12 A gewinnt (Zug 37, 84 s)  A 7 – B 5 – U 0`).

Auswertung (`BenchStats`, rein, getestet): Siege A/B, Unentschieden, Siegquote A unter den entschiedenen Spielen
mit 95-%-Wilson-Intervall, Ø/Median Züge, Ø Dauer, Anzahl Unentschieden durch Zugdeckel. Ausgabe:
`<out>/<yyyy-MM-dd-HHmm>-<a>-vs-<b>.md` (Tabelle + Parameter + Seed) und `.json` (alle Einzelspiele).

Bench läuft sequenziell im aufrufenden Thread (Forge hat globalen Zustand: `MyRandom`, `FModel`, Profil-Cache).
`Ctrl-C` → bisherige Spiele werden trotzdem geschrieben (Shutdown-Hook).

## Frontend

- `protocol.ts`: `Lobby` um `aiModes`, `aiProfiles`, `aiTimeout` erweitern; `DeckRef & { name, ai? }`;
  `startGame.aiTimeout?`.
- `Lobby.tsx`: je KI-Slot zwei Dropdowns **Modus** (Standard / Hybrid / Simulation, mit Tooltip: „Simulation:
  rechnet Züge vor, langsamer, braucht Bedenkzeit") und **Profil**; oben ein Zahlenfeld **KI-Bedenkzeit (s)**
  (1–60). Auswahl bleibt im `localStorage` (`mtg.lobby.ai`) und wird beim Öffnen wiederhergestellt; unbekannte
  gespeicherte Profile fallen auf `Default` zurück.
- `lobbyPayload.ts`: `buildStartGame(spectate, human, ais, aiConfigs, aiTimeout)` → `opponents[i].ai` nur setzen,
  wenn nicht Standard/Default (Payload bleibt für Bestandstests gleich); `aiTimeout` nur, wenn ≠ 5.
- Styling: Dropdowns im vorhandenen dunklen Stil; Playwright-Screenshot der Lobby (`scripts/shot-lobby.mjs`) bei
  1280×720 und 1600×900, Layout-Check unverändert.

## Tests

- Bridge: `AiConfigTest` (parse/fromJson/Fehler, `newLobbyPlayer` setzt Option und Profil – über
  `LobbyPlayerAi.getAiProfile()` und einen erzeugten Controller `usesFullSimulation()`), `BenchStatsTest`
  (Wilson-Intervall gegen bekannte Werte, Startspieler-Wechsel, Unentschieden-Zählung), `BenchSmokeTest`
  (2 Spiele, `--turns 30`, Precons, schreibt md+json in ein Temp-Verzeichnis; Laufzeit < 3 min), Erweiterung
  `BridgeEndToEndTest`: `startGame` mit `ai` und `aiTimeout` → Spiel startet, Fehlerfall unbekanntes Profil →
  `error`. `AiMatchTest` bleibt grün (Default-Config).
- Web: `lobbyPayload.test.ts` (ai-Feld nur bei Abweichung, aiTimeout nur ≠ 5), Vitest gesamt grün, Build,
  Lobby-Screenshots gelesen.

## Erster Messlauf (Teil der Runde)

`--bench --games 40 --a sim:Default --b std:Default` mit zwei festen Precons (Namen im Plan), Seed 1.
Ergebnis nach `docs/bench/2026-09-19-sim-vs-standard.md` (Kopie der Bench-Ausgabe + zwei Sätze Einordnung).
Entscheidungsregel für Stufe 2: Untergrenze des Wilson-Intervalls > 50 % → Simulation ist messbar besser;
sonst zuerst Hybrid und andere Profile benchen, bevor am Evaluator gearbeitet wird.

## Nicht in dieser Stufe

Bench aus der Lobby, parallele Spiele, 4er-Pod-Bench (Parameter später), Forge-Änderungen.
