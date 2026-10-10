# 2v2 bench and fair seating — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The bench can play and measure 2v2, and a team match seats its teams alternating — in the bench and
in the games Kevin plays.

**Architecture:** One pure unit (`Seating.interleave`) decides the seat order and is used by both the headless
bench (`AiMatch`/`Bench`) and the live match (`HumanMatch`), so the bench measures the seating Kevin plays.
`AiMatch.play` gains an optional team list, `BenchArgs` a `--teams` flag; nothing else about the bench changes.

**Tech Stack:** Java 17, Forge 2.0.15-mtgplayer, JUnit 5. No web changes.

Spec: `docs/superpowers/specs/2026-10-10-bench-2v2-und-sitzordnung-design.md`.

## Global Constraints

- **Never run Maven in `/home/kevin/projects/MTG-Player`** — Kevin's bridge runs from `bridge/target/classes`
  there. Work in a git worktree under the scratchpad, merge to `main` at the end.
- A fresh worktree has no Forge submodule: create `forge/forge-gui/res` as a symlink to
  `/home/kevin/projects/MTG-Player/forge/forge-gui/res` so the bridge tests find the card data, and **delete it
  again before committing**. A fresh worktree also has no `web/node_modules` (symlink it if you touch web).
- One Maven run at a time, offline (`mvn -o`); before each run list the `classworlds` processes
  (`ps -eo pid,etime,args | grep classworlds | grep -v grep`) and **never kill a process you did not start, and
  never by name pattern — only by a pid you captured yourself.** `pkill` is forbidden in every form.
  **Never `mvn clean`.**
- Tests never write to `~/.mtg-player` (surefire sets `-Dmtgplayer.data=target/test-data`) and never go online.
- Ports 8080/8081 belong to Kevin's bridge; own instances use 18180/18181.
- Commit messages in **German**, prefix `bridge:`/`test:`/`docs:`, trailer exactly
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Language rule of 2026-10-09: table vocabulary English, everything else (bench reports, logs, comments) German.
- Teams are `1..6`; "no team" is `-1` in the engine list and absent in stored data.

---

### Task 1: The seating rule

**Files:**
- Create: `bridge/src/main/java/mtgplayer/match/Seating.java`
- Test: `bridge/src/test/java/mtgplayer/match/SeatingTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `public static List<Integer> Seating.interleave(List<Integer> teams)` — the new seat order as a
  list of **indices into the given list**: `interleave([1,1,2,2])` is `[0,2,1,3]`, meaning "seat 0 stays, then
  the seat that was at index 2, then 1, then 3". Index 0 of the input always stays first (Forge sorts the human
  seat to the front; see the spec). Without teams (`null`, empty, or every entry `< 0`) it returns the identity.

- [ ] **Step 1: Write the failing test**

```java
package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Die Zugreihenfolge folgt der Sitzreihenfolge: sitzen beide eines Teams nebeneinander, zieht dieses
 *  Team zweimal hintereinander. Diese Regel verteilt die Sitze abwechselnd - und zwar fuer den Bench und
 *  fuer die echte Partie gleich, sonst misst der Bench etwas anderes, als gespielt wird. */
class SeatingTest {

    @Test
    void ohneTeamsBleibtDieReihenfolge() {
        assertEquals(List.of(0, 1, 2, 3), Seating.interleave(List.of(-1, -1, -1, -1)));
        assertEquals(List.of(0, 1), Seating.interleave(null));
    }

    @Test
    void zweiGegenZweiWechseltAb() {
        // Sitz 0 bleibt vorn (Forge sortiert den menschlichen Sitz dorthin), danach abwechselnd.
        assertEquals(List.of(0, 2, 1, 3), Seating.interleave(List.of(1, 1, 2, 2)));
    }

    @Test
    void bereitsAbwechselndBleibtUnveraendert() {
        assertEquals(List.of(0, 1, 2, 3), Seating.interleave(List.of(1, 2, 1, 2)));
    }

