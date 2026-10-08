import { describe, expect, it } from "vitest";
import { ABSTAND, KARTE_RATIO, vorschauGroesse, vorschauPosition, WUNSCH_BREITE } from "./cardPreview";

const fenster = { breite: 1600, hoehe: 900 };

describe("vorschauGroesse", () => {
  it("hohes Fenster: die Wunschbreite", () => {
    expect(vorschauGroesse(1200).breite).toBe(WUNSCH_BREITE);
  });

  it("flaches Fenster: so hoch wie es passt, nicht hoeher", () => {
    const g = vorschauGroesse(400);
    expect(g.breite).toBeLessThan(WUNSCH_BREITE);
    expect(g.hoehe).toBeLessThanOrEqual(400);
  });

  it("Seitenverhaeltnis bleibt das einer Karte", () => {
    const g = vorschauGroesse(900);
    expect(g.hoehe / g.breite).toBeCloseTo(KARTE_RATIO, 5);
  });
});

describe("vorschauPosition", () => {
  const vorschau = vorschauGroesse(fenster.hoehe);

  it("rechts neben dem Zeiger, wenn dort Platz ist", () => {
    const { left } = vorschauPosition({ x: 300, y: 450 }, fenster, vorschau);
    expect(left).toBe(300 + ABSTAND);
  });

  it("am rechten Rand klappt sie auf die andere Seite", () => {
    const { left } = vorschauPosition({ x: 1550, y: 450 }, fenster, vorschau);
    expect(left).toBe(1550 - ABSTAND - vorschau.breite);
    expect(left).toBeGreaterThanOrEqual(0);
  });

  it("bleibt oben und unten im Bild", () => {
    const oben = vorschauPosition({ x: 300, y: 5 }, fenster, vorschau);
    expect(oben.top).toBeGreaterThanOrEqual(0);
    const unten = vorschauPosition({ x: 300, y: 895 }, fenster, vorschau);
    expect(unten.top + vorschau.hoehe).toBeLessThanOrEqual(fenster.hoehe);
  });

  it("passt in kein Fenster: lieber am Rand kleben als halb draussen", () => {
    const schmal = { breite: 300, hoehe: 900 };
    const { left } = vorschauPosition({ x: 150, y: 450 }, schmal, vorschau);
    expect(left).toBeGreaterThanOrEqual(0);
  });
});
