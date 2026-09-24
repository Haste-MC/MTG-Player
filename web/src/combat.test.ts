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
