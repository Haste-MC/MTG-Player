# Forge-Fork (Haste-MC/forge, Branch `mtg-player`)

Basis: Tag `forge-2.0.14` (Commit a37a865a). Maven-Version `2.0.14-mtgplayer`.

| Commit | Änderung | Warum |
|---|---|---|
| 952c5e1a | Version `2.0.14-mtgplayer` in allen Modul-POMs | Fork-Artefakte getrennt vom Original in `~/.m2` |

Geplant (Stufe 2, siehe `docs/bench/2026-09-20-sim-vs-standard.md`): Zeitbudget für `SpellAbilityPicker`,
`GameCopier` robust (Monarch-Effektkarte, `<Nothing>`-Fake-Karte aus `Combat.removeFromCombat`),
Nichtstun-Schwäche der Simulation.

Upstream-Update: `cd forge && git fetch upstream --tags && git merge forge-<version>` (Konflikte nur in den
POM-Versionen und unseren Patches), dann `mvn versions:set -DnewVersion=<version>-mtgplayer` und Bridge-POM anpassen.
