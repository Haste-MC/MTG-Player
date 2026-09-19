import { describe, expect, it } from "vitest";
import { groupCards } from "./groups";
import type { CardSnap } from "./protocol";

const forest = (id: number, over: Partial<CardSnap> = {}): CardSnap =>
  ({ id, faceDown: false, name: "Forest", typeLine: "Basic Land — Forest", ...over });
const swamp = (id: number): CardSnap => ({ id, faceDown: false, name: "Swamp", typeLine: "Basic Land — Swamp" });

describe("groupCards", () => {
  it("stapelt gleichnamige karten ohne auszeichnung und zaehlt die getappten", () => {
    const g = groupCards([forest(1), forest(2, { tapped: true }), swamp(3), forest(4, { tapped: true })]);
    expect(g.map((x) => x.cards.map((c) => c.id))).toEqual([[1, 2, 4], [3]]);
    expect(g[0].tapped).toBe(2);
    expect(g[1].tapped).toBe(0);
  });

  it("loest den stapel auf, sobald eine karte des namens waehlbar ist", () => {
    const g = groupCards([forest(1), forest(2, { selectable: true }), forest(3)]);
    expect(g.map((x) => x.cards.map((c) => c.id))).toEqual([[1], [2], [3]]);
  });

  it("karten mit marken, anhaengen oder schaden bleiben einzeln", () => {
    const g = groupCards([
      forest(1, { counters: { P1P1: 1 } }),
      swamp(2), swamp(3),
      { id: 4, faceDown: false, name: "Bear", power: 2, toughness: 2, damage: 1 },
      { id: 5, faceDown: false, name: "Bear", power: 2, toughness: 2, damage: 1 },
      { id: 6, faceDown: false, name: "Sword", attachedTo: 4 },
      { id: 7, faceDown: false, name: "Sword", attachedTo: 5 },
    ]);
    expect(g.map((x) => x.cards.map((c) => c.id))).toEqual([[1], [2, 3], [4], [5], [6], [7]]);
  });

  it("reihenfolge bleibt stabil: jeder stapel steht beim ersten auftreten", () => {
    const g = groupCards([swamp(1), forest(2), swamp(3), forest(4), { id: 5, faceDown: false, name: "Sol Ring" }]);
    expect(g.map((x) => x.card.name)).toEqual(["Swamp", "Forest", "Sol Ring"]);
    expect(g.map((x) => x.cards.length)).toEqual([2, 2, 1]);
  });

  it("klickziel ist die erste ungetappte karte (bevorzugt actionable), sonst die erste", () => {
    const g1 = groupCards([forest(1, { tapped: true }), forest(2, { tapped: true }), forest(3), forest(4)]);
    expect(g1[0].card.id).toBe(3);
    // eine ungetappte mit spielbarer Faehigkeit geht vor - z. B. Elf mit Beschwoerungskrankheit neben einem ohne
    const g3 = groupCards([forest(1), forest(2, { actionable: true })]);
    expect(g3[0].card.id).toBe(2);
    expect(g3[0].cards.length).toBe(2);
    const g2 = groupCards([forest(1, { tapped: true }), forest(2, { tapped: true })]);
    expect(g2[0].card.id).toBe(1);
    expect(g2[0].tapped).toBe(2);
  });
});