    @Test
    void dreiTeamsZuZweitReihumVerteilt() {
        List<Integer> teams = List.of(1, 1, 2, 2, 3, 3);
        List<Integer> order = Seating.interleave(teams);
        // Kein Team sitzt zweimal hintereinander.
        for (int i = 1; i < order.size(); i++) {
            int vorher = teams.get(order.get(i - 1));
            int jetzt = teams.get(order.get(i));
            org.junit.jupiter.api.Assertions.assertNotEquals(vorher, jetzt,
                    "Sitz " + i + " hat dasselbe Team wie sein Vorgaenger: " + order);
        }
        assertEquals(0, order.get(0), "Sitz 0 bleibt vorn");
    }

    @Test
    void ungleicheTeamsVerteilenSoGutEsGeht() {
        // 1v2: ein Doppelzug ist unvermeidbar, aber das grosse Team darf nicht komplett vorne sitzen.
        List<Integer> teams = List.of(1, 2, 2);
        List<Integer> order = Seating.interleave(teams);
        assertEquals(List.of(0, 1, 2), order);
    }

    @Test
    void jederSitzKommtGenauEinmalVor() {
        List<Integer> order = Seating.interleave(List.of(1, 1, 1, 2, 2, 2));
        assertEquals(6, order.size());
        assertEquals(List.of(0, 1, 2, 3, 4, 5), order.stream().sorted().toList());
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `cd bridge && mvn -o -q -Dtest=SeatingTest test`
Expected: compile error, `mtgplayer.match.Seating` does not exist.

- [ ] **Step 3: Write the implementation**

```java
package mtgplayer.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sitzreihenfolge bei Teams: die Zugreihenfolge folgt den Sitzen, also wuerde ein Team, dessen Sitze
 * nebeneinander liegen, zweimal hintereinander ziehen. Diese Regel verteilt die Sitze abwechselnd.
 *
 * <p>Sitz 0 bleibt immer vorn: {@code HostedMatch.startMatch} sortiert den menschlichen Sitz stabil nach
 * vorn, und gegen diese Sortierung anzuarbeiten wuerde still scheitern - die Umsortierung muss sie also
 * vorwegnehmen.</p>
 *
 * <p>Bei ungleich grossen Teams (1v2) laesst sich ein Doppelzug nicht vermeiden; dann wird nur so
 * gleichmaessig verteilt, wie es geht.</p>
 */
public final class Seating {

    private Seating() { }

    public static List<Integer> interleave(List<Integer> teams) {
        if (teams == null || teams.isEmpty()) {
            return identity(teams == null ? 2 : 0);
        }
        if (teams.stream().allMatch(t -> t == null || t < 0)) {
            return identity(teams.size());
        }
        // Je Team seine Sitze in gegebener Reihenfolge; das Team von Sitz 0 kommt zuerst dran.
        Map<Integer, List<Integer>> proTeam = new LinkedHashMap<>();
        proTeam.computeIfAbsent(teams.get(0), t -> new ArrayList<>());
        for (int i = 0; i < teams.size(); i++) {
            proTeam.computeIfAbsent(teams.get(i), t -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> schlangen = new ArrayList<>(proTeam.values());
        List<Integer> out = new ArrayList<>();
        // Reihum eine Schlange nach der anderen leeren: das verteilt die Teams so gleichmaessig wie
        // moeglich und laesst ein groesseres Team nur am Ende doppelt sitzen.
        while (out.size() < teams.size()) {
            boolean etwasGenommen = false;
            for (List<Integer> schlange : schlangen) {
                if (!schlange.isEmpty()) {
                    out.add(schlange.remove(0));
                    etwasGenommen = true;
                }
            }
            if (!etwasGenommen) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> identity(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return List.copyOf(out);
    }
}
```

Note for the implementer: `interleave(null)` returning `[0, 1]` is what the test above asks for, because the
only caller that passes `null` is a two-seat match without teams. If you find that ugly, change **both** the
test and the Javadoc to return an empty list and make the callers handle it — but do not leave them disagreeing.

- [ ] **Step 4: Run the test and watch it pass**

Run: `cd bridge && mvn -o -q -Dtest=SeatingTest test`
Expected: `Tests run: 6, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add bridge/src/main/java/mtgplayer/match/Seating.java bridge/src/test/java/mtgplayer/match/SeatingTest.java
git commit -m "bridge: sitzregel fuer teams - die teams wechseln sich ab

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: The headless match knows teams

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/match/AiMatch.java` (the `play(...)` overloads and the loop that
  builds `players`, around lines 88–105)
- Test: `bridge/src/test/java/mtgplayer/match/AiMatchSeatsTest.java` (new)

**Interfaces:**
- Consumes: `Seating.interleave(List<Integer>)` from Task 1, `HumanMatch.applyTeams(List<RegisteredPlayer>, List<Integer>)` (already in the branch).
- Produces:
  - `static List<RegisteredPlayer> AiMatch.registered(List<Deck> decks, List<String> names, List<AiConfig> configs, List<Integer> teams, Map<RegisteredPlayer, String> deckNames)` — package-private, builds the seats **in the interleaved order** and sets the team numbers;
  - `public static Result AiMatch.play(List<Deck> decks, List<String> names, List<AiConfig> configs, List<Integer> teams, int aiTimeout, int maxTurns, Consumer<String> log)` — `teams` may be `null` (no teams); the existing overloads pass `null`.

- [ ] **Step 1: Write the failing test**

```java
package mtgplayer.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import forge.deck.Deck;
import forge.game.player.RegisteredPlayer;
import mtgplayer.ai.AiConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Die Sitzliste der headless Partie: mit Teams abwechselnd sortiert, und die Teamnummer wandert mit dem
 *  Sitz mit - sonst traegt hinterher der falsche Sitz das falsche Team. */
class AiMatchSeatsTest {

    private static List<Deck> decks(int n) {
        List<Deck> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Deck("D" + i));
        }
        return out;
    }

    @Test
    void ohneTeamsBleibtDieReihenfolge() {
        List<RegisteredPlayer> rp = AiMatch.registered(decks(2), List.of("A", "B"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT), null, new HashMap<>());

        assertEquals(List.of("A", "B"), rp.stream().map(p -> p.getPlayer().getName()).toList());
        assertEquals(List.of(-1, -1), rp.stream().map(RegisteredPlayer::getTeamNumber).toList());
    }

    @Test
    void mitTeamsWechselnSichDieSitzeAb() {
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), new HashMap<>());

        assertEquals(List.of("A1", "B1", "A2", "B2"), rp.stream().map(p -> p.getPlayer().getName()).toList(),
                "abwechselnd, Sitz 0 bleibt vorn");
        assertEquals(List.of(1, 2, 1, 2), rp.stream().map(RegisteredPlayer::getTeamNumber).toList(),
                "die Teamnummer wandert mit dem Sitz mit");
    }

    @Test
    void deckNamenFolgenIhremSitz() {
        var deckNames = new HashMap<RegisteredPlayer, String>();
        List<RegisteredPlayer> rp = AiMatch.registered(decks(4), List.of("A1", "A2", "B1", "B2"),
                List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 1, 2, 2), deckNames);

        assertEquals("D2", deckNames.get(rp.get(1)), "Sitz 1 ist der alte Index 2 - mit dessen Deck");
    }
}
```

- [ ] **Step 2: Run the test and watch it fail**

Run: `cd bridge && mvn -o -q -Dtest=AiMatchSeatsTest test`
Expected: compile error, `AiMatch.registered` does not exist.

- [ ] **Step 3: Implement**

Replace the seat-building loop in `AiMatch.play(...)` with a call to the new helper and add the helper:

```java
    /**
     * Sitzliste der Partie. Mit Teams ({@code teams != null}) wechseln sich die Teams ab
     * ({@link Seating#interleave}), und Name, Deck, KI-Einstellung und Teamnummer wandern gemeinsam mit
     * dem Sitz - eine der vier Listen nicht mitzudrehen hiesse, dass hinterher der falsche Sitz das
     * falsche Team (oder Deck) traegt.
     */
    static List<RegisteredPlayer> registered(List<Deck> decks, List<String> names, List<AiConfig> configs,
                                             List<Integer> teams, Map<RegisteredPlayer, String> deckNames) {
        List<Integer> order = teams == null ? null : Seating.interleave(teams);
        List<RegisteredPlayer> players = new ArrayList<>();
        List<Integer> sortierteTeams = teams == null ? null : new ArrayList<>();
        for (int pos = 0; pos < decks.size(); pos++) {
            int i = order == null ? pos : order.get(pos);
            RegisteredPlayer rp = RegisteredPlayer.forCommander(decks.get(i));
            rp.setPlayer(configs.get(i).newLobbyPlayer(names.get(i)));
            players.add(rp);
            deckNames.put(rp, decks.get(i).getName());
            if (sortierteTeams != null) {
                sortierteTeams.add(teams.get(i));
            }
        }
        HumanMatch.applyTeams(players, sortierteTeams);
        return players;
    }
```

`play(...)` gains the `teams` parameter right after `configs`; the existing overloads pass `null`. Keep the
argument check (`2–6 Decks mit gleich vielen Namen`) and add: if `teams != null` it must have the same size.

- [ ] **Step 4: Run the test and watch it pass**

Run: `cd bridge && mvn -o -q -Dtest='AiMatchSeatsTest,AiMatchTest,SeatingTest' test`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add bridge/src
git commit -m "bridge: headless partie mit teams und abwechselnden sitzen

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The played match seats the same way

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/match/HumanMatch.java` (`start(...)` and `startSpectator(...)`, where
  `players` is filled and `applyTeams` is called)
- Test: `bridge/src/test/java/mtgplayer/match/HumanMatchTeamsTest.java` (extend),
  `bridge/src/test/java/mtgplayer/scene/TeamSceneTest.java` (extend)

**Interfaces:**
- Consumes: `Seating.interleave` (Task 1).
- Produces: no new API — `start`/`startSpectator` keep their signatures and seat the players interleaved when
  a team list is given.

- [ ] **Step 1: Write the failing tests**

Add to `HumanMatchTeamsTest`:

```java
    @Test
    void sitzeWerdenAbwechselndSortiertUndDieTeamsWandernMit() {
        // Lobby-Reihenfolge Du(1), KI 1(1), KI 2(2), KI 3(2) - gespielt wird abwechselnd.
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            RegisteredPlayer rp = new RegisteredPlayer(new Deck("D" + i));
            rp.setPlayer(new LobbyPlayerAi("S" + i, null));
            players.add(rp);
        }

        List<RegisteredPlayer> sortiert = HumanMatch.seated(players, List.of(1, 1, 2, 2));

        assertEquals(List.of("S0", "S2", "S1", "S3"),
                sortiert.stream().map(p -> p.getPlayer().getName()).toList());
        assertEquals(List.of(1, 2, 1, 2),
                sortiert.stream().map(RegisteredPlayer::getTeamNumber).toList());
    }

