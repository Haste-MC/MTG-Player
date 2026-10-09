import { describe, expect, it } from "vitest";
import { fitTeams, humanHasPartner, lineupProblem, lineupText, lineupValid, seatTeams, splitSeats, twoVsTwo, withoutSeat } from "./lineup";

describe("lineup", () => {
  it("ohne Teams gueltig", () => {
    expect(lineupValid([-1, -1, -1])).toBe(true);
    expect(lineupValid([])).toBe(true);
  });

  it("halb gesetzt ist ungueltig, weil ein Sitz ohne Team in der Bridge keine Gegner haette", () => {
    expect(lineupValid([1, -1, 2])).toBe(false);
    expect(lineupProblem([1, -1, 2])).toMatch(/alle Sitze oder keiner/);
  });

  it("ein einziges Team ist ungueltig, weil niemand Gegner waere", () => {
    expect(lineupValid([1, 1, 1])).toBe(false);
    expect(lineupProblem([1, 1, 1])).toMatch(/zwei verschiedene/);
  });

  it("zwei Teams sind gueltig", () => {
    expect(lineupValid([1, 1, 2, 2])).toBe(true);
    expect(lineupProblem([1, 1, 2, 2])).toBeUndefined();
  });

  it("3 gegen 1 ist gueltig, 1 gegen 2 ebenfalls", () => {
    expect(lineupValid([1, 1, 1, 2])).toBe(true);
    expect(lineupValid([1, 2, 2])).toBe(true);
  });

  it("jeder in einem eigenen Team ist ungueltig: ein Team braucht zwei Sitze", () => {
    expect(lineupValid([1, 2, 3])).toBe(false);
    expect(lineupProblem([1, 2, 3])).toMatch(/zwei Sitze/);
  });

  it("zu zweit ist Team 1 gegen Team 2 ein gewoehnliches Duell, das die Bridge ablehnt", () => {
    expect(lineupValid([1, 2])).toBe(false);
    expect(lineupProblem([1, 2])).toMatch(/zwei Sitze/);
  });

  it("Nummern ausserhalb 1 bis 6 (ausser -1) lehnt die Bridge ab, also auch die Lobby", () => {
    expect(lineupValid([0, 0, 1, 1])).toBe(false);
    expect(lineupValid([1, 1, 7, 7])).toBe(false);
    expect(lineupValid([1, 1, 6, 6])).toBe(true);
  });

  it("schreibt die Aufstellung aus", () => {
    expect(lineupText(["You", "AI 1", "AI 2", "AI 3"], [1, 1, 2, 2]))
      .toBe("Team 1: You + AI 1 — Team 2: AI 2 + AI 3");
    expect(lineupText(["You", "AI 1"], [-1, -1])).toBe("");
  });

  it("halb gesetzte Aufstellung nennt nur die gesetzten Sitze", () => {
    expect(lineupText(["You", "AI 1", "AI 2"], [2, -1, 2])).toBe("Team 2: You + AI 2");
  });

  it("2v2 nur bei genau vier Sitzen", () => {
    expect(twoVsTwo(4)).toEqual([1, 1, 2, 2]);
    expect(twoVsTwo(3)).toBeUndefined();
    expect(twoVsTwo(6)).toBeUndefined();
  });
});

describe("humanHasPartner", () => {
  it("Partner nur, wenn ein anderer Sitz die Teamnummer des Menschen teilt", () => {
    expect(humanHasPartner([1, 1, 2, 2])).toBe(true);
    expect(humanHasPartner([2, 1, 2, 2])).toBe(true);
    expect(humanHasPartner([1, 2, 2])).toBe(false); // allein in Team 1
    expect(humanHasPartner([-1, 1, 1])).toBe(false); // der Mensch hat kein Team, die KIs schon
    expect(humanHasPartner([])).toBe(false);
  });
});

describe("Sitzliste und Teams im Gleichschritt", () => {
  it("der menschliche Sitz steht vorn, im Zuschauer-Modus fehlt er", () => {
    expect(seatTeams(false, 1, [1, 2])).toEqual([1, 1, 2]);
    expect(seatTeams(true, 1, [1, 2])).toEqual([1, 2]);
  });

  it("ein neuer Sitz beginnt ohne Team", () => {
    expect(fitTeams([1, 2], 3)).toEqual([1, 2, -1]);
  });

  it("ein entfernter Sitz nimmt seine Zuordnung mit, die uebrigen ruecken auf", () => {
    expect(withoutSeat([1, 2, 2], 0)).toEqual([2, 2]);
    expect(withoutSeat([1, 2, 2], 2)).toEqual([1, 2]);
  });

  it("zu viele gespeicherte Eintraege werden gekappt, damit kein Team fuer einen fehlenden Sitz bleibt", () => {
    expect(fitTeams([1, 1, 2, 2], 2)).toEqual([1, 1]);
  });

  it("Zuschauer-Modus an und aus: der Mensch behaelt sein Team, die KI-Sitze auch", () => {
    const human = 1;
    const ais = [1, 2, 2];
    // Zuschauen: der Mensch zaehlt nicht, die drei KI-Sitze bilden 1/2/2 - gueltig.
    expect(lineupValid(seatTeams(true, human, ais))).toBe(true);
    // Zurueck: der Mensch ist wieder dabei, nichts ging verloren.
    expect(seatTeams(false, human, ais)).toEqual([1, 1, 2, 2]);
  });

  it("Schnellwahl 2v2 setzt im Zuschauer-Modus nur die KI-Sitze und laesst den Menschen unberuehrt", () => {
    expect(splitSeats(true, [1, 1, 2, 2], 2)).toEqual({ humanTeam: 2, aiTeams: [1, 1, 2, 2] });
    expect(splitSeats(false, [1, 1, 2, 2], -1)).toEqual({ humanTeam: 1, aiTeams: [1, 2, 2] });
  });
});
