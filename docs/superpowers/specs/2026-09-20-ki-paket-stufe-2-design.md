# KI-Paket Stufe 2 – Fork: Simulation benutzbar machen – Design

Stand: 2026-09-20. Grundlage: `docs/bench/2026-09-20-sim-vs-standard.md` (Sim-KI gewinnt Spiegel ~76 %,
aber 28 % Ausfälle). Fork `Haste-MC/forge`, Branch `mtg-player`, Version `2.0.14-mtgplayer` (eingerichtet,
`docs/forge-fork.md`). Reihenfolge von Kevin bestätigt: Zeitbudget → GameCopier → Nichtstun-Schwäche →
(erst danach) Bewertung.

## Ziel

Die Voll-Simulation (`USE_FULL_SIMULATION`) beendet jede Entscheidung innerhalb der eingestellten
KI-Bedenkzeit, stürzt in Commander-Spielen nicht mehr an Monarch/Planeswalkern ab, und ihre Nichtstun-Schwäche
ist verstanden (und wenn billig, behoben). Jeder Schritt wird mit dem Bench nachgewiesen.

## 1. Zeitbudget für die Simulation (`forge-ai`)

Befund: `SpellAbilityPicker.chooseSpellAbilityToPlay` läuft ohne Zeitlimit; `AiController` umgeht für den
Sim-Pfad den `FutureTask`-Timeout. Eine Entscheidung dauerte im Bench > 30 min.

Design:
- `SimulationController` bekommt eine Deadline (`long deadlineNanos`, `isOutOfTime()`), gesetzt beim Anlegen
  in `SpellAbilityPicker.createNewPlan` aus `game.getAITimeout()` Sekunden – **ein** Budget für die ganze
  Entscheidung, auch wenn `formulatePlanWithPhase` zweimal läuft (Phase null und COMBAT_DECLARE_BLOCKERS teilen
  sich die Deadline; die zweite Runde bekommt nur den Rest).
- Prüfpunkte: (a) Kandidatenschleife in `chooseSpellAbilityToPlayImpl` – nach Ablauf keine weiteren Kandidaten,
  aber mindestens **ein** Kandidat wird immer vollständig bewertet (sonst wäre der Modus bei kleinem Budget
  gleich Standard-Passen); (b) `shouldRecurse()` liefert nach Ablauf `false` (keine tieferen Ebenen);
  (c) Ziel-/Modus-Iteration in `evaluateSa` (`MultiTargetSelector`, Modi) bricht nach Ablauf ab.
  Ergebnis ist immer der beste **bisher** bewertete Zug – nie ein Abbruch ohne Ergebnis.
- Sichtbarkeit: die bestehende `print("BEST: … TIME: …")` bekommt den Zusatz `(Budget erschöpft, n von m
  Kandidaten)`; im Spiel-Log erscheint nichts (kein Rauschen).
- Rekursive Aufrufe (`controller != null`) prüfen dieselbe Deadline.
- Der Standard-/Hybrid-Pfad bleibt unverändert (FutureTask-Timeout wie bisher).

Nachweis: Spiegel-Bench Ahoy Mateys, 40 Seeds, `--timeout 10 --game-timeout 10`: **0 Timeouts**, Siegquote
mit Untergrenze > 50 %. Zusätzlich Abzan-Spiegel 10 Spiele (das Board, das vorher 30 min hing): alle Spiele
enden.

## 2. `GameCopier` robust (`forge-ai`, `forge-game`)

Befunde:
- `Couldn't map The Monarch (n)`: der Monarch-Effekt (`Player.monarchEffect`, Kommandozone,
  `GamePieceType.EFFECT`) wird von `copyEffectCardsToSnapshot` gemappt, ist aber nicht in `cardMap` – Ursache
  im Copier finden (vermutlich überspringt `addCard`/`createCardCopy` Effekt-Karten oder die Zone der
  Effektkarte wird vor dem Mapping durch `getZone()` anders gesehen) und beheben; Ziel: die Effektkarte wird
  wie andere Kommandozonen-Karten kopiert und gemappt.
- `Player.getMonarchSet()` hat die Bedingung verdreht (`monarchEffect == null ? monarchEffect.getSetCode() :
  null` → NPE bzw. immer null) – beheben.