    @Test
    void ohneTeamsBleibtDieLobbyReihenfolge() {
        List<RegisteredPlayer> players = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RegisteredPlayer rp = new RegisteredPlayer(new Deck("D" + i));
            rp.setPlayer(new LobbyPlayerAi("S" + i, null));
            players.add(rp);
        }

        List<RegisteredPlayer> sortiert = HumanMatch.seated(players, null);

        assertEquals(List.of("S0", "S1", "S2"), sortiert.stream().map(p -> p.getPlayer().getName()).toList());
    }
```

Add to `TeamSceneTest` (this one proves the *engine's* turn order, not just a list):

```java
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void dieZugreihenfolgeWechseltZwischenDenTeams() {
        // Scene baut die Sitze in der gegebenen Reihenfolge; hier die, die HumanMatch erzeugen wuerde.
        Scene s = Scene.ofTeams(List.of(AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT, AiConfig.DEFAULT),
                List.of(1, 2, 1, 2), 3);

        List<Integer> reihenfolge = s.game().getPlayers().stream().map(Player::getTeam).toList();

        for (int i = 1; i < reihenfolge.size(); i++) {
            assertNotEquals(reihenfolge.get(i - 1), reihenfolge.get(i),
                    "Sitz " + i + " zieht direkt nach seinem eigenen Team: " + reihenfolge);
        }
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd bridge && mvn -o -q -Dtest='HumanMatchTeamsTest,TeamSceneTest' test`
Expected: compile error, `HumanMatch.seated` does not exist (the scene test may already pass — it pins the
property for later).

- [ ] **Step 3: Implement**

```java
    /**
     * Sitzreihenfolge der Partie: mit Teams abwechselnd ({@link Seating#interleave}), sonst wie gegeben.
     * Setzt dabei die Teamnummern, weil beides zusammengehoert - die Teamliste kommt in Lobby-Reihenfolge,
     * und nur die Sitze zu drehen hiesse, dass hinterher der falsche Sitz das falsche Team traegt.
     */
    static List<RegisteredPlayer> seated(List<RegisteredPlayer> players, List<Integer> teams) {
        if (teams == null) {
            applyTeams(players, null);
            return players;
        }
        List<Integer> order = Seating.interleave(teams);
        List<RegisteredPlayer> sortiert = new ArrayList<>();
        List<Integer> sortierteTeams = new ArrayList<>();
        for (int i : order) {
            sortiert.add(players.get(i));
            sortierteTeams.add(teams.get(i));
        }
        applyTeams(sortiert, sortierteTeams);
        return sortiert;
    }
```

In `start(...)` and `startSpectator(...)`, replace `applyTeams(players, teams);` with
`players = seated(players, teams);` (declare `players` as a non-final local). Everything downstream —
`guis`, `deckNames`, `hosted.startMatch` — keeps working because it is keyed by `RegisteredPlayer`, not by
index. The human seat stays at index 0 because `Seating.interleave` keeps index 0 in front, which is also what
`HostedMatch.startMatch`'s stable sort expects.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd bridge && mvn -o -q -Dtest='HumanMatchTeamsTest,TeamSceneTest,HumanMatchStartTeamsTest,SnapshotTeamTest,TeamConcedeTest,PartnerHandTest' test`
Expected: green. Several of these build four seats with `1,1,2,2` and assert the resulting order; where they
assert the **seat order**, update them to the interleaved order — those updates are the proof that the team
list really moves with the seats. Do not weaken an assertion to make it pass.

- [ ] **Step 5: Commit**

```bash
git add bridge/src
git commit -m "bridge: auch die gespielte partie setzt die teams abwechselnd

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: `--teams` in the bench arguments

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/bench/BenchArgs.java`
- Modify: `bridge/src/main/java/mtgplayer/bench/SubprocessRunner.java` (the method that builds the child's
  argument list, around line 110)
- Test: `bridge/src/test/java/mtgplayer/bench/BenchArgsTest.java` (extend) and
  `bridge/src/test/java/mtgplayer/bench/BenchSubprocessTest.java` (extend — it already exercises the child
  process path; put the `childArgs` cases there instead of adding a second class)

**Interfaces:**
- Consumes: nothing.
- Produces: `BenchArgs` gains a trailing component `boolean teams`; `BenchArgs.parse` accepts `--teams` as a
  flag without a value (like `--in-process`).
- No new validation: `--teams` without usable deck references fails exactly where a two-seat run fails today,
  in `Bench.loadDeck` ("Deck-Referenz muss mit 'precon:' oder 'saved:' beginnen"). Do not add a second check
  that would drift from it.

- [ ] **Step 1: Write the failing tests**

```java
    @Test
    void teamsIstEinSchalterOhneWert() {
        BenchArgs a = BenchArgs.parse(new String[] {"--teams", "--games", "8"});
        assertTrue(a.teams());
        assertEquals(8, a.games(), "der naechste Schalter wird nicht als Wert von --teams verschluckt");
    }

    @Test
    void ohneSchalterKeineTeams() {
        assertFalse(BenchArgs.parse(new String[] {"--games", "8"}).teams());
    }
```

and for the child process:

```java
    @Test
    void teamsErreichtDenKindprozess() {
        BenchArgs a = BenchArgs.parse(new String[] {"--teams", "--games", "2"});

        List<String> kindArgs = SubprocessRunner.childArgs(a, 0);

        assertTrue(kindArgs.contains("--teams"),
                "ohne den Schalter wuerde das Kind ein Spiel ohne Teams spielen und der Lauf misst das Falsche");
    }

    @Test
    void ohneTeamsKeinSchalterImKindprozess() {
        assertFalse(SubprocessRunner.childArgs(BenchArgs.parse(new String[] {"--games", "2"}), 0).contains("--teams"));
    }
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd bridge && mvn -o -q -Dtest='BenchArgsTest,BenchSubprocessTest' test`
Expected: compile error (`teams()` / `childArgs` missing).

- [ ] **Step 3: Implement**

`BenchArgs` gets the component and a parse branch next to `--in-process`:

```java
            case "--teams" -> teams = true;
```

In `SubprocessRunner`, extract the argument list into a package-private, testable method
`static List<String> childArgs(BenchArgs args, int i)` (move the existing `out.add(...)` block into it
unchanged) and append:

```java
        if (args.teams()) {
            out.add("--teams");
        }
```

- [ ] **Step 4: Run them and watch them pass**

Run: `cd bridge && mvn -o -q -Dtest='BenchArgsTest,BenchSubprocessTest,BenchStatsTest' test`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add bridge/src
git commit -m "bridge: bench-schalter --teams, auch fuer den kindprozess

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: The bench builds a 2v2

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/bench/Bench.java` (`playOne`, around lines 135–180)
- Test: `bridge/src/test/java/mtgplayer/bench/BenchSeatsTest.java` (new)

**Interfaces:**
- Consumes: `BenchArgs.teams()` (Task 4), `AiMatch.play(..., teams, ...)` (Task 2).
- Produces: `record Bench.Besetzung(List<String> namen, List<Integer> teams, List<String> deckRefs, List<AiConfig> configs)`
  and `static Besetzung Bench.besetzung(BenchArgs args, int i)` — package-private, pure (no deck loading), so
  the seat construction can be tested without Forge.

- [ ] **Step 1: Write the failing test**

```java
package mtgplayer.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Die Besetzung eines Bench-Spiels: ohne --teams wie bisher zwei Sitze mit Wechsel je Spiel, mit --teams
 *  vier Sitze, gespiegeltes Deckpaar und abwechselnde Teams. */
class BenchSeatsTest {

    private static BenchArgs args(String extra) {
        return BenchArgs.parse(("--deck-a precon:P --deck-b precon:Q --a sim --b std " + extra).split(" "));
    }

    @Test
    void ohneTeamsZweiSitzeMitWechsel() {
        assertEquals(List.of("A", "B"), Bench.besetzung(args(""), 0).namen());
        assertEquals(List.of("B", "A"), Bench.besetzung(args(""), 1).namen(), "jedes zweite Spiel gedreht");
        assertEquals(List.of(-1, -1), Bench.besetzung(args(""), 0).teams());
    }

    @Test
    void mitTeamsVierSitzeGespiegelt() {
        Bench.Besetzung b = Bench.besetzung(args("--teams"), 0);

        assertEquals(List.of("A1", "B1", "A2", "B2"), b.namen(), "abwechselnd");
        assertEquals(List.of(1, 2, 1, 2), b.teams());
        assertEquals(List.of("precon:P", "precon:P", "precon:Q", "precon:Q"), b.deckRefs(),
                "beide Teams spielen dasselbe Deckpaar, benachbarte Sitze dasselbe Deck");
        assertEquals(List.of("sim", "std", "sim", "std"), b.configs().stream().map(c -> c.spec()).toList(),
                "je Team eine Einstellung, auf beiden Sitzen des Teams");
    }

    @Test
    void mitTeamsRotiertDerErsteSitzUeberDieSpiele() {
        List<String> ersteSitze = List.of(0, 1, 2, 3).stream()
                .map(i -> Bench.besetzung(args("--teams"), i).namen().get(0)).toList();

        assertEquals(4, ersteSitze.stream().distinct().count(),
                "ueber vier Spiele faengt jeder Sitz einmal an, sonst klebt der Startvorteil an einem Team: " + ersteSitze);
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd bridge && mvn -o -q -Dtest=BenchSeatsTest test`
Expected: compile error, `Bench.besetzung` does not exist.

- [ ] **Step 3: Implement**

```java
    /** Besetzung eines einzelnen Bench-Spiels: Namen, Teams, Deck-Referenzen und KI-Einstellungen je Sitz. */
    record Besetzung(List<String> namen, List<Integer> teams, List<String> deckRefs, List<AiConfig> configs) { }

    /**
     * Wer sitzt in Spiel {@code i} wo? Ohne {@code --teams} wie bisher zwei Sitze, deren Reihenfolge jedes
     * zweite Spiel dreht. Mit {@code --teams} vier Sitze: beide Teams spielen dasselbe Deckpaar (Spiegel),
     * die Teams wechseln sich ab, und ueber vier Spiele faengt jeder Sitz einmal an - sonst wuerde der
     * Startvorteil an einem Team kleben und die Messung verfaelschen.
     */
    static Besetzung besetzung(BenchArgs args, int i) {
        if (!args.teams()) {
            boolean swap = i % 2 != 0;
            return new Besetzung(
                    swap ? List.of("B", "A") : List.of("A", "B"),
                    List.of(-1, -1),
                    swap ? List.of(args.deckB(), args.deckA()) : List.of(args.deckA(), args.deckB()),
                    swap ? List.of(args.b(), args.a()) : List.of(args.a(), args.b()));
        }
        List<String> namen = new ArrayList<>(List.of("A1", "B1", "A2", "B2"));
        List<Integer> teams = new ArrayList<>(List.of(1, 2, 1, 2));
        List<String> decks = new ArrayList<>(List.of(args.deckA(), args.deckA(), args.deckB(), args.deckB()));
        List<AiConfig> configs = new ArrayList<>(List.of(args.a(), args.b(), args.a(), args.b()));
        int dreh = i % 4;
        java.util.Collections.rotate(namen, -dreh);
        java.util.Collections.rotate(teams, -dreh);
        java.util.Collections.rotate(decks, -dreh);
        java.util.Collections.rotate(configs, -dreh);
        return new Besetzung(List.copyOf(namen), List.copyOf(teams), List.copyOf(decks), List.copyOf(configs));
    }
```

`playOne` uses it: load the decks from `b.deckRefs()` (the same `loadDeck` as today, one load per distinct
reference), and call
`AiMatch.play(decks, b.namen(), b.configs(), args.teams() ? b.teams() : null, args.timeout(), args.turns(), log)`.

The winner in a team run is a **seat** name (`"A1"`); the record wants the team. Map it:

```java
    /** "A1"/"A2" -> "A"; ohne Teams bleibt der Sitzname stehen. Null (Unentschieden) bleibt null. */
    private static String siegerTeam(String sitz, boolean teams) {
        return sitz == null || !teams ? sitz : sitz.substring(0, 1);
    }
```

- [ ] **Step 4: Run it and watch it pass**

Run: `cd bridge && mvn -o -q -Dtest='BenchSeatsTest,BenchStatsTest' test`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add bridge/src
git commit -m "bridge: bench besetzt vier sitze im spiegel, teams wechseln sich ab

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: The report says it was a 2v2

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/bench/Bench.java` (`markdown(...)`, around line 219, and the JSON
  writer next to it)
- Test: `bridge/src/test/java/mtgplayer/bench/BenchReportTest.java` (new — die vorhandenen Bench-Tests sind
  `BenchArgsTest`, `BenchStatsTest`, `BenchSubprocessTest` und `BenchSmokeTest`; keiner liest den Bericht)

**Interfaces:**
- Consumes: `BenchArgs.teams()`, `Bench.besetzung(...)`.
- Produces: no API; the report text changes.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void berichtNenntDieAufstellungUndDieTeams() {
        BenchArgs args = BenchArgs.parse("--teams --deck-a precon:P --deck-b precon:Q --a sim --b std --games 2".split(" "));
        List<GameRecord> spiele = List.of(
                new GameRecord(0, 1L, "A1", "A", "AllOpposingTeamsLost", 20, 1000, false),
                new GameRecord(1, 2L, "B1", "B", "AllOpposingTeamsLost", 24, 1000, false));

        String md = Bench.markdownFuerTest(args, spiele, BenchStats.summarize(spiele));

        assertTrue(md.contains("2v2"), "die Kopfzeile sagt, dass es eine Teampartie war:\n" + md);
        assertTrue(md.contains("Team A") && md.contains("Team B"), "die Tabelle spricht von Teams:\n" + md);
        assertTrue(md.contains("precon:P") && md.contains("precon:Q"), "das Deckpaar steht im Kopf");
    }
```

(`markdownFuerTest` is a package-private alias for the existing private `markdown(...)`; add it rather than
widening the original, and say so in a one-line comment.)

- [ ] **Step 2: Run it and watch it fail**

Run: `cd bridge && mvn -o -q -Dtest=BenchReportTest test`
Expected: FAIL — no "2v2" and no "Team A" in the output.

- [ ] **Step 3: Implement**

In `markdown(...)`, when `args.teams()`:
- the title line becomes `# Bench 2v2 <a.spec()> vs <b.spec()>`;
- the deck line becomes `Deckpaar (beide Teams): P + Q · …`;
- the table header's `A`/`B` become `Team A`/`Team B`, and the win-rate line says `Siegquote Team A`.

Without `--teams` the output stays byte-identical to today — keep the existing strings on that path.

The JSON next to it gains `"teams": true|false` and, per game, the seat line-up of that game
(`besetzung(args, i).namen()` zipped with `.teams()`), so a later run can be compared seat by seat.

- [ ] **Step 4: Run it and watch it pass**

Run: `cd bridge && mvn -o -q -Dtest='BenchReportTest,BenchStatsTest,BenchSeatsTest' test`
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add bridge/src
git commit -m "bridge: bench-bericht nennt aufstellung und teams

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Prove it end to end, then write it down

**Files:**
- Modify: `README.md` (the "Bench (AI vs. AI)" section and one sentence in "Team modes")
- Test: a real short bench run (not a unit test)

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

- [ ] **Step 1: Run the full bridge suite**

Run: `cd bridge && mvn -o test`
Expected: `Tests run: …, Failures: 0, Errors: 0`. Note the count in your report.

- [ ] **Step 2: Run a real 2v2 bench, four games**

```bash
cd bridge && mvn -o -q compile exec:java -Dmtgplayer.data="$PWD/../.bench-live" \
  -Dexec.args="--bench --teams --games 4 --a std:Default --b std:Default \
  --deck-a 'precon:Abzan Armor [TDC] [2025]' --deck-b 'precon:Adaptive Enchantment [C18] [2018]' \
  --seed 1 --turns 60 --timeout 2"
```

Standard AI on both sides and a low turn cap keep it to minutes. Then **read the report** under
`.bench-live/bench/` im Arbeitsbaum (Kratzverzeichnis, vor dem Commit loeschen) and check, in your own words in the report:
- four games ran, each with four seats;
- the per-game rows name a winning **team**, not a seat;
- the seat line-up rotates across the four games;
- the win rate and its interval are computed over the decided games.

This is the step that catches what unit tests cannot: a flag that never reaches the child process, a seat
list that looks right in a test and wrong in a real match.

- [ ] **Step 3: Document it**

In `README.md`, extend the bench option table with:

```markdown
| `--teams` | 2v2: four seats, both teams play the deck pair `--deck-a` + `--deck-b`, `--a`/`--b` are the per-team AI settings (flag, no value) | off |
```

and add to the "Team modes" section:

```markdown
Seats are arranged so the teams alternate around the table — your lobby order decides who plays *with* whom,
not who plays *when*. (Turn order follows seat order, so two seats of one team side by side would give that
team two turns in a row.)
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: bench-schalter --teams und die abwechselnde sitzordnung

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Finishing

1. Remove the temporary `forge/forge-gui/res` symlink from the worktree (`rm forge/forge-gui/res && rmdir
   forge/forge-gui`), leaving `forge/` itself in place, and check `git status` shows no deleted gitlink.
2. Full bridge suite once more.
3. Merge to `main` (`git merge --ff-only <branch>`), remove the worktree and the branch, push.
