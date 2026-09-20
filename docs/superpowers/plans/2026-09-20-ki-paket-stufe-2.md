# KI-Paket Stufe 2 – Implementierungsplan (Fork)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Forges Voll-Simulation benutzbar machen: Zeitbudget je Entscheidung, keine Abstürze an Monarch/Planeswalkern, Nichtstun-Schwäche verstanden – jeweils mit Bench-Nachweis.

**Architecture:** Änderungen im Fork `forge/` (Submodule `Haste-MC/forge`, Branch `mtg-player`) in `forge-ai/…/simulation/{SimulationController,SpellAbilityPicker,GameCopier}.java` und `forge-game/…/{combat/Combat,player/Player}.java`; nach jeder Forge-Änderung `cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true`, dann Bridge-Tests (konsumieren `2.0.14-mtgplayer` aus `~/.m2`). Spec: `docs/superpowers/specs/2026-09-20-ki-paket-stufe-2-design.md`.

**Tech Stack:** Java 17, Maven (Forge-Multimodul + Bridge), JUnit 5 (Bridge), Bench-CLI.

## Global Constraints

- Forge-Patches upstream-tauglich: kein MTG-Player-Wissen in Forge, Stil der umgebenden Datei, englische Kommentare in Forge, deutsche im Bridge-Code.
- Zwei Repos, zwei Commit-Ströme: Forge-Commits im Submodule auf `mtg-player` (Prefix `mtg-player:`), dann Bridge-Commit, der den Submodule-Zeiger + `docs/forge-fork.md` mitnimmt. Beide mit Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Submodule pushen: `cd forge && git push origin mtg-player`.
- Ein Maven-Prozess zur Zeit (Forge-Install dauert 3–5 min; Timeout 600000 ms), nie `mvn clean` im Bridge-Verzeichnis, Kevins Bridge auf 8080/8081 nicht anfassen. Bench-Läufe aus `bridge/` mit `java -cp target/classes:$(cat <scratch>/cp.txt)` – **cp.txt nach jedem Forge-Install neu erzeugen** (`mvn -q dependency:build-classpath -Dmdep.outputFile=…`), sonst zeigt der Classpath auf alte Jars.
- Bench-Nachweise als Markdown nach `docs/bench/`, Ausgabeverzeichnis je Lauf `--out ~/.mtg-player/bench-s2-<name>`.
- Bridge-Suite grün (115 + neue), `docs/forge-fork.md` nach jeder Forge-Änderung ergänzt.

---

### Task 1: Zeitbudget für die Simulation

**Files:**
- Modify (Forge): `forge/forge-ai/src/main/java/forge/ai/simulation/SimulationController.java`, `forge/forge-ai/src/main/java/forge/ai/simulation/SpellAbilityPicker.java`
- Modify (Bridge): `README.md` (Bench-/Timeout-Absätze), `web/src/components/Lobby.tsx` (Hinweistext), `docs/forge-fork.md`
- Test (Bridge): `bridge/src/test/java/mtgplayer/ai/SimBudgetTest.java`

**Interfaces (Produces):** `SimulationController(Score score, int maxDepth, long deadlineNanos)`; `boolean isOutOfTime()`; `SpellAbilityPicker` setzt die Deadline aus `game.getAITimeout()`; neue statische Hilfsmethode `SpellAbilityPicker.deadlineFor(Game)` (Sekunden → `System.nanoTime() + …`, bei `AI_TIMEOUT <= 0` → `Long.MAX_VALUE`).

- [ ] **Step 1: Forge-Code lesen** – `SpellAbilityPicker` (createNewPlan ~Z. 120–160, chooseSpellAbilityToPlayImpl ~Z. 165–195, evaluateSa und die Ziel-/Modus-Schleifen darunter), `SimulationController.shouldRecurse()` (~Z. 59), `GameSimulator` (wo `controller.shouldRecurse()` gefragt wird). Kurz notieren, an welchen Stellen Kandidaten, Ziele und Modi iteriert werden.
- [ ] **Step 2: Bridge-Test `SimBudgetTest` schreiben (RED):**

