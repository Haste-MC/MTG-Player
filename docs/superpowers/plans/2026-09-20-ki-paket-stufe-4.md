# KI-Paket Stufe 4 – Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Szenen-Harness für Mechanik-Tests; Meld (Titania/Argoth) und Goad geprüft und ggf. gefixt; Mulligan mit Farb-/Kurvenprüfung; Bewertung in messbaren Schritten verbessert.

**Architecture:** Bridge-Test-Harness `mtgplayer.scene.Scene` (Forge `Match`/`Game` direkt, wie Forges `AITest`, aber headless über `ForgeBoot`). Forge-Fork-Änderungen in `forge-ai` (Mulligan `ComputerUtil.scoreHand`/`PlayerControllerAi.tuckCardsViaMulligan`, `AiProps`, Profile; `GameStateEvaluator`; Attack-/Meld-Logik nach Befund). Spec: `docs/superpowers/specs/2026-09-20-ki-paket-stufe-4-design.md`.

**Tech Stack:** wie Stufe 2/3 (Java 17, Maven, JUnit 5, Forge-Fork, Bench-CLI).

## Global Constraints

- Forge-Patches upstream-tauglich (englische Kommentare, Stil, keine MTG-Player-Bezüge); Forge-Commits `mtg-player: …` auf `mtg-player`, gepusht; Bridge-Commit mit Submodule-Zeiger + `docs/forge-fork.md`-Zeile; Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Nach jeder Forge-Änderung `cd forge && mvn -q -pl forge-gui -am install -DskipTests -Dcheckstyle.skip -Dmaven.javadoc.skip=true`; ein Maven-Prozess; nie `mvn clean` in der Bridge; Kevins Bridge (8080/8081) unangetastet; Bench-Läufe aus eingefrorenen Jar-Kopien (`cp.txt` → Scratchpad), damit parallel gebaut werden kann.
- Bench-Referenz: Ahoy-Spiegel 40 Seeds, `--timeout 10 --game-timeout 10`, Stand Stufe 3 = 28:12 (70 %, 54,6–81,9). Ergebnisse nach `docs/bench/2026-09-2x-stufe-4-*.md`.
- Bridge-Suite grün; Szenen-Tests laufen in Sekunden (kein Bench in der Suite).

---

### Task 1: Szenen-Harness + deterministische Copier-Tests + Titania-Szenen

**Files:**
- Create: `bridge/src/test/java/mtgplayer/scene/Scene.java`, `bridge/src/test/java/mtgplayer/scene/SceneTest.java`, `bridge/src/test/java/mtgplayer/ai/GameCopierTest.java`, `bridge/src/test/java/mtgplayer/ai/MeldTitaniaTest.java`
- Reference (read-only): `forge/forge-gui-desktop/src/test/java/forge/ai/AITest.java` (`initAndCreateGame`, `createCard`, `addCardToZone`, `gameLoopUntilNextPhase`), `forge/forge-gui/res/cardsfolder/t/titania_voice_of_gaea_titania_gaea_incarnate.txt`, `a/argoth_sanctum_of_nature.txt`

**Interfaces (Produces):**
```java
package mtgplayer.scene;
public final class Scene {
  public static Scene twoPlayers(AiConfig a, AiConfig b);          // Spieler 0 = "A", 1 = "B"; leere Decks; CommanderRules; Age Play; Phase MAIN1 von A
  public static Scene threePlayers(AiConfig a, AiConfig b, AiConfig c);
  public Game game(); public Player player(int i);
  public Card card(String name, Player owner, ZoneType zone);       // Card.fromPaperCard + neuer Timestamp + zone.add
  public List<Card> cards(String name, int n, Player owner, ZoneType zone);
  public void setPhase(PhaseType phase, Player active);            // devModeSet + onStackResolved
  public void loopUntil(PhaseType phase, Player active);           // mainLoopStep bis Phase/aktiver Spieler erreicht, max 500 Schritte, sonst AssertionError
  public void step(int n);                                          // n × mainLoopStep
  public boolean has(Player p, ZoneType zone, String name);
  public int count(Player p, ZoneType zone, String name);
}
```

