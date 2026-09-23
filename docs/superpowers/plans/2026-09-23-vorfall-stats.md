# Runde B – Vorfall-Kennzahlen je Partie

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Jede Partie schreibt ab sofort deutlich mehr mit: gekonterte/verpuffte Zauber, gezogene/abgeworfene/gemillte Karten, verlorene bleibende Karten inkl. Massenentfernung, Angriffs- und Blockverhalten, Schadensquellen nach Schlüsselwort, Lebensgewinn und eine Zeitachse je eigenem Zug.

**Architecture:** Nur `MatchRecorder` (neue `@Subscribe`-Methoden, Zuordnung über `PlayerView`/`ZoneView`/Kontrolleur) und `MatchRecord.Seat` (neue Felder, Formatversion `v: 2`). Web unverändert – die Anzeige kommt in Runde A. Spec: `docs/superpowers/specs/2026-09-23-vorfall-stats-design.md`.

**Tech Stack:** Java 21 (Bridge, JUnit 5), Forge-Events, Szenen-Harness `mtgplayer.scene.Scene`.

## Global Constraints

- Branch `feature/vorfall-stats` (existiert, Spec committet). Commits deutsch, Kleinschreibung, Präfix `bridge:`/`docs:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, keine weiteren Co-Authors.
- **Kevins Bridge läuft auf 8080/8081 aus `bridge/target/classes`** – nicht im Hauptbaum kompilieren. Eigener Arbeitsbaum (`git worktree add --detach <scratch>/<name> feature/vorfall-stats`), dort bauen und committen, danach im Hauptbaum `git cherry-pick`, zum Schluss `git worktree remove --force`. Maven dort mit `-Dmtgplayer.assets=/home/kevin/projects/MTG-Player/bridge/assets`. Nie `mvn clean`; ein Maven-Prozess gleichzeitig (vorher prüfen: `ps -eo args= | grep -c "[c]lassworlds.*test"`); Timeout 600000 ms.
- Tests schreiben nie nach `~/.mtg-player` (Surefire setzt `mtgplayer.data` bereits auf `target/test-data`; `CrashLog.setFile(@TempDir …)` zusätzlich, wo ein Test den CrashLog auslöst).
- Kein Zähler darf eine Partie abschießen: jede Handler-Methode fängt `RuntimeException`, zählt den Fall nicht mit und meldet einmal je Partie über `CrashLog.note`.
- Alte Datensätze (`v: 1`) bleiben lesbar; neue Felder sind dort 0.

---

### Task 1: Zauber, Karten, Verluste

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/stats/MatchRecord.java`, `MatchRecorder.java`
- Test: `bridge/src/test/java/mtgplayer/stats/MatchRecorderTest.java`

**Interfaces:** `Seat` bekommt (nach `spellMana`, Reihenfolge wie hier):
```java
int spellsCountered, int spellsFizzled, int counterspellsCast,
int cardsDrawn, int cardsDiscarded, int cardsMilled,
int permanentsLost, int creaturesLostInCombat, int creaturesLostOther,
int biggestSweep, int sweepsSuffered, int tokensCreated
```
`MatchRecord.FORMAT = 2`.

Ableitungen (Spec §2):
- `spellsCountered`: `GameEventSpellRemovedFromStack` → Sitz aus `sa.getHostCard().getController()`; nur zählen, wenn für diesen Stack-Eintrag kein `GameEventSpellResolved` kam (Merkliste der zuletzt aufgelösten `SpellAbilityView`s, Abgleich über Objektidentität).
- `spellsFizzled`: `GameEventSpellResolved` mit `hasFizzled()`.
- `counterspellsCast`: bei `GameEventSpellAbilityCast` die Fähigkeitskette (`sa`, `sa.getSubAbility()`, …) auf `ApiType.Counter` prüfen.
- `cardsDrawn` / `cardsDiscarded` / `cardsMilled`: `GameEventCardChangeZone` mit `from`/`to` (`ZoneView.player()` = Sitz): Library→Hand / Hand→Graveyard / Library→(Graveyard|Exile).
- `permanentsLost`: Battlefield→(Graveyard|Exile), Sitz = `from.player()`; davon Kreaturen (`card.getCurrentState().isCreature()`) je nach aktueller Phase in `creaturesLostInCombat` (`PhaseType.COMBAT_DAMAGE*`) oder `creaturesLostOther`.
- `biggestSweep`/`sweepsSuffered`: Fenster je Sitz, das bei `GameEventSpellResolved` **und** bei `GameEventTurnPhase` zurückgesetzt wird; Maximum bzw. Zählung ab 3.
- `tokensCreated`: Zonenwechsel **nach** Battlefield mit `card.isToken()`, Sitz = `to.player()`.