```java
@Test @Timeout(value = 15, unit = TimeUnit.MINUTES)
void simEntscheidungenHaltenDasBudgetEin() {
    // Abzan-Spiegel: das Board, das im Bench > 30 min in einer Entscheidung hing.
    List<Deck> decks = List.of(Precons.load("Abzan Armor [TDC] [2025]"), Precons.load("Abzan Armor [TDC] [2025]"));
    List<AiConfig> cfg = List.of(AiConfig.parse("sim"), AiConfig.DEFAULT);
    MyRandom.setRandom(new Random(1));
    long t0 = System.nanoTime();
    AiMatch.Result r = AiMatch.play(decks, List.of("A", "B"), cfg, 3, 26, s -> { });
    double seconds = (System.nanoTime() - t0) / 1e9;
    // 26 Spielerzuege, davon 13 fuer die Sim-KI mit je ~2 Entscheidungen (Main 1/2) a max. 3 s Budget,
    // plus Kampf und Standard-KI: grosszuegige Schranke, die ohne Budget (>30 min) sicher reisst.
    assertTrue(seconds < 240, "Sim-Spiel brauchte " + seconds + " s fuer " + r.turns() + " Zuege");
}
```
  Vor dem Fix laufen lassen (`cd bridge && mvn -q test -Dtest=SimBudgetTest`) – erwartet: Timeout/Failure (dokumentieren, ggf. nach 15 min abgebrochen).