- `Couldn't map <Nothing>`: `Combat.removeFromCombat` legt beim Entfernen eines angegriffenen
  Planeswalkers/Battles eine Fake-Karte `<Nothing>` (id −1) als Verteidiger an; `Combat(Combat, IEntityMap)`
  versucht sie zu mappen. Fix im Kopier-Konstruktor: ein Verteidiger, der eine `<Nothing>`-Fake-Karte ist,
  bekommt in der Kopie eine frische Fake-Karte (gleiche Konstruktion wie in `removeFromCombat`) statt eines
  Map-Lookups. Alternativ `GameCopier.find`: Karten mit id −1 und Name `<Nothing>` → neue Fake-Karte.
- `Simulation: SA not found! …` (Bench-Log): notieren, was es ist; beheben nur, wenn es zu Abstürzen führt.

Nachweis: Bench Abzan(sim) vs Adaptive Enchantment(std) 20 Spiele (Estrid-Planeswalker) und Ahoy-Spiegel
20 Spiele: **0 Abstürze, 0 „Sim defekt"**. Bridge-Test: ein `AiMatch`-Spiel gegen Adaptive Enchantment mit
Sim-KI und Zugdeckel 40 läuft ohne Ausnahme.

## 3. Nichtstun-Schwäche (Untersuchung, `forge-ai`)

Befund: in verlorenen Spielen spielt die Sim-KI 1–5 Zauber bei 3–6 Ländern und wirft Karten ab (z. B.
Seed 40, Ahoy-Spiegel Teil 2, Spiel 21). Verdacht: `chooseSpellAbilityToPlayImpl` verwirft den besten Zug,
wenn `availableValue` nicht steigt (Kommentar „hold off on plays that only add unavailable resources … before
MAIN2") – Kreaturen mit Summoning Sickness zählen vor Main 2 nicht, in Main 2 könnte der Plan-Mechanismus
(`getPlannedSpellAbility`) den Zug dann nicht mehr vorsehen; oder die Bewertung in `GameStateEvaluator`
schätzt Handkarten höher als das Spielen.

Vorgehen: Seed 40 (und zwei weitere Niederlagen) mit `GameSimulator.debugPrint`/`print`-Ausgabe der Picker-
Entscheidungen nachspielen (Bridge `--bench-one` mit einer Debug-Property, die `SpellAbilityPicker`-Ausgabe
einschaltet), die Züge protokollieren, in denen spielbare Karten gehalten wurden, und die Ursache benennen.
Ist der Fix klein und klar (z. B. `availableValue`-Regel nur anwenden, wenn in Main 2 noch Mana bleibt),
umsetzen und per Spiegel-Bench (40 Seeds) gegen den Stand nach Schritt 2 messen. Sonst: Befund in
`docs/bench/` und Vorschlag für die Bewertungsarbeit.

## Bridge-Anpassungen

- Bench: `--timeout` gilt jetzt auch für die Simulation (README/Lobby-Hinweis anpassen: „Bedenkzeit gilt
  für alle Modi"). Lobby-Hinweis „Simulation rechnet ohne Zeitlimit" entfällt.
- `docs/forge-fork.md`: jede Fork-Änderung mit Commit, Datei, Grund.

## Tests

- Forge-Änderungen werden über die Bridge getestet (der Fork wird als Jar konsumiert): neue Tests in
  `bridge/src/test/java/mtgplayer/ai/` – (1) `SimBudgetTest`: ein `AiMatch`-Spiel Abzan-Spiegel, Sim auf A,
  `aiTimeout = 3`, Zugdeckel 20 – Wanddauer pro Spielerzug im Mittel < 4 × Budget (grobe Schranke; der
  Test misst die Gesamtdauer / Züge); (2) `SimCopierTest`: Sim-KI gegen Adaptive Enchantment, Zugdeckel 40,
  keine Ausnahme, und ein Spiel mit Monarch-Karte (Ahoy-Spiegel, Seed 5 – der Absturz-Seed) ohne Ausnahme.
- Forge selbst: `mvn -q -pl forge-ai -am install -DskipTests …` muss bauen; Forges eigene Sim-Tests
  (`forge-gui-desktop/src/test/java/forge/ai/simulation/*`) laufen, wenn sie in < 10 min laufen – sonst
  dokumentiert überspringen.
- Bench-Läufe wie oben als Nachweis, Ergebnisse nach `docs/bench/2026-09-2x-stufe-2-*.md`.

## Nicht in dieser Stufe

Bewertungsänderungen (`GameStateEvaluator`), gelernte Bewertung, 4er-Pod-Bench, Upstream-PRs (Patches werden
aber so geschrieben, dass sie upstream-tauglich wären: kein Bridge-Wissen in Forge).
