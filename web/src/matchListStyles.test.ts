import { describe, expect, it } from "vitest";
import css from "./styles.css?raw";

// Die Partienliste des Statistik-Boards ist ein festes Spaltenraster. Ob eine Zeile passt, entscheidet am
// Ende der Browser (Screenshot-Pruefung, scripts/shot-stats.mjs); diese Tests halten nur die Zusagen fest,
// die sich im Stylesheet nachlesen lassen und leicht unbemerkt wieder wegfallen.

/** Der Rumpf der Regel `selector { ... }` - genau diese Zeile, ohne Varianten wie ".row.head". */
function rule(selector: string): string {
  const line = css.split("\n").find((l: string) => l.startsWith(selector + " {"));
  if (!line) throw new Error("keine Regel " + selector);
  return line.slice(line.indexOf("{") + 1, line.lastIndexOf("}"));
}

describe("Spalte Quelle der Partienliste", () => {
  it("ist breit genug fuer Zuschauer + Bedenkzeit + Aufstellung (gemessen rund 155 px, bei 2v2v2 mehr)", () => {
    const columns = /grid-template-columns:\s*([^;]+);/.exec(rule(".match-row"))![1].trim().split(/\s+/);
    // Spalten: Pfeil, Datum, Quelle, ...
    expect(parseInt(columns[2], 10)).toBeGreaterThanOrEqual(160);
  });

  it("bricht um, statt dass ein Chip in die Deckspalte hineinragt", () => {
    expect(rule(".match-source")).toMatch(/flex-wrap:\s*wrap/);
  });
});

describe("Farben der Aufstellungs-Marke", () => {
  it("haengt an den Teamfarben des Tischs, nicht an einer eigenen Kopie des Farbwerts", () => {
    const chip = rule(".chip.match-lineup");
    expect(chip).toMatch(/var\(--team-1-rgb\)/);
    expect(chip).not.toMatch(/\d+,\s*\d+,\s*\d+/);
  });

  it("die Teamfarben stehen genau einmal als Token, Tisch und Statistik lesen dasselbe", () => {
    for (const n of [1, 2, 3]) {
      expect(css.match(new RegExp(`--team-${n}-rgb:`, "g"))).toHaveLength(1);
    }
    expect(rule(".player .team-tag.team-1")).toMatch(/var\(--team-1-rgb\)/);
    expect(rule(".player.team-edge-1")).toMatch(/var\(--team-1-rgb\)/);
  });
});