- [ ] **Step 1: `SceneTest` (RED):** Szene mit A: „Grizzly Bears" im Spiel, „Forest" ×3; `setPhase(MAIN1, A)`; `has(A, Battlefield, "Grizzly Bears")`; `loopUntil(COMBAT_DECLARE_ATTACKERS, A)` läuft durch, Phase stimmt. Test schlägt fehl (Klasse fehlt).
- [ ] **Step 2: `Scene` implementieren** nach `AITest` (Deck leer → `new Deck()`; `RegisteredPlayer(deck).setPlayer(config.newLobbyPlayer(name))`; `Match`, `new Game(players, rules, match)`, `game.setAge(GameStage.Play)`, `AI_TIMEOUT` aus Argument (Standard 3)). `card()` per `FModel.getMagicDb().getCommonCards().getCard(name)` (bei null `StaticData.instance().attemptToLoadCard(name)`), `Card.fromPaperCard`, `setGameTimestamp(game.getNextTimestamp())`, `owner.getZone(zone).add(c)`; für Battlefield zusätzlich `c.setSickness(false)` optional per Parameter. `loopUntil`: Schleife `game.getPhaseHandler().mainLoopStep()` bis `is(phase) && getPlayerTurn() == active`, Deckel 500. Test GREEN.
- [ ] **Step 3: `GameCopierTest` (drei Fälle, deterministisch):** (a) Monarch: A `createMonarchEffect("LCC")` via `game.getAction().becomeMonarch(A, "LCC")`, dann `becomeMonarch(B, "LCC")` (A verliert die Krone → Zombie-Effektkarte), `new GameCopier(game).makeCopy()` ohne Ausnahme und die Kopie hat genau eine Monarch-Karte in B's Kommandozone; (b) `<Nothing>`: A greift mit „Grizzly Bears" B's Planeswalker „Jace Beleren" an (Kampf über `setPhase(COMBAT_DECLARE_ATTACKERS, A)`, `game.getCombat()` anlegen bzw. `game.getPhaseHandler().getCombat()`, `combat.addAttacker(bears, jace)`), dann `game.getAction().destroy(jace, …)`/`combat.removeFromCombat(jace)` → `makeCopy()` ohne Ausnahme; (c) Token im Exil erinnert: Effektkarte oder „Hostage Taker"-artige Konstruktion – einfachste Form: Token „Treasure" für B erzeugen (`TokenInfo`/`CardFactory.makeToken` per Skript `c_a_treasure_sac` – Name im `res/tokenscripts` prüfen), ins Exil legen, eine Karte von A `addRemembered(token)` → `makeCopy()` ohne Ausnahme. Wo ein Fall sich nicht ohne Spielfluss aufbauen lässt, im Report begründen und den Fall weglassen (nicht faken).
- [ ] **Step 4: `MeldTitaniaTest`:** Szene A (Mechanik): A kontrolliert „Titania, Voice of Gaea" + „Argoth, Sanctum of Nature" + 4 „Forest" im Friedhof + 3 Forest im Spiel; `setPhase(END_OF_TURN, B)`; `loopUntil(MAIN1, A)` → `has(A, Battlefield, "Titania, Gaea Incarnate")` und weder Titania noch Argoth im Spiel. Szene B (Strategie, Standard-KI): wie A, aber nur 2 Länder im Friedhof, Argoth ungetappt + 4 weitere ungetappte Forest, `setPhase(MAIN1, A)`, `loopUntil(COMBAT_BEGIN, A)` → Erwartung: ein Bear-Token im Spiel (Argoth aktiviert) **oder** Friedhof-Länder ≥ 4. Szene C: wie A mit Sim-KI (`AiConfig.parse("sim")`, `AI_TIMEOUT` 3). Ergebnisse festhalten – die Tests dürfen zunächst rot bleiben (Task 2 fixt), aber sie müssen die Erwartung exakt ausdrücken (`@Disabled` nur mit Grund und Verweis auf Task 2, falls rot).
- [ ] **Step 5:** Suite grün (rote Meld-Szenen ggf. `@Disabled` mit Begründung), Commit `test: szenen-harness, deterministische gamecopier-tests, meld-szenen titania/argoth`.

---

### Task 2: Meld-Befund umsetzen (Fork)

