# Kampfanzeige – wer greift wen an – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ziel und Blockerzuordnung jedes Angreifers sichtbar machen – als Panel „Kampf" in der rechten Spalte und als Marke auf der Karte.

**Architecture:** Die Bridge serialisiert Forges `CombatView` als neues Snapshot-Feld `combat` (eine Zeile je Angreifer). Im Web steckt die gesamte Auswertung in einer reinen Modul-Datei `web/src/combat.ts`, die sowohl das neue Panel `Combat.tsx` als auch die Kartenmarke in `CardBox.tsx` speist – Liste und Brett können so nie Verschiedenes behaupten.

**Tech Stack:** Java 21 (Records, Maven, JUnit 5), Forge-Views (`GameView`, `CombatView`), React 18 + TypeScript, Zustand, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-24-kampfanzeige-design.md` – bei Widersprüchen gilt die Spec.

## Global Constraints

- **Niemals im Hauptbaum `/home/kevin/projects/MTG-Player` kompilieren oder Maven laufen lassen.** Kevins Bridge läuft dort als `mvn -q compile exec:java` aus `bridge/target/classes`; ein Neuübersetzen unter der laufenden JVM hat seine Partie schon zweimal mit `NoSuchMethodError` abgeschossen. Java-Arbeit passiert im Worktree (Task 1, Setup dort beschrieben), das Ergebnis wird per `git cherry-pick` in den Hauptbaum geholt.
- **Niemals `mvn clean`.**
- Maven-Aufrufe mit `timeout 590` und Tool-Timeout 600000 ms.
- Vor einem eigenen Maven-Lauf prüfen, dass außer Kevins Bridge kein weiterer Maven läuft: `ps -eo args= | grep -c '[c]lassworlds'` muss `1` oder `0` sein; bei mehr 20 s warten und erneut prüfen.
- Tests dürfen **nie** nach `~/.mtg-player` schreiben (surefire setzt `mtgplayer.data` auf `target/test-data`, das bleibt so).
- Kein `pkill -f java`; eigene Prozesse nur per PID beenden. Ports 8080/8081 gehören Kevins Bridge und bleiben unberührt; der Vite-Dev-Server für Screenshots läuft auf **5199**.
- Commits: deutsch, klein geschrieben, Präfix `bridge:` / `ui:` / `docs:` / `test:`. Trailer exakt `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`, kein weiterer Co-Author.
- Kommentare und Bezeichner im Projektstil: deutsche Kommentare, die das **Warum** erklären (siehe Nachbarcode); keine Umlaute in Commit-Titeln.
- Genau eines von `defenderPlayer` / `defenderCard` ist gesetzt; fehlt beides, bleibt die Zeile trotzdem erhalten.

---

## Dateien

| Datei | Rolle |
|---|---|
| `bridge/src/main/java/mtgplayer/protocol/Snapshot.java` | neues Feld `combat` + Record `AttackSnap` |
| `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java` | liest `GameView.getCombat()` aus |
| `bridge/src/test/java/mtgplayer/protocol/CombatSnapshotTest.java` | Szenentest für beides |
| `web/src/protocol.ts` | `AttackSnap` + `combat?` am `Snapshot` |
| `web/src/combat.ts` | reine Logik: Gruppierung + Kartenmarke |
| `web/src/combat.test.ts` | Vitest dazu |
| `web/src/components/Combat.tsx` | Panel „Kampf" |
| `web/src/components/Table.tsx` | hängt das Panel in `.side` ein |
| `web/src/components/CardBox.tsx` | Marke „→ Tom" / „blockt Krenko" |
| `web/src/styles.css` | Styles für das Panel |
| `web/fixtures/table.json`, `web/fixtures/spectator.json` | Kampfdaten für Screenshots |

---

### Task 1: Bridge – `combat` im Snapshot

**Files:**
- Modify: `bridge/src/main/java/mtgplayer/protocol/Snapshot.java`
- Modify: `bridge/src/main/java/mtgplayer/protocol/StateSerializer.java`
- Create: `bridge/src/test/java/mtgplayer/protocol/CombatSnapshotTest.java`

**Interfaces:**
- Consumes: `Scene` (`bridge/src/test/java/mtgplayer/scene/Scene.java`), `ViewContext.plain(PlayerView)`.
- Produces: `Snapshot.combat()` → `List<Snapshot.AttackSnap>` oder `null`; `record AttackSnap(int attacker, Integer defenderPlayer, Integer defenderCard, List<Integer> blockers)`. Task 2 bildet das 1:1 in TypeScript ab.

- [ ] **Step 1: Worktree anlegen (hier wird gebaut, nicht im Hauptbaum)**

```bash
SCRATCH=/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad
cd /home/kevin/projects/MTG-Player && git worktree add --detach $SCRATCH/kampf HEAD
cd $SCRATCH/kampf && rmdir forge && ln -s /home/kevin/projects/MTG-Player/forge forge
cp /home/kevin/projects/MTG-Player/bridge/assets/forge.profile.properties bridge/assets/
```

Existiert `$SCRATCH/kampf` schon, diesen Block überspringen und darin weiterarbeiten. Der Symlink `forge` sorgt dafür, dass `bridge/assets/res -> ../../forge/forge-gui/res` auf die Kartendaten des Hauptbaums zeigt; ohne ihn schlägt `ForgeBoot.init()` fehl. Ab hier spielt sich **alles** in `$SCRATCH/kampf` ab.

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

Datei `$SCRATCH/kampf/bridge/src/test/java/mtgplayer/protocol/CombatSnapshotTest.java`:

```java
package mtgplayer.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import mtgplayer.ai.AiConfig;
import mtgplayer.forge.ForgeBoot;
import mtgplayer.scene.Scene;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Kampfdaten im Snapshot (Spec 2026-09-24-kampfanzeige, §1/§2): wer greift wen an und wer blockt.
 *  Die Szene setzt den Kampf direkt ueber {@link Combat} statt ihn auszuspielen - so steht die Stellung
 *  deterministisch, ohne von KI-Entscheidungen abzuhaengen. {@code Game.updateCombatForView()} baut aus
 *  dem Combat die {@code CombatView}, die der Serializer liest. */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CombatSnapshotTest {
    private static final String BEAR = "Grizzly Bears";
    private static final String WALL = "Wall of Wood";

    @BeforeAll
    static void boot() {
        ForgeBoot.init();
    }

    /** Sitz 0 greift mit einem Baeren Sitz 1 an; Sitz 1 hat eine Mauer als moeglichen Blocker. */
    private static Scene attackScene() {
        Scene s = Scene.twoPlayers(AiConfig.DEFAULT, AiConfig.DEFAULT);
        s.card(BEAR, s.player(0), ZoneType.Battlefield);
        s.card(WALL, s.player(1), ZoneType.Battlefield);
        s.cards("Forest", 10, s.player(0), ZoneType.Library);
        s.cards("Forest", 10, s.player(1), ZoneType.Library);
        s.setPhase(PhaseType.COMBAT_DECLARE_BLOCKERS, s.player(0));
        return s;
    }

    private static Card only(Scene s, int seat, String name) {
        for (Card c : s.player(seat).getCardsIn(ZoneType.Battlefield)) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError(name + " nicht im Spiel von Sitz " + seat);
    }

    private static Snapshot snapshotOf(Scene s) {
        return StateSerializer.snapshot(s.game().getView(), ViewContext.plain(s.player(0).getView()));
    }

    @Test
    void ohneKampfKeineKampfdaten() {
        Scene s = attackScene();
        assertNull(snapshotOf(s).combat());
    }

    @Test
    void angreiferTraegtSeinZiel() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        List<Snapshot.AttackSnap> attacks = snapshotOf(s).combat();
        assertNotNull(attacks);
        assertEquals(1, attacks.size());
        Snapshot.AttackSnap a = attacks.get(0);
        assertEquals(bear.getId(), a.attacker());
        assertEquals(s.player(1).getId(), a.defenderPlayer());
        assertNull(a.defenderCard());
        assertNull(a.blockers());
        assertTrue(snapshotOf(s).cards().containsKey(bear.getId()), "Angreifer fehlt in der Kartentabelle");
    }

    @Test
    void geblockterAngreiferTraegtSeineBlocker() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Card wall = only(s, 1, WALL);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        combat.addBlocker(bear, wall);
        combat.setBlocked(bear, true);
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        Snapshot.AttackSnap a = snapshotOf(s).combat().get(0);
        assertEquals(List.of(wall.getId()), a.blockers());
    }

    /** Solange der Mensch noch zuteilt, ist das Band nicht als "geblockt" markiert: Forge fuehrt die
     *  Zuordnung dann nur als geplante Blocker (GameView.updateCombat uebergibt sie separat). Genau in
     *  diesem Moment ist die Anzeige am nuetzlichsten, also muss der Serializer darauf zurueckfallen. */
    @Test
    void geplanteBlockerZaehlenAuch() {
        Scene s = attackScene();
        Card bear = only(s, 0, BEAR);
        Card wall = only(s, 1, WALL);
        Combat combat = new Combat(s.player(0));
        combat.addAttacker(bear, s.player(1));
        combat.addBlocker(bear, wall);
        s.game().getPhaseHandler().setCombat(combat);
        s.game().updateCombatForView();

        Snapshot.AttackSnap a = snapshotOf(s).combat().get(0);
        assertEquals(List.of(wall.getId()), a.blockers());
    }
}
```

- [ ] **Step 3: Test laufen lassen – er muss scheitern**

```bash
cd $SCRATCH/kampf/bridge && timeout 590 mvn -q -Dtest=CombatSnapshotTest -DfailIfNoSpecifiedTests=false test
```

Erwartet: Übersetzungsfehler „cannot find symbol: method combat()" bzw. „AttackSnap".

- [ ] **Step 4: `AttackSnap` und das Feld in `Snapshot.java` ergänzen**

`combat` kommt als **letztes** Feld in den Record `Snapshot` (nach `spectator`):

```java
        PromptSnap prompt,
        Boolean spectator,
        /** Eine Zeile je Angreifer, solange ein Kampf laeuft; sonst null (siehe StateSerializer). */
        List<AttackSnap> combat) {
```

und als neuer Record neben `StackSnap`:

```java
    /** Ein Angreifer mit seinem Ziel und seinen Blockern. Genau eines von {@code defenderPlayer} und
     *  {@code defenderCard} ist gesetzt (Forges Verteidiger ist ein Spieler ODER eine Karte - Planeswalker,
     *  Battle); liefert Forge kein Ziel, bleiben beide null und die Zeile bleibt trotzdem erhalten. */
    public record AttackSnap(
            int attacker,
            Integer defenderPlayer,
            Integer defenderCard,
            /** Blocker dieses Angreifers, null wenn keiner zugeteilt ist. */
            List<Integer> blockers) { }
```

- [ ] **Step 5: `StateSerializer.snapshot` erweitern**

Neue Importe: `forge.game.GameEntityView`, `forge.game.combat.CombatView`.

Direkt vor `PlayerView turn = gv.getPlayerTurn();` einfügen:

```java
        List<Snapshot.AttackSnap> combat = combat(gv, ctx, cards);
```

Das neue Argument `combat` kommt im `new Snapshot(...)`-Aufruf ans Ende, hinter `ctx.spectator() ? Boolean.TRUE : null`.

Dazu die neue Methode (neben `stackItem`):

```java
    /** Forges CombatView in Zeilen je Angreifer uebersetzen. Null (nicht leere Liste), wenn kein Kampf
     *  laeuft - das UI prueft nur "da oder nicht". Karten, die noch nicht in der Kartentabelle stehen,
     *  werden dabei nachgetragen. */
    private static List<Snapshot.AttackSnap> combat(GameView gv, ViewContext ctx,
                                                    Map<Integer, Snapshot.CardSnap> cards) {
        CombatView cv = gv.getCombat();
        if (cv == null || cv.getNumAttackers() == 0) return null;
        List<Snapshot.AttackSnap> out = new ArrayList<>();
        for (CardView attacker : cv.getAttackers()) {
            if (attacker == null) continue;
            cards.computeIfAbsent(attacker.getId(), id -> cardSnap(attacker, ctx));
            Integer defenderPlayer = null;
            Integer defenderCard = null;
            GameEntityView defender = cv.getDefender(attacker);
            if (defender instanceof PlayerView p) {
                defenderPlayer = p.getId();
            } else if (defender instanceof CardView c) {
                defenderCard = c.getId();
                cards.computeIfAbsent(c.getId(), id -> cardSnap(c, ctx));
            }
            out.add(new Snapshot.AttackSnap(attacker.getId(), defenderPlayer, defenderCard,
                    blockers(cv, attacker, ctx, cards)));
        }
        return out.isEmpty() ? null : out;
    }

    /** Blocker eines Angreifers. Solange das Band nicht als geblockt markiert ist (der Mensch teilt noch
     *  zu), fuehrt Forge die Zuordnung nur als geplante Blocker - dann zaehlen die. */
    private static List<Integer> blockers(CombatView cv, CardView attacker, ViewContext ctx,
                                          Map<Integer, Snapshot.CardSnap> cards) {
        FCollectionView<CardView> blockers = cv.getBlockers(attacker);
        if (blockers == null || blockers.isEmpty()) {
            blockers = cv.getPlannedBlockers(attacker);
        }
        if (blockers == null || blockers.isEmpty()) return null;
        List<Integer> ids = new ArrayList<>();
        for (CardView b : blockers) {
            if (b == null) continue;
            cards.computeIfAbsent(b.getId(), id -> cardSnap(b, ctx));
            ids.add(b.getId());
        }
        return ids.isEmpty() ? null : ids;
    }
```

`FCollection` ist ein `FCollectionView` – passt der Rückgabetyp von `getBlockers` nicht, die lokale Variable auf den Typ setzen, den Forge liefert (`FCollection<CardView>`), und nichts weiter ändern.

- [ ] **Step 6: Alle Aufrufer von `new Snapshot(...)` reparieren**

```bash
cd $SCRATCH/kampf && grep -rn "new Snapshot(" bridge/src
```

Jeder Treffer braucht das neue letzte Argument (`null`, wenn dort kein Kampf bekannt ist).

- [ ] **Step 7: Test laufen lassen – jetzt grün**

```bash
cd $SCRATCH/kampf/bridge && timeout 590 mvn -q -Dtest=CombatSnapshotTest -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 8: Rückwirkungen prüfen (Protokoll- und GUI-Tests)**

```bash
cd $SCRATCH/kampf/bridge && timeout 590 mvn -q -Dtest='mtgplayer.protocol.*Test,mtgplayer.gui.*Test' -DfailIfNoSpecifiedTests=false test
grep -h "Tests run" target/surefire-reports/*.txt
```

Erwartet: keine Fehler. Schlägt etwas fehl, das nicht an dieser Änderung liegt, im Report festhalten statt es zu übergehen.

- [ ] **Step 9: Im Worktree committen und in den Hauptbaum holen**

```bash
cd $SCRATCH/kampf && git add -A bridge && git commit -m "bridge: snapshot traegt ziel und blocker jedes angreifers

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
HASH=$(git -C $SCRATCH/kampf rev-parse HEAD)
cd /home/kevin/projects/MTG-Player && git cherry-pick $HASH && git log --oneline -1
```

Der Cherry-Pick ändert nur Quelltext – **kein** Maven-Lauf im Hauptbaum.

---

### Task 2: Web – Protokolltypen und `combat.ts`

**Files:**
- Modify: `web/src/protocol.ts`
- Create: `web/src/combat.ts`
- Create: `web/src/combat.test.ts`

**Interfaces:**
- Consumes: `Snapshot.combat?: AttackSnap[]` aus Task 1.
- Produces:
  - `combatGroups(state: Snapshot): CombatGroup[]` mit `CombatGroup = { key: string; label: string; sub?: string; attackers: { card: CardSnap; blockers: CardSnap[] }[] }`
  - `combatTag(state: Snapshot, cardId: number): CombatTag | undefined` mit `CombatTag = { kind: "atk" | "blk"; label: string; title: string }`
  - Task 3 nutzt `combatGroups`, Task 4 nutzt `combatTag`.

Alle Kommandos in diesem Task laufen im **Hauptbaum** unter `/home/kevin/projects/MTG-Player/web` (kein Maven, kein Java).

- [ ] **Step 1: Protokolltypen ergänzen**

In `web/src/protocol.ts` vor `export interface Snapshot`:

```ts
/** Ein Angreifer mit Ziel und Blockern (Bridge: Snapshot.AttackSnap). Genau eines von defenderPlayer und
 *  defenderCard ist gesetzt; fehlt beides, ist das Ziel nicht aufloesbar. */
export interface AttackSnap {
  attacker: number;
  defenderPlayer?: number;
  defenderCard?: number;
  blockers?: number[];
}
```

und im `Snapshot` als letztes Feld:

```ts
  /** Zeilen je Angreifer, solange ein Kampf laeuft; sonst fehlt das Feld. */
  combat?: AttackSnap[];
```

- [ ] **Step 2: Den fehlschlagenden Test schreiben**

Datei `web/src/combat.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import type { CardSnap, Snapshot } from "./protocol";
import { combatGroups, combatTag } from "./combat";

const card = (id: number, name: string, extra: Partial<CardSnap> = {}): CardSnap =>
  ({ id, faceDown: false, name, ...extra });

/** Minimaler Snapshot: nur was combat.ts liest (players, cards, combat). */
const snap = (combat: Snapshot["combat"], cards: CardSnap[], players = [
  { id: 1, name: "Du" }, { id: 2, name: "KI 1" },
]): Snapshot => ({
  type: "state", turn: 5, gameOver: false,
  players: players.map((p) => ({
    ...p, isAi: p.id !== 1, life: 40, commanderDamage: {}, hand: [], librarySize: 60,
    graveyard: [], exile: [], command: [], battlefield: [], manaPool: {}, hasPriority: false,
  })),
  stack: [],
  cards: Object.fromEntries(cards.map((c) => [String(c.id), c])),
  stops: { own: [], opp: [] }, fullControl: false,
  prompt: { message: "", okLabel: "", cancelLabel: "", okEnabled: false, cancelEnabled: false, seq: 0 },
  combat,
});

describe("combatGroups", () => {
  it("gruppiert Angreifer nach dem verteidigenden Spieler", () => {
    const s = snap(
      [{ attacker: 10, defenderPlayer: 1 }, { attacker: 11, defenderPlayer: 1 }],
      [card(10, "Krenko"), card(11, "Goblin")],
    );
    const groups = combatGroups(s);
    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe("p:1");
    expect(groups[0].label).toBe("Du");
    expect(groups[0].attackers.map((a) => a.card.name)).toEqual(["Krenko", "Goblin"]);
  });

  it("nennt bei Kartenzielen den Kartennamen und den Besitzer", () => {
    const s = snap(
      [{ attacker: 10, defenderCard: 20 }],
      [card(10, "Krenko"), card(20, "Teferi", { controller: 2 })],
    );
    const [g] = combatGroups(s);
    expect(g.key).toBe("c:20");
    expect(g.label).toBe("Teferi");
    expect(g.sub).toBe("bei KI 1");
  });

  it("haengt die Blocker an ihren Angreifer", () => {
    const s = snap(
      [{ attacker: 10, defenderPlayer: 1, blockers: [30, 31] }],
      [card(10, "Krenko"), card(30, "Mauer"), card(31, "Baer")],
    );
    expect(combatGroups(s)[0].attackers[0].blockers.map((b) => b.name)).toEqual(["Mauer", "Baer"]);
  });

  it("sammelt Angreifer ohne aufloesbares Ziel in einer eigenen Gruppe", () => {
    const s = snap([{ attacker: 10 }, { attacker: 11, defenderPlayer: 99 }],
      [card(10, "Krenko"), card(11, "Goblin")]);
    const groups = combatGroups(s);
    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBe("none");
    expect(groups[0].label).toBe("Angriff");
    expect(groups[0].attackers).toHaveLength(2);
  });

  it("ueberspringt unbekannte Karten-Ids", () => {
    const s = snap([{ attacker: 10, defenderPlayer: 1, blockers: [30, 99] }, { attacker: 98, defenderPlayer: 1 }],
      [card(10, "Krenko"), card(30, "Mauer")]);
    const groups = combatGroups(s);
    expect(groups[0].attackers).toHaveLength(1);
    expect(groups[0].attackers[0].blockers.map((b) => b.name)).toEqual(["Mauer"]);
  });

  it("ist leer, wenn kein Kampf laeuft", () => {
    expect(combatGroups(snap(undefined, []))).toEqual([]);
  });
});

describe("combatTag", () => {
  it("nennt beim Angreifer sein Ziel", () => {
    const s = snap([{ attacker: 10, defenderPlayer: 2 }], [card(10, "Krenko")]);
    expect(combatTag(s, 10)).toEqual({ kind: "atk", label: "→ KI 1", title: "greift KI 1 an" });
  });

  it("kuerzt lange Namen im Label, nicht im Tooltip", () => {
    const s = snap([{ attacker: 10, defenderCard: 20, blockers: [30] }],
      [card(10, "Krenko"), card(20, "Teferi, Hero of Dominaria", { controller: 2 }), card(30, "Mauer")]);
    expect(combatTag(s, 10)).toEqual({
      kind: "atk", label: "→ Teferi, Hero…", title: "greift Teferi, Hero of Dominaria an",
    });
    expect(combatTag(s, 30)).toEqual({ kind: "blk", label: "blockt Krenko", title: "blockt Krenko" });
  });

  it("zaehlt beim Mehrfachblock die weiteren Angreifer mit", () => {
    const s = snap(
      [{ attacker: 10, defenderPlayer: 1, blockers: [30] }, { attacker: 11, defenderPlayer: 1, blockers: [30] }],
      [card(10, "Krenko"), card(11, "Goblin"), card(30, "Mauer")],
    );
    expect(combatTag(s, 30)).toEqual({
      kind: "blk", label: "blockt Krenko +1", title: "blockt Krenko und 1 weiteren Angreifer",
    });
  });

  it("bleibt ohne aufloesbares Ziel bei einer schlichten Marke", () => {
    const s = snap([{ attacker: 10 }], [card(10, "Krenko")]);
    expect(combatTag(s, 10)).toEqual({ kind: "atk", label: "Angriff", title: "greift an" });
  });

  it("liefert nichts fuer Karten ausserhalb des Kampfes", () => {
    const s = snap([{ attacker: 10, defenderPlayer: 1 }], [card(10, "Krenko"), card(40, "Wald")]);
    expect(combatTag(s, 40)).toBeUndefined();
    expect(combatTag(snap(undefined, []), 10)).toBeUndefined();
  });
});
```

- [ ] **Step 3: Test laufen lassen – er muss scheitern**

```bash
cd /home/kevin/projects/MTG-Player/web && npx vitest run src/combat.test.ts
```

Erwartet: `Failed to resolve import "./combat"`.

- [ ] **Step 4: `web/src/combat.ts` schreiben**

```ts
import type { AttackSnap, CardSnap, Snapshot } from "./protocol";

/** Ein Angreifer mit den Karten, die ihn blocken. */
export interface CombatAttacker { card: CardSnap; blockers: CardSnap[] }

/** Alle Angreifer, die demselben Ziel gelten. key: "p:<id>" (Spieler), "c:<id>" (Karte) oder "none". */
export interface CombatGroup { key: string; label: string; sub?: string; attackers: CombatAttacker[] }

/** Marke an der Karte im Spielfeld: kurzes Label, voller Text im Tooltip. */
export interface CombatTag { kind: "atk" | "blk"; label: string; title: string }

const MAX_NAME = 14;

/** Lange Kartennamen passen nicht in die Marke; der Tooltip traegt immer den vollen Namen. */
function short(name: string): string {
  return name.length > MAX_NAME ? name.slice(0, 13) + "…" : name;
}

const cardOf = (state: Snapshot, id: number | undefined): CardSnap | undefined =>
  id === undefined ? undefined : state.cards[String(id)];

const playerName = (state: Snapshot, id: number | undefined): string | undefined =>
  state.players.find((p) => p.id === id)?.name;

/** Ziel einer Angriffszeile als (key, label, sub) - nicht aufloesbar ergibt die Sammelgruppe "none". */
function target(state: Snapshot, a: AttackSnap): { key: string; label: string; sub?: string } {
  const pn = playerName(state, a.defenderPlayer);
  if (pn !== undefined) return { key: "p:" + a.defenderPlayer, label: pn };
  const c = cardOf(state, a.defenderCard);
  if (c) {
    const owner = playerName(state, c.controller);
    return { key: "c:" + c.id, label: c.name ?? "Karte", sub: owner ? "bei " + owner : undefined };
  }
  return { key: "none", label: "Angriff" };
}

/** Angreifer nach Ziel gruppiert, Reihenfolge nach dem ersten Vorkommen in state.combat.
 *  Karten, die nicht im Snapshot stehen, werden uebersprungen. */
export function combatGroups(state: Snapshot): CombatGroup[] {
  const groups: CombatGroup[] = [];
  const byKey = new Map<string, CombatGroup>();
  for (const a of state.combat ?? []) {
    const card = cardOf(state, a.attacker);
    if (!card) continue;
    const t = target(state, a);
    let g = byKey.get(t.key);
    if (!g) {
      g = { ...t, attackers: [] };
      byKey.set(t.key, g);
      groups.push(g);
    }
    const blockers = (a.blockers ?? []).map((id) => cardOf(state, id)).filter((c): c is CardSnap => !!c);
    g.attackers.push({ card, blockers });
  }
  return groups;
}

/** Marke fuer eine Karte im Spielfeld, oder undefined wenn sie nicht am Kampf beteiligt ist. */
export function combatTag(state: Snapshot, cardId: number): CombatTag | undefined {
  const combat = state.combat ?? [];
  const attack = combat.find((a) => a.attacker === cardId);
  if (attack) {
    const t = target(state, attack);
    if (t.key === "none") return { kind: "atk", label: "Angriff", title: "greift an" };
    return { kind: "atk", label: "→ " + short(t.label), title: "greift " + t.label + " an" };
  }
  const blocked = combat.filter((a) => (a.blockers ?? []).includes(cardId));
  if (blocked.length === 0) return undefined;
  const first = cardOf(state, blocked[0].attacker)?.name;
  if (!first) return { kind: "blk", label: "Block", title: "blockt" };
  const more = blocked.length - 1;
  return {
    kind: "blk",
    label: "blockt " + short(first) + (more > 0 ? " +" + more : ""),
    title: "blockt " + first + (more > 0 ? ` und ${more} weitere${more === 1 ? "n" : ""} Angreifer` : ""),
  };
}
```

- [ ] **Step 5: Test laufen lassen – jetzt grün**

```bash
cd /home/kevin/projects/MTG-Player/web && npx vitest run src/combat.test.ts
```

Erwartet: 12 Tests bestanden.

- [ ] **Step 6: Typen und Gesamtsuite prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

Erwartet: keine Typfehler, alle Suiten grün.

- [ ] **Step 7: Commit**

```bash
cd /home/kevin/projects/MTG-Player && git add web/src/protocol.ts web/src/combat.ts web/src/combat.test.ts
git commit -m "ui: kampfdaten auswerten - gruppierung nach ziel und kartenmarke

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Web – Panel „Kampf" in der Seitenspalte

**Files:**
- Create: `web/src/components/Combat.tsx`
- Modify: `web/src/components/Table.tsx` (in `.side`, zwischen `<CardDetail />` und `<div className="stack">`)
- Modify: `web/src/styles.css` (neue Regeln ans Ende des Abschnitts nach `.stack-thumb`, Zeile ~613)
- Modify: `web/fixtures/table.json`, `web/fixtures/spectator.json`

**Interfaces:**
- Consumes: `combatGroups(state)` aus Task 2; `CardImage`, `useStore().setHover`, `send` aus dem Bestand.
- Produces: Komponente `Combat` (Default-Export, Props `{ state: Snapshot }`); CSS-Klassen `combat-panel`, `combat-group`, `combat-target`, `combat-row`, `combat-card`, `combat-blockers`, `combat-unblocked`.

- [ ] **Step 1: Fixtures um Kampfdaten erweitern**

In `web/fixtures/table.json` (Spieler 1 „Du", 2 „KI 1", 3 „KI 2"; Angreifer 305 „Beast" und 405 „Managorger Hydra" gehören Sitz 3, Blocker 212 „Eternal Witness" gehört Sitz 1) nach dem Feld `"spectator"` – bzw. wenn es fehlt, als letztes Feld des Objekts:

```json
  "combat": [
    { "attacker": 305, "defenderPlayer": 1, "blockers": [212] },
    { "attacker": 405, "defenderPlayer": 1 }
  ]
```

In `web/fixtures/spectator.json` (Angreifer 204 „Managorger Hydra" von Sitz 2, 404 „Beast" von Sitz 4, Blocker 304 „Eternal Witness" von Sitz 3):

```json
  "combat": [
    { "attacker": 204, "defenderPlayer": 3, "blockers": [304] },
    { "attacker": 404, "defenderPlayer": 1 }
  ]
```

Prüfen, dass beide Dateien gültiges JSON bleiben:

```bash
cd /home/kevin/projects/MTG-Player/web && node -e "['fixtures/table.json','fixtures/spectator.json'].forEach(f=>{const d=require('./'+f);console.log(f,d.combat.length)})"
```

Erwartet: je `2`.

- [ ] **Step 2: `web/src/components/Combat.tsx` schreiben**

```tsx
import type { MouseEvent } from "react";
import type { CardSnap, Snapshot } from "../protocol";
import { combatGroups } from "../combat";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

/** Eine Karte in der Kampfliste: Miniatur, Name, P/T. Hover zeigt sie im Detail-Panel, Klick waehlt sie
 *  aus - dieselbe Nachricht wie ein Klick auf dem Brett, damit man waehrend der Blockzuteilung auch aus
 *  der Liste heraus zuteilen kann. */
function CombatCard({ card, kind }: { card: CardSnap; kind: "atk" | "blk" }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={"combat-card " + kind} title={card.text ?? ""} onClick={click} onContextMenu={click}
      onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
      <div className="stack-thumb">
        {card.imageKey ? <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" />
          : <span className="pile-name">{card.name}</span>}
      </div>
      <div className="combat-body">
        <div className="combat-name">{card.name}</div>
        {card.power !== undefined && <div className="muted">{card.power}/{card.toughness}</div>}
      </div>
    </div>
  );
}

