# Forge-Fork (Haste-MC/forge, Branch `mtg-player`)

Basis: Tag `forge-2.0.14` (Commit a37a865a). Maven-Version `2.0.14-mtgplayer`.

| Commit | Änderung | Warum |
|---|---|---|
| 952c5e1a | Version `2.0.14-mtgplayer` in allen Modul-POMs | Fork-Artefakte getrennt vom Original in `~/.m2` |
| ffe45981 | `forge-ai`: `SimulationController` bekommt eine Deadline (`deadlineNanos`, `isOutOfTime()`, `shouldRecurse()` false nach Ablauf); `SpellAbilityPicker.deadlineFor(Game)` setzt sie aus `AI_TIMEOUT` je Top-Level-Entscheidung (beide `formulatePlanWithPhase`-Runden teilen sich das Budget), Kandidatenschleife bricht nach Ablauf ab (mindestens ein Kandidat wird bewertet), `SpellAbilityChoicesIterator.advance` probiert nach Ablauf keine weiteren Ziele/Modi/Kartenwahlen; `print("BEST: …")` nennt das erschöpfte Budget | Ohne Budget hing eine Sim-Entscheidung im Bench > 30 min; `AI_TIMEOUT <= 0` = kein Limit; Standard-/Hybrid-Pfad unverändert |

Bekannt: `GameSimulator.CHECK_GAME_COPY_SCORE` (nur mit Java-Assertions aktiv) meldet im Abzan-Spiegel
`Game copy error` – eine Kopie in Rekursionstiefe 3 wird anders bewertet als das Original. Das ist ein
Kopiertreue-Problem des `GameCopier` (Stufe 2, Task 2), unabhängig vom Zeitbudget. Die Bridge-Tests laufen
deshalb wie die Produktion (Bench-Kindprozess, Bridge) ohne Assertions (`bridge/pom.xml`, Surefire
`enableAssertions=false`); Task 2 sollte seine Copier-Tests gezielt mit `-ea` laufen lassen.

Geplant (Stufe 2, siehe `docs/bench/2026-09-20-sim-vs-standard.md`):
`GameCopier` robust (Monarch-Effektkarte, `<Nothing>`-Fake-Karte aus `Combat.removeFromCombat`),
Nichtstun-Schwäche der Simulation.

Upstream-Update: `cd forge && git fetch upstream --tags && git merge forge-<version>` (Konflikte nur in den
POM-Versionen und unseren Patches), dann `mvn versions:set -DnewVersion=<version>-mtgplayer` und Bridge-POM anpassen.