**Files:** je nach Task-1-Befund: `forge/forge-game/.../ability/effects/MeldEffect.java`, `forge/forge-ai/.../ability/{MeldAi,TokenAi}.java`, `forge/forge-ai/.../AiController.java` (Aktivierungs-Priorität), `docs/forge-fork.md`, Bridge: `MeldTitaniaTest` (`@Disabled` entfernen)

- [ ] **Step 1:** Ist Szene A rot → Mechanik-Fix (Trigger-Bedingung, `MeldEffect`, oder Copier für Sim). Ist Szene B rot → „AI works toward its own meld condition": in `AiController`/`ComputerUtilAbility` beim Bewerten aktivierbarer Fähigkeiten: hat der Spieler eine Karte mit `Meld`-Trigger (`SpellAbility` mit `ApiType.Meld` in Triggern), dessen `CheckSVar`-Bedingung noch nicht erfüllt ist, und ist das Gegenstück im Spiel → Fähigkeiten mit `Mill`-Unterfähigkeit oder Land-Opfer (Cost `Sac<1/Land>`) desselben Spielers bekommen Priorität (`AiPlayDecision.WillPlay` mit hoher Punktzahl); generisch über die Skript-Parameter, keine Kartennamen im Code.
- [ ] **Step 2:** Install, Meld-Szenen grün, Suite grün, Forge-Commit `mtg-player: …` + push, Bridge-Commit `feat: meld – …` mit Zeiger und Doc-Zeile.

---

### Task 3: Goad-Szenen mit Agitator Ant (+ Fix falls nötig)

Kevins Fall: „Agitator Ant" (Skript `a/agitator_ant.txt`: End-Step-Trigger, jeder Spieler *darf* zwei +1/+1-Marken auf eine eigene Kreatur legen – `ChooseCard` mit `MinAmount$ 0` – und die Kreatur wird gegoadet). Forges Angriffs-Controller kennt Goad nur generisch über `AttackConstraints`/`AttackRequirement` (Zeilen ~880–935 in `AiAttackController`), `ChooseCardAi` kennt den Goad-Nachteil gar nicht.

**Files:** `bridge/src/test/java/mtgplayer/ai/GoadTest.java`; ggf. `forge/forge-ai/.../ability/ChooseCardAi.java` (Goad-Bewusstsein), `AiAttackController.java`, `CreatureEvaluator.java`, `GameStateEvaluator.java`

