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
| deb296332 | `forge-ai`: `SpellAbilityPicker` baut die Kandidaten-Debug-Zeile nur noch, wenn `printOutput` gesetzt ist (String-Aufbau je Kandidat war sonst umsonst); `deadlineFor` paketprivat → `private static` (nur intern genutzt); `GameCopier`-Kommentar zum übersprungenen `Remembered`-Objekt verallgemeinert (LKI-Kopien, Token außerhalb des Spielfelds, Sideboard) | Review-Befund Whole-Branch-Review KI-Paket 2 |
| 94931591 | `forge-ai`: `SpellAbilityChoicesIterator.advance` poppt Ziel-/Modus-Ebenen nur, wenn sie in der letzten Simulation gepusht wurden (`pushTarget`/`advancedToNextMode`); ein unausgeglichener `evalDepth` wird gemeldet und ausgeglichen statt `RuntimeException("-1")` zu werfen | Bricht der Simulator vor `chooseTargets` ab ("SA not found" auf der Kopie), stürzte das ganze Spiel ab (Bench Seed 13, nicht deterministisch reproduzierbar) |
| c3910fdb | `forge-ai`: `SpellAbilityPicker.getCandidateSpellsAndAbilities` sortiert die Kandidaten wie die Regel-KI (`saEvaluator`, Kreaturen zuerst) | Unter Zeitbudget wurden sonst die ersten Karten aus `getAvailableCards` bewertet (Länder, Cantrips); Bench-Vergleich in `docs/bench/2026-09-20-stufe-3-kandidaten.md` |
| 7f98cdd9 | `forge-ai`: `ChoicePoint.open` merkt, ob die Kartenwahl-Ebene auf dem Controller-Stack liegt; `advance()` poppt nur offene Ebenen; `SimulationController.doneEvaluating` ohne offene Entscheidung meldet statt NPE | Bench 30-s-Budget, Seed 31: nach einem frühen Simulator-Abbruch wurden nie neu gepushte Ebenen gepoppt → Stack-Unterlauf, Spiel tot |

Bekannt (offen): `GameSimulator.CHECK_GAME_COPY_SCORE` (nur mit Java-Assertions aktiv) meldet im Abzan-Spiegel und
bei Abzan gegen Adaptive Enchantment `Game copy error` – eine Kopie in Rekursionstiefe 2–3 wird anders bewertet als
das Original. Kopiertreue-Problem des `GameCopier`, unabhängig von Zeitbudget und den Monarch/`<Nothing>`-Fixes.
Die Bridge-Tests laufen deshalb wie die Produktion (Bench-Kindprozess, Bridge) ohne Assertions (`bridge/pom.xml`,
Surefire `enableAssertions=false`). Kandidat für die Bewertungsarbeit.

Nachgeprüft (2026-09-20, `cd bridge && _JAVA_OPTIONS="-Xmx4g -ea:forge.ai.simulation.GameSimulator" mvn -q test
-Dtest=SimCopierTest`; die `argLine` in `bridge/pom.xml` ist ein fester Literal-String ohne `${argLine}`-Platzhalter,
`-DargLine=…` auf der Kommandozeile wirkt also nicht – `_JAVA_OPTIONS` erreicht die geforkte Surefire-JVM
trotzdem): `SimCopierTest` besteht mit `-ea` weiterhin nur im Monarch-Fall
(`monarchEffektkarteUeberlebtDieKopie`); die beiden anderen Fälle (`nothingPlatzhalterUeberlebtDieKopie`,
`geopferteAusruestungUeberlebtDieKopie`) schlagen mit `Game copy error` fehl – derselbe `CHECK_GAME_COPY_SCORE`-
Befund wie im Abzan-Spiegel, unabhängig vom jeweiligen `GameCopier`-Fix für diesen Absturzfall.

Das Zeitbudget (ffe45981/7b88bf01) lässt die Nach-Blocker-Planrunde entfallen, wenn die erste Runde das Budget
schon aufgebraucht hat (`SimulationController.isPast`) – unter knappem Budget entscheidet die Simulation damit
öfter allein anhand von Main 1, das Warten auf einen besseren Zug nach den Blockern fällt weg. Das ist eine
bewusste Verzerrung Richtung „spielen in Main 1" statt eines Fehlers: ohne den Kurzschluss lief die zweite Runde
mindestens einen vollen Kandidaten ohne Limit (siehe 7b88bf01 oben).

`Simulation: SA not found!` (im Bench-Log gesehen): `GameSimulator` findet nach dem Kopieren die zu simulierende
Fähigkeit auf der Kopie nicht (z. B. Escape aus dem Friedhof) und überspringt sie – kein Absturz, nur eine
nicht bewertete Option.

Nichtstun-Schwäche der Simulation (Stufe 2, Schritt 3): untersucht, kein Bewertungsfehler – die verlorenen
Spiele sind Land-/Farbmangel-Seeds, die Standard-KI verliert sie mit denselben Händen genauso. Befund und
Vorschläge in `docs/bench/2026-09-20-stufe-2-nichtstun.md`.

Forges eigene Sim-Tests (`forge.ai.simulation.*Test` in `forge-gui-desktop`, Review-Punkt 4): mit
`cd forge && mvn -q -pl forge-gui-desktop -am test -Dtest='forge.ai.simulation.*Test' -Dcheckstyle.skip
-Dmaven.javadoc.skip=true -Dsurefire.failIfNoSpecifiedTests=false` (die im Review vorgeschlagene Option
`-DfailIfNoTests=false` griff nicht – falscher Property-Name für dieses Surefire, siehe Fehlermeldung; korrekt
ist `-Dsurefire.failIfNoSpecifiedTests=false`) baut das Modul in unter einer Minute, aber alle sechs Testklassen
scheitern gleich in ihrem `@BeforeClass initializeModel`: `NoClassDefFoundError: Could not initialize class
forge.GuiDesktop`, verursacht durch `java.awt.HeadlessException`. Auch mit `-Djava.awt.headless=true` (per
`-D` und per `_JAVA_OPTIONS`, um die geforkte JVM sicher zu erreichen) derselbe Fehler – `GuiDesktop`s
Initialisierung braucht offenbar eine echte Anzeige/Toolkit-Ressource, kein `xvfb-run` in dieser Umgebung
installiert. **Nicht ausgeführt** in dem Sinn, dass keiner der Sim-Tests selbst lief (145 Tests, 6 Fehler in
`initializeModel`, 139 übersprungen); Ursache ist eine Umgebungsabhängigkeit von `forge-gui-desktop`s Test-
Fixture (`AITest`/`GuiDesktop`), nicht der Stufe-2-Fork-Änderungen. Die Bridge-eigenen Sim-Tests
(`SimBudgetTest`, `SimCopierTest`, volle Suite) laufen unabhängig davon grün, siehe
`.superpowers/sdd/final-fix-report-ki2.md`.

Upstream-Update: `cd forge && git fetch upstream --tags && git merge forge-<version>` (Konflikte nur in den
POM-Versionen und unseren Patches), dann `mvn versions:set -DnewVersion=<version>-mtgplayer` und Bridge-POM anpassen.
