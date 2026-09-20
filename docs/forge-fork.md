# Forge-Fork (Haste-MC/forge, Branch `mtg-player`)

Basis: Tag `forge-2.0.14` (Commit a37a865a). Maven-Version `2.0.14-mtgplayer`.

| Commit | Änderung | Warum |
|---|---|---|
| 952c5e1a | Version `2.0.14-mtgplayer` in allen Modul-POMs | Fork-Artefakte getrennt vom Original in `~/.m2` |
| ffe45981 | `forge-ai`: `SimulationController` bekommt eine Deadline (`deadlineNanos`, `isOutOfTime()`, `shouldRecurse()` false nach Ablauf); `SpellAbilityPicker.deadlineFor(Game)` setzt sie aus `AI_TIMEOUT` je Top-Level-Entscheidung (beide `formulatePlanWithPhase`-Runden teilen sich das Budget), Kandidatenschleife bricht nach Ablauf ab (mindestens ein Kandidat wird bewertet), `SpellAbilityChoicesIterator.advance` probiert nach Ablauf keine weiteren Ziele/Modi/Kartenwahlen; `print("BEST: …")` nennt das erschöpfte Budget | Ohne Budget hing eine Sim-Entscheidung im Bench > 30 min; `AI_TIMEOUT <= 0` = kein Limit; Standard-/Hybrid-Pfad unverändert |
| 7b88bf01 | `forge-ai`: zweite Planrunde (nach Blockern) nur, wenn noch Budget übrig ist; `SimulationController.isPast()` als gemeinsamer Deadline-Test; `DEFAULT_MAX_DEPTH` paketprivat | Review-Befund: die zweite Runde lief sonst mindestens einen vollen Kandidaten ohne Limit |
| 2990f760 | `forge-game`: `Player.mapEffectCard` mappt eine Effektkarte nur, wenn sie wirklich in ihrer Zone liegt (`Zone.remove` löscht `Card.getZone()` nicht – die Monarch-Karte des Vor-Monarchen hing als Zombie im Mapping); `Player.getMonarchSet()` Null-Check richtig herum; `Combat(Combat, IEntityMap)` legt für die `<Nothing>`-Platzhalterkarte aus `removeFromCombat` (angegriffener Planeswalker/Battle verlässt das Spielfeld) eine frische Platzhalterkarte in der Kopie an statt sie zu mappen (`attackableEntries`, `attackedByBands`, `damageMap`). `forge-ai`: `GameCopier.createCardCopy` kopiert bei Karten ohne PaperCard (Effektkarten) auch Trigger, Ersetzungseffekte und Set-Code | Sim-KI stürzte in Commander-Spielen mit Monarch (`Couldn't map The Monarch`) und Planeswalker-Commandern (`Couldn't map <Nothing>`) ab; Bridge-Test `SimCopierTest` |
| 6c84aec7 | `forge-ai`: `SpellAbilityPicker` liest einmalig `-Dforge.ai.sim.debug`; wenn gesetzt, druckt jede Top-Level-Entscheidung Phase/Zug, Hand, jeden bewerteten Kandidaten mit Score, `BEST` und Plan auf stdout (rekursive Picker stumm) | Nachspielen von Bench-Spielen (`--bench-one`), Befund Nichtstun-Schwäche in `docs/bench/2026-09-20-stufe-2-nichtstun.md`; vorher nur per Quelltext-Kommentar (`//printOutput = controller == null`) erreichbar |
| 450c26e5 | `forge-ai`: `GameCopier.makeCopy` überspringt beim Übertragen von `Remembered` jede Karte, die nicht in `cardMap` liegt (vorher nur `getZone() == null`): Token außerhalb des Spielfelds werden von `Zone.add` nicht in die Zonenliste aufgenommen, behalten aber ihr `getZone()` und werden deshalb nie kopiert; Import-Reihenfolge | Sim-KI stürzte mit `Couldn't map Treasure Token` ab, wenn die Effektkarte von Hostage Taker („you may cast that card“) ein von ihr ins Exil geschicktes Treasure-Token erinnerte (Ahoy-Spiegel, Seed 30); `SimCopierTest` Fall (c) |

Bekannt (offen): `GameSimulator.CHECK_GAME_COPY_SCORE` (nur mit Java-Assertions aktiv) meldet im Abzan-Spiegel und
bei Abzan gegen Adaptive Enchantment `Game copy error` – eine Kopie in Rekursionstiefe 2–3 wird anders bewertet als
das Original. Kopiertreue-Problem des `GameCopier`, unabhängig von Zeitbudget und den Monarch/`<Nothing>`-Fixes
(mit `-ea:forge.ai.simulation.GameSimulator` besteht `SimCopierTest` nur im Monarch-Fall). Die Bridge-Tests laufen
deshalb wie die Produktion (Bench-Kindprozess, Bridge) ohne Assertions (`bridge/pom.xml`, Surefire
`enableAssertions=false`). Kandidat für die Bewertungsarbeit.

`Simulation: SA not found!` (im Bench-Log gesehen): `GameSimulator` findet nach dem Kopieren die zu simulierende
Fähigkeit auf der Kopie nicht (z. B. Escape aus dem Friedhof) und überspringt sie – kein Absturz, nur eine
nicht bewertete Option.

Nichtstun-Schwäche der Simulation (Stufe 2, Schritt 3): untersucht, kein Bewertungsfehler – die verlorenen
Spiele sind Land-/Farbmangel-Seeds, die Standard-KI verliert sie mit denselben Händen genauso. Befund und
Vorschläge in `docs/bench/2026-09-20-stufe-2-nichtstun.md`.

Upstream-Update: `cd forge && git fetch upstream --tags && git merge forge-<version>` (Konflikte nur in den
POM-Versionen und unseren Patches), dann `mvn versions:set -DnewVersion=<version>-mtgplayer` und Bridge-POM anpassen.