- [ ] **Step 1: Failing Tests** – Szenen-Tests je Zähler laut Spec §4 (Zauber/Karten/Verluste-Teil). Wo ein echter Spielzug zu aufwendig ist, `game.fireEvent(...)` mit passenden Views; mindestens „Kreatur im Kampf verloren" und „Kreatur per Entfernung verloren" aus echten Zügen.
- [ ] **Step 2: Rot** (Arbeitsbaum, `mvn -q -o test -Dtest=MatchRecorderTest`).
- [ ] **Step 3: Implementieren.**
- [ ] **Step 4: Grün** – `MatchRecorder*`, danach volle Suite.
- [ ] **Step 5: Commit** – `bridge: vorfall-kennzahlen teil 1 – zauber, karten, verluste`.

---

### Task 2: Kampf, Schadensquellen, Leben, Zeitachse

**Files:** wie Task 1, zusätzlich Test `MatchRecorderCombatTest`

**Interfaces:** `Seat` bekommt weiter (nach `tokensCreated`):
```java
int attacksDeclared, int attackedTurns, int attackersFaced, int blocksDeclared,
int damageTakenFlying, int damageTakenTrample, int damageTakenOther, int damageTakenNonCombat,
int damageDealtCombat, int damageDealtNonCombat, int commanderDamageTaken, int lifeGained,
List<TurnPoint> timeline
```
mit `public record TurnPoint(int turn, int lands, int creatures, int life, int hand) { }` (im selben File wie `MatchRecord`).

Ableitungen:
- `attacksDeclared`/`attackedTurns`: `GameEventAttackersDeclared` – Zahl der Angreifer je Sitz, plus ein Zug-Merker.
- `attackersFaced`: aus derselben Multimap die Angreifer, deren Verteidiger dieser Sitz (oder ein Planeswalker dieses Sitzes) ist.
- `blocksDeclared`: `GameEventBlockersDeclared` – Zahl der Blocker des verteidigenden Sitzes.
- `damageTaken*`: `GameEventPlayerDamaged`; bei `combat` nach Schlüsselwort der Quelle (`FLYING` → flying, sonst `TRAMPLE` → trample, sonst other), sonst `damageTakenNonCombat`; `commanderDamageTaken` zusätzlich, wenn `source.isCommander()`.
- `damageDealt*`: derselbe Event, Sitz = Kontrolleur der Quelle.
- `lifeGained`: `GameEventPlayerLivesChanged` mit `newLives > oldLives`.
- `timeline`: bei `GameEventTurnBegan` für den Zugbesitzer einen Punkt anhängen (Länder/Kreaturen im Spiel, Leben, Handkarten aus dem Spielzustand), Deckel 60.

- [ ] **Step 1–5** wie Task 1, Commit `bridge: vorfall-kennzahlen teil 2 – kampf, schadensquellen, zeitachse`.

---

### Task 3: Robustheit, Format-Version, Abschluss

**Files:** `MatchRecorder.java`, `MatchStore.java` (nur falls nötig), `bridge/src/test/java/mtgplayer/stats/MatchStoreTest.java`, `README.md`

- [ ] **Step 1:** Jede neue Handler-Methode in `try/catch (RuntimeException)`; ein Fehlerzähler, der am Ende **einmal** über `CrashLog.note("MatchRecorder", "n Ereignisse nicht gezählt: …")` gemeldet wird. Test: ein Ereignis mit fehlender Quelle (`fireEvent` mit `null`) bricht nichts ab, der Datensatz entsteht trotzdem.
- [ ] **Step 2:** `MatchStoreTest`: ein `v: 1`-Datensatz (JSON von Hand) liest sich weiterhin, neue Felder sind 0, `timeline` ist leer statt `null`.
- [ ] **Step 3:** `MatchRecorderAiTest` um Plausibilitätsprüfungen erweitern (Spec §4).
- [ ] **Step 4:** README (Abschnitt Statistik): Liste der neuen Kennzahlen in einem Satz je Gruppe, Hinweis, dass sie erst ab jetzt gesammelt werden und ältere Partien sie nicht haben.
- [ ] **Step 5:** volle Suite, Commit `bridge: vorfall-kennzahlen robust, formatversion 2, readme`.