- [ ] **Step 3: Forge-Fix.** `SimulationController`: Feld `deadlineNanos`, Konstruktor-Überladung, `isOutOfTime()`; `shouldRecurse()` → `… && !isOutOfTime()`. `SpellAbilityPicker`: in `createNewPlan` eine Deadline für die ganze Entscheidung berechnen und an beide `formulatePlanWithPhase`-Aufrufe durchreichen (Signatur um `long deadlineNanos` erweitern); `chooseSpellAbilityToPlayImpl`: `if (i > 0 && controller.isOutOfTime()) { budgetHit = true; break; }`; in den Ziel-/Modus-Schleifen von `evaluateSa` (und Helfern) analog abbrechen, sobald ein Ergebnis vorliegt; `print`-Zeile ergänzen. Rekursion (`controller != null` in `chooseSpellAbilityToPlay`) nutzt die Deadline des übergebenen Controllers. Kommentar am Feld: warum (unbegrenzte Suche auf großen Boards, > 30 min gemessen).
- [ ] **Step 4: Forge bauen + installieren**, cp.txt neu, `SimBudgetTest` GREEN, komplette Bridge-Suite grün.
- [ ] **Step 5: Bench-Nachweis** (Hintergrund, ~1 h): Ahoy-Spiegel 40 Seeds `--a sim:Default --b std:Default --timeout 10 --game-timeout 10 --seed 1 --out ~/.mtg-player/bench-s2-budget`; parallel Abzan-Spiegel 10 Seeds `--timeout 10 --game-timeout 15`. Erwartung: 0 Timeouts; Siegquote-Untergrenze > 50 %. Ergebnis nach `docs/bench/2026-09-2x-stufe-2-zeitbudget.md` (Tabelle + 3 Sätze).
- [ ] **Step 6: Bridge-Texte:** README (Bench: „`--timeout` gilt auch für die Simulation"; Forge-Fork-Abschnitt), `Lobby.tsx` Hinweis → „gilt für alle KI-Modi; Simulation nutzt das Budget je Entscheidung", `docs/forge-fork.md` Zeile. `npm test`, `npm run build`.
- [ ] **Step 7: Commits** – Forge: `mtg-player: time budget for full simulation (AI_TIMEOUT per decision, best-so-far result)`; Bridge: `feat: sim-ki mit zeitbudget (fork), tests, docs`.

---

### Task 2: `GameCopier` robust – Monarch, `<Nothing>`, `getMonarchSet`

**Files:**
- Modify (Forge): `forge/forge-ai/src/main/java/forge/ai/simulation/GameCopier.java`, `forge/forge-game/src/main/java/forge/game/combat/Combat.java` (Kopier-Konstruktor), `forge/forge-game/src/main/java/forge/game/player/Player.java` (`getMonarchSet`)
- Modify (Bridge): `docs/forge-fork.md`
- Test (Bridge): `bridge/src/test/java/mtgplayer/ai/SimCopierTest.java`

- [ ] **Step 1: Reproduzieren.** Bench-Log-Analyse: `Couldn't map The Monarch` trat im Ahoy-Spiegel bei Seed 5 (Teil 1, Spiel 5, nach 5 s) und Seed 7 auf; `<Nothing>` bei Abzan vs Adaptive Enchantment Seed 4 (Estrid stirbt im Kampf, Zug ~9). Test `SimCopierTest` (RED) mit zwei Fällen: (a) Ahoy-Spiegel Seed 5, Sim auf A, Zugdeckel 30, `aiTimeout 3` → keine Ausnahme; (b) Abzan(sim) vs Adaptive Enchantment(std) Seed 4, Zugdeckel 30 → keine Ausnahme. Beide vor dem Fix laufen lassen, Stacktraces in den Report.
- [ ] **Step 2: Ursache Monarch.** Im Copier prüfen, warum die Effektkarte nicht in `cardMap` landet (Hypothesen: `addCard` überspringt `GamePieceType.EFFECT`; `origGame.getCardsIn(ZoneType.Command)` enthält die Karte, aber `createCardCopy` wirft/liefert null für Karten ohne `PaperCard` (`new Card(id, null, game)`); oder die Karte liegt in keiner Zone und `copyEffectCardsToSnapshot` mappt trotzdem, weil `getZone()` gesetzt ist). Fix so, dass Effektkarten ohne PaperCard kopiert werden (Name, Typ, Trigger/Statics, Owner) oder – falls das Kopieren der Trigger nicht trivial ist – die Kopie den Effekt über `Player.createMonarchEffect(set)` neu anlegt und `copyEffectCardsToSnapshot` die neue Karte verwendet. Im Report die gewählte Variante begründen.
- [ ] **Step 3: `getMonarchSet` reparieren:** `return monarchEffect == null ? null : monarchEffect.getSetCode();`.
- [ ] **Step 4: `<Nothing>`.** In `Combat(Combat combat, IEntityMap map)` beim Mappen von `attackedByBands`-Schlüsseln: ist der Schlüssel eine Karte mit Namen `<Nothing>` (bzw. id −1, kein Zone), eine neue Fake-Karte im Zielspiel anlegen (`new Card(-1, game)`, `setName("<Nothing>")`, Controller über `map.map(controller)`), statt `map.map(...)`. Gleiche Behandlung, falls die Fake-Karte auch in `blockedBands`/`attackersOrderedForDamageAssignment` auftaucht (prüfen).
- [ ] **Step 5: Bauen, installieren, cp.txt, `SimCopierTest` GREEN, Bridge-Suite grün.**
- [ ] **Step 6: Bench-Nachweis:** Abzan(sim) vs Adaptive Enchantment(std) 20 Seeds und Ahoy-Spiegel 20 Seeds (`--timeout 10 --game-timeout 10`), Erwartung 0 Abstürze / 0 Sim defekt → `docs/bench/2026-09-2x-stufe-2-copier.md`.
- [ ] **Step 7: Commits** – Forge: `mtg-player: GameCopier copies effect cards (monarch), Combat copy tolerates <Nothing> placeholder, fix Player.getMonarchSet`; Bridge: `feat: sim-ki ohne abstuerze an monarch/planeswalkern (fork), tests, docs`.

---

### Task 3: Nichtstun-Schwäche untersuchen (und beheben, wenn klein)

**Files:**
- Modify (Bridge): `bridge/src/main/java/mtgplayer/Main.java` (`--bench-one` akzeptiert `-Dmtgplayer.simDebug=true` → `GameSimulator.debugPrint = true` und Picker-`print` sichtbar; prüfen, wie `SpellAbilityPicker.print` gesteuert wird – ggf. Forge-Flag `SpellAbilityPicker.debugPrint` einführen), `docs/bench/2026-09-2x-stufe-2-nichtstun.md`
- Modify (Forge, nur wenn Fix klein): `SpellAbilityPicker.java` / `GameStateEvaluator.java`

- [ ] **Step 1:** Seed 40 (Ahoy-Spiegel Teil 2, Spiel 21: 5 Länder, 1 Zauber, 1 Abwurf) und Seeds 22 und 32 (weitere Niederlagen) mit Debug-Ausgabe nachspielen (`--bench-one` in-process, Log in Datei). Je Zug der Sim-KI: Handkarten, Kandidaten, Bewertungen, gewählter Zug; Züge markieren, in denen ein spielbarer Zauber nicht gespielt wurde.
- [ ] **Step 2:** Ursache benennen (Hypothesen aus der Spec: `availableValue`-Regel vor Main 2 + Plan-Mechanismus; Bewertung Hand vs. Board; Budget-Abbruch nach Task 1 zu früh?). Mit Zahlen aus dem Log belegen.
- [ ] **Step 3:** Ist der Fix ≤ ~20 Zeilen und ohne Bewertungs-Neudesign (z. B. `availableValue`-Regel nur in Main 1 anwenden, wenn der Zug in Main 2 noch bezahlbar wäre; oder in Main 2 die Regel aussetzen): umsetzen, bauen, Bridge-Suite, Spiegel-Bench 40 Seeds gegen den Stand nach Task 2 → Siegquote-Vergleich in `docs/bench/2026-09-2x-stufe-2-nichtstun.md`. Sonst: Befund + konkreter Vorschlag für die Bewertungsarbeit dort dokumentieren, kein Forge-Commit.
- [ ] **Step 4:** Commits wie in Task 1/2 (`mtg-player: …` bzw. `docs: …`).

---

## Abschluss (Controller)

Whole-Branch-Review über Bridge-Diff + Forge-Diff (`cd forge && git diff forge-2.0.14..mtg-player -- forge-ai forge-game`), Trailer prüfen, Submodule und Bridge gepusht, `docs/forge-fork.md` vollständig, Ledger/Memory.