- [ ] **Step 1 Szenen** (`threePlayers`, Standard-KI; Sitz A = KI unter Test, B kontrolliert „Agitator Ant", C dritter Spieler):
  (a) *Marken-Entscheidung, günstig:* A hat „Grizzly Bears", C hat keinen Blocker, B hat „Colossal Dreadmaw" (6/6). `setPhase(END_OF_TURN, B)`, `loopUntil(UPKEEP, A)`: Erwartung: A hat die Marken genommen (Bears 4/4) – Angriff auf C ist frei.
  (b) *Marken-Entscheidung, ungünstig:* nur zwei Spieler (A, B), B hat Dreadmaw, A hat Bears: Erwartung: A nimmt **keine** Marken (gegoadete 4/4 müsste in die 6/6 laufen).
  (c) *Angriff:* wie (a) nach dem Trigger, `loopUntil(COMBAT_DECLARE_BLOCKERS, A)`: Bears greift an, Verteidiger ist C (nicht B); mit `game.getCombat().getDefenderByAttacker(bears)` prüfen.
  (d) *Angriff, nur Goader angreifbar:* wie (b): Bears greift B an (Pflicht, einziger Spieler), auch wenn ungünstig – Regelkonformität, keine Ausnahme/kein Hänger.
  (e) (c) mit Sim-KI auf A.
- [ ] **Step 2 Fix nach Befund:** rote (a)/(b) → `ChooseCardAi`: wenn die Fähigkeitskette (`SubAbility`-Kette des `ChooseCard`) ein `Goad` auf die gewählte Karte enthält, nur wählen, wenn es einen angreifbaren Spieler außer dem Goader gibt, gegen den die Kreatur (mit den Marken) nicht chancenlos ist (`ComputerUtilCombat.canKillAttacker`/vorhandene Blocker-Bewertung); generisch, keine Kartennamen. Rote (c)/(d) → `AiAttackController` (Requirements werden ignoriert oder Goader gewählt). Rote (e) → Sim-Bewertung (`CreatureEvaluator` −5 „goaded" ist zu wenig, oder die Kopie verliert `goadedBy`). Install, Tests grün, Commits wie oben.

### Task 4: Mulligan-Heuristik (Fork)

**Files:** `forge/forge-ai/.../ComputerUtil.java` (`scoreHand`), `forge/forge-ai/.../PlayerControllerAi.java` (`tuckCardsViaMulligan`), `forge/forge-ai/.../AiProps.java` (`MULLIGAN_CHECK_COLORS`), `forge/forge-gui/res/ai/{Default,Cautious,Reckless,Experimental}.ai` (`MULLIGAN_CHECK_COLORS=true`), neu `forge/forge-gui/res/ai/Legacy.ai` (Kopie Default mit `false`); Bridge: `bridge/src/test/java/mtgplayer/ai/MulliganTest.java` (Szenen: Hand 5 Länder R/C/B/B + 3 blaue Zauber → Mulligan; Hand 3 Länder G/G/W + Zauber G, GW, 2G → behalten; London-Bottoming legt die unspielbare Farbe zuerst weg), `docs/forge-fork.md`, `docs/bench/2026-09-2x-stufe-4-mulligan.md`

- [ ] **Step 1:** Szenen-Tests (RED) über `Scene`: Hand per `card(name, A, ZoneType.Hand)`, dann `ComputerUtil.wantMulligan(A, 0)` bzw. `A.getController().tuckCardsViaMulligan(hand, 1)`; Erwartungen aus der Spec.
- [ ] **Step 2:** Implementierung nach Spec §4 (Farb-Abdeckung aus den Mana-Fähigkeiten der Hand-Länder; `Any` zählt für alle; Commander-Farbidentität als Filter; Kurven-Bonus; Bottoming-Rangfolge). Property-Schalter, alte Logik bleibt bei `false`.
- [ ] **Step 3:** Install, Tests grün, Suite grün; Bench: `--a std:Default --b std:Legacy` Abzan-Spiegel 40 Seeds und Ahoy-Spiegel 40 Seeds (Standard-KI: ~10 s je Spiel, Läufe in Minuten); Bench-Erweiterung: `GameRecord.fewSpells` (aus Kindprozess-Log: `<Sitz> cast`-Zeilen ≤ 2 bei ≥ 5 `<Sitz> played`-Ländern) und Summary-Zähler; Doc mit Tabelle.
- [ ] **Step 4:** Commits (Forge `mtg-player: mulligan checks colors and curve, London bottoming by playability (MULLIGAN_CHECK_COLORS)`, Bridge `feat: mulligan-heuristik (fork), bench zaehlt nichtstun-spiele, docs`).

---

### Task 5: Bewertung in Schritten (Fork)

**Files:** `forge/forge-ai/.../simulation/GameStateEvaluator.java`, `SpellAbilityPicker.java` (Schritt 4), `docs/bench/2026-09-2x-stufe-4-bewertung.md`, `docs/forge-fork.md`

- [ ] **Schritt 1 Landgewicht** (Spec §5.1) → Install → eingefrorene Jars → Ahoy-Spiegel 40 Seeds `sim:Default` vs `std:Default`, 10 s. Behalten, wenn Siegquote ≥ 70 % **oder** Untergrenze ≥ 54,6 %; sonst zurücknehmen (Commit revert) und im Doc festhalten.
- [ ] **Schritt 2 Handkarte** (§5.2), gleiche Regel gegen den jeweils besten Stand.
- [ ] **Schritt 3 Nicht-Kreaturen** (§5.3).
- [ ] **Schritt 4 `availableValue`-Regel** (§5.4).
- [ ] Jeder behaltene Schritt: Forge-Commit `mtg-player: evaluator – …`, Bridge-Zeiger, Doc-Zeile; am Ende Doc mit Tabelle aller Schritte (auch der verworfenen).

---

## Abschluss (Controller)

Whole-Branch-Review (Bridge + Fork-Diff), Trailer, Merge `main`, Push, Ledger/Memory, Kevin-Zusammenfassung mit Titania-Antwort.
