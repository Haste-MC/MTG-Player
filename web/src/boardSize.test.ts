import { describe, expect, it } from "vitest";
import { fitCardWidth, slotHeadroom, slotUnits } from "./boardSize";
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
    // 4 nebeneinander: (4w + 18) <= 600 -> w <= 145. Zwei Zeilen: Hoehe 2*1.4w + 4 <= 500 -> w <= 177,
    // und bei diesem w packt greedy nicht 2+2, sondern 3+1 (Zeile 1 nimmt Karten, bis die naechste nicht
    // mehr passt) - fuer die Hoehenrechnung zaehlt nur die Zeilenzahl (2), nicht die Aufteilung der Karten
    // darauf. Breite 2w + 6 <= 600 -> w <= 297 (fuer die erste, volle Zeile allemal genug). Also passt 177,
    // nicht nur 145.
    const w = fitCardWidth(600, 500, [row(4)]);
    expect(w).toBeGreaterThan(160);
    expect(w).toBeLessThanOrEqual(180);
  });

  it("zeilenzahl bindend: 5 karten, breite fuer genau 2 pro zeile bei w=60 -> 3 zeilen (greedy 2+2+1)", () => {
    // width = 2*60 + 6 = 126: bei w=60 passen genau 2 Karten pro Zeile (60 + 6 + 60 = 126), 5 Karten also
    // 3 Zeilen. Hoehe fuer genau 3 Zeilen bei w=60: 3 * 1.4*60 + 2*6 = 3*84 + 12 = 264. Bei w=61 passt das
    // zweite Paar nicht mehr (61 + 6 + 61 = 128 > 126) - greedy packt dann nur noch 1 Karte pro Zeile, 5
    // Zeilen brauchen deutlich mehr als 264px Hoehe. fitCardWidth muss also bei 60 bleiben, nicht bei 61.
    const w = fitCardWidth(126, 264, [row(5)]);
    expect(w).toBe(60);
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

  it("ein slot allein breiter als der container: groesste breite <= container", () => {
    // Hoehe ist hier grosszuegig (1000px) - allein die Breite muss den einzelnen Slot begrenzen.
    // fitCardWidth(100, 1000, [row(1)]): groesste Breite <= 100 ist 100 selbst.
    expect(fitCardWidth(100, 1000, [row(1)])).toBe(100);
  });

  it("ein slot allein breiter als der container: faellt auf min zurueck, wenn selbst min zu breit ist", () => {
    // fitCardWidth(30, 1000, [row(1)]): selbst die Untergrenze (50) ist breiter als der Container (30) ->
    // fits(min) scheitert sofort, Rueckfall auf min.
    expect(fitCardWidth(30, 1000, [row(1)])).toBe(50);
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

describe("headroom", () => {
  it("slotHeadroom: 0.22 * 1.4 je anhang, gedeckelt bei 4; getappter wirt zusaetzlich 0.4", () => {
    expect(slotHeadroom(0)).toBe(0);
    expect(slotHeadroom(0, true)).toBe(0);
    expect(slotHeadroom(1)).toBeCloseTo(0.308);
    expect(slotHeadroom(6)).toBeCloseTo(4 * 0.308);
    expect(slotHeadroom(1, true)).toBeCloseTo(0.708);
  });
  it("eine reihe mit headroom braucht bei gleicher hoehe eine kleinere kartenbreite", () => {
    const ohne = fitCardWidth(600, 200, [{ units: [1, 1], scale: 1 }]);
    const mit = fitCardWidth(600, 200, [{ units: [1, 1], scale: 1, headroom: slotHeadroom(2) }]);
    expect(mit).toBeLessThan(ohne);
    // 200 = 1.4 * w * (1 + 0.616) -> w = 88
    expect(mit).toBe(88);
  });
});