/** Panel "Kampf" in der Seitenspalte: wer greift wen an, und wer blockt. Nur sichtbar, solange Angreifer
 *  im Snapshot stehen; Forge raeumt die CombatView am Kampfende ab, dann verschwindet das Panel. */
export default function Combat({ state }: { state: Snapshot }) {
  const groups = combatGroups(state);
  if (groups.length === 0) return null;
  const attackers = groups.reduce((n, g) => n + g.attackers.length, 0);
  return (
    <div className="combat-panel">
      <div className="panel-title">Kampf<span className="count">{attackers}</span></div>
      {groups.map((g) => (
        <div key={g.key} className="combat-group">
          <div className="combat-target">
            {g.key === "none" ? g.label : <>Angriff auf <b>{g.label}</b></>}
            {g.sub && <span className="muted"> · {g.sub}</span>}
          </div>
          {g.attackers.map((a) => (
            <div key={a.card.id} className="combat-row">
              <CombatCard card={a.card} kind="atk" />
              {a.blockers.length === 0
                ? <div className="combat-unblocked">ungeblockt</div>
                : <div className="combat-blockers">
                    {a.blockers.map((b) => <CombatCard key={b.id} card={b} kind="blk" />)}
                  </div>}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 3: Panel in `Table.tsx` einhängen**

Import neben den anderen Komponenten-Importen ergänzen:

```tsx
import Combat from "./Combat";
```

und im `.side`-Block direkt nach `<CardDetail />`:

```tsx
      <div className="side">
        <CardDetail />
        <Combat state={state} />
        <div className="stack">
```

- [ ] **Step 4: Styles ergänzen**

Ans Ende von `web/src/styles.css`:

```css
/* Kampf-Panel (Seitenspalte, ueber dem Stack): Angreifer nach Ziel gruppiert, Blocker eingerueckt.
   Rahmen/Hintergrund kommen wie bei Stack und Log aus ".side > *". */
.combat-panel { flex: 0 1 auto; padding: var(--pad); max-height: 34%; overflow: auto; scrollbar-width: thin; }
.combat-group + .combat-group { margin-top: 10px; }
.combat-target { font-size: 12px; color: var(--muted); margin-bottom: 4px; }
.combat-target b { color: var(--text); }
.combat-row { display: flex; flex-direction: column; gap: 3px; padding: 4px 0; }
.combat-row + .combat-row { border-top: 1px solid var(--line); }
.combat-card { display: flex; align-items: center; gap: 6px; cursor: pointer; font-size: 12px; }
.combat-card .stack-thumb { width: 26px; height: 37px; }
.combat-card.blk .stack-thumb { width: 21px; height: 30px; }
.combat-card .pile-name { display: block; font-size: 8px; line-height: 1.1; padding: 2px; }
.combat-body { min-width: 0; display: flex; align-items: baseline; gap: 6px; }
.combat-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.combat-blockers { display: flex; flex-direction: column; gap: 3px; margin-left: 14px; }
.combat-unblocked { margin-left: 14px; font-size: 10px; font-weight: 600; letter-spacing: 0.04em;
  text-transform: uppercase; color: var(--red); }
```

Ist eine der Variablen `--text`, `--red`, `--pad`, `--line`, `--muted` im `:root`-Block nicht vorhanden, die im Nachbarcode übliche verwenden statt eine neue anzulegen (`grep -n "^:root" -A 20 web/src/styles.css`).

- [ ] **Step 5: Typen und Tests prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

Erwartet: keine Typfehler, alle Suiten grün.

- [ ] **Step 6: Screenshots machen und ansehen**

```bash
cd /home/kevin/projects/MTG-Player/web && (npx vite --port 5199 --strictPort > /tmp/vite-5199.log 2>&1 &) && sleep 4
node scripts/shot.mjs http://localhost:5199 /tmp/kampf-table.png fixtures/table.json
node scripts/shot.mjs http://localhost:5199 /tmp/kampf-spectator.png fixtures/spectator.json
```

Beide PNGs mit dem Read-Tool **ansehen** und prüfen: Panel „Kampf" mit Zähler 2 über dem Stack, Überschrift „Angriff auf Du" bzw. „Angriff auf KI 3", Blocker eingerückt unter seinem Angreifer, „ungeblockt" beim zweiten Angreifer, kein Überlauf und kein abgeschnittener Text. Passt etwas nicht, CSS nachbessern und erneut aufnehmen. Danach den Vite-Prozess über seine PID beenden (`pgrep -f "vite --port 5199"`), **kein** `pkill -f java`, und Ports 8080/8081 nicht anfassen.

- [ ] **Step 7: Commit**

```bash
cd /home/kevin/projects/MTG-Player && git add web/src/components/Combat.tsx web/src/components/Table.tsx web/src/styles.css web/fixtures/table.json web/fixtures/spectator.json
git commit -m "ui: panel kampf zeigt angreifer, ziel und blocker

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Web – Ziel und Blocker an der Karte

**Files:**
- Modify: `web/src/components/CardBox.tsx` (Kampf-Marke, aktuell Zeile ~100)

**Interfaces:**
- Consumes: `combatTag(state, cardId)` aus Task 2.
- Produces: nichts Neues für spätere Tasks.

- [ ] **Step 1: Marke auf `combatTag` umstellen**

In `web/src/components/CardBox.tsx` den Import ergänzen:

```tsx
import { combatTag } from "../combat";
```

und oben in der Komponente, neben den bestehenden `useStore`-Zeilen:

```tsx
  // Kampf-Marke aus den Kampfdaten des Snapshots (combat.ts). Fehlt "combat" (aeltere Bruecke, Fixture
  // ohne Kampfdaten), bleibt es beim bisherigen Text - die Marke verschwindet nie.
  const state = useStore((s) => s.state);
  const tag = state ? combatTag(state, card.id) : undefined;
```

Der bestehende Block

```tsx
        {(card.attacking || card.blocking) && (
          <div className={"tag combat-tag " + (card.attacking ? "atk" : "blk")}>{card.attacking ? "Angriff" : "Block"}</div>
        )}
```

wird zu

```tsx
        {(tag || card.attacking || card.blocking) && (
          <div className={"tag combat-tag " + (tag ? tag.kind : card.attacking ? "atk" : "blk")}
            title={tag ? tag.title : undefined}>
            {tag ? tag.label : card.attacking ? "Angriff" : "Block"}
          </div>
        )}
```

- [ ] **Step 2: Typen und Tests prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && npx tsc --noEmit && npx vitest run
```

Erwartet: keine Typfehler, alle Suiten grün.

- [ ] **Step 3: Screenshot prüfen**

```bash
cd /home/kevin/projects/MTG-Player/web && (npx vite --port 5199 --strictPort > /tmp/vite-5199.log 2>&1 &) && sleep 4
node scripts/shot.mjs http://localhost:5199 /tmp/kampf-marken.png fixtures/table.json
```

PNG mit dem Read-Tool ansehen: Die angreifenden Kreaturen von KI 2 tragen „→ Du", Eternal Witness trägt „blockt Beast" (oder „blockt Managorger…", je nach Fixture-Zuordnung), die Marke bleibt einzeilig und überdeckt die Karte nicht. Danach den Vite-Prozess über seine PID beenden.

- [ ] **Step 4: Commit**

```bash
cd /home/kevin/projects/MTG-Player && git add web/src/components/CardBox.tsx
git commit -m "ui: kampfmarke nennt ziel und geblockten angreifer

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Abschluss

- `web/src/styles.css` wächst um einen Abschnitt; die Datei ist gewachsen, aber ein Aufteilen gehört nicht in diese Runde.
- Nach Task 4: Ledger-Zeile in `.superpowers/sdd/progress.md` anhängen, dann `git push`.
- Kevin braucht für den Bridge-Teil einen Neustart seiner Bridge; das UI zieht er über den Vite-Build.
