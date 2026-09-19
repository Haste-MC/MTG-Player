import { describe, expect, it } from "vitest";
import { fitCardWidth, slotUnits } from "./boardSize";
import type { CardSnap } from "./protocol";

const row = (n: number, scale = 1, units?: number[]): { units: number[]; scale: number } =>
  ({ units: units ?? Array(n).fill(1), scale });
const snap = (id: number, over: Partial<CardSnap> = {}): CardSnap => ({ id, faceDown: false, name: "Forest", ...over });

describe("fitCardWidth", () => {
  it("leere reihen -> maximum", () => {
    expect(fitCardWidth(600, 500, [])).toBe(180);
    expect(fitCardWidth(600, 500, [row(0)])).toBe(180);
  });

  it("eine reihe mit 4 karten in 600x500: umbruch in zwei zeilen statt vier winzige nebeneinander", () => {
    // 4 nebeneinander: (4w + 18) <= 600 -> w <= 145. Zwei Zeilen a 2: Hoehe 2*1.4w + 4 <= 500 -> w <= 177,
    // Breite 2w + 6 <= 600 -> w <= 297. Also passt 177, nicht nur 145.
    const w = fitCardWidth(600, 500, [row(4)]);
    expect(w).toBeGreaterThan(160);
    expect(w).toBeLessThanOrEqual(180);
  });

  it("getappte karten (1.4 einheiten) verbreitern die reihe", () => {
    const upright = fitCardWidth(600, 200, [row(4)]);
    const tapped = fitCardWidth(600, 200, [row(4, 1, [1.4, 1.4, 1.4, 1.4])]);
    expect(tapped).toBeLessThan(upright);
  });

  it("laender-faktor: eine laenderreihe (0.8) laesst breitere karten zu als eine kartenreihe", () => {
    const lands = fitCardWidth(600, 200, [row(4, 0.8)]);
    const cards = fitCardWidth(600, 200, [row(4)]);
    expect(lands).toBeGreaterThan(cards);
  });

  it("nie unter min oder ueber max", () => {
    expect(fitCardWidth(100, 60, [row(10)])).toBe(50);
    expect(fitCardWidth(5000, 5000, [row(1)])).toBe(180);
    expect(fitCardWidth(5000, 5000, [row(1)], { max: 132 })).toBe(132);
  });

  it("hoehe ist bindend: drei reihen muessen uebereinander passen", () => {
    // 3 Reihen je 1 Karte, Hoehe 300: 1.4w*(1+1+0.8) + 2*4 <= 300 -> w <= 74.
    const w = fitCardWidth(1000, 300, [row(1), row(1), row(1, 0.8)]);
    expect(w).toBeGreaterThanOrEqual(72);
    expect(w).toBeLessThanOrEqual(75);
  });
});

describe("slotUnits", () => {
  it("einzelkarte: 1, getappt 1.4", () => {
    expect(slotUnits({ card: snap(1), cards: [snap(1)], tapped: 0 })).toBe(1);
    expect(slotUnits({ card: snap(1, { tapped: true }), cards: [snap(1, { tapped: true })], tapped: 1 })).toBe(1.4);
  });
  it("stapel: mit getappten 1.4, sonst 1 + 0.04 je ebene (max 4)", () => {
    const cards = [snap(1), snap(2), snap(3)];
    expect(slotUnits({ card: cards[0], cards, tapped: 0 })).toBeCloseTo(1.08);
    expect(slotUnits({ card: cards[0], cards, tapped: 1 })).toBe(1.4);
    const six = [1, 2, 3, 4, 5, 6].map((i) => snap(i));
    expect(slotUnits({ card: six[0], cards: six, tapped: 0 })).toBeCloseTo(1.16);
  });
});
