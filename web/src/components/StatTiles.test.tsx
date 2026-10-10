// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { summarize, type Format } from "../matchStats";
import type { MatchRecord, MatchSeat } from "../protocol";
import StatBlocks from "./StatTiles";

// "Ø Platz" und "Ø Ausscheide-Zug" gibt es nur dort, wo man vor dem Partieende ausscheiden kann. Die
// Grenze zwischen beiden verlaeuft im Team: Der Platz zaehlt jeden, der dich ueberlebt hat - auch den
// Partner. Stirbst du in Zug 6 und dein Partner gewinnt in Zug 12, waere das Platz 2 neben einem Sieg in
// der Siegquote. Das widerspraeche sich auf demselben Bildschirm, darum zeigt das Team keinen Platz.
// Der Ausscheide-Zug bleibt eine blosse Tatsache ("du bist in Zug 6 ausgeschieden") und steht weiter da.

/** Nur die Felder, die summarize/StatBlocks fuer diese Kacheln brauchen; der Rest ist 0. */
function seat(over: Partial<MatchSeat>): MatchSeat {
  return {
    name: "S", deck: "Testdeck", human: false, winner: false, mulligans: 0, lands: 5, landsByTurn: [0, 1, 2, 3, 4, 5],
    missedLandDrops: 0, spells: 5, spellMana: 10, commanderCasts: 1, commanderTax: 0, damageDealt: 0, damageTaken: 0,
    combatDamageTaken: 0, lifeEnd: 0, poisonEnd: 0, spellsCountered: 0, spellsFizzled: 0, counterspellsCast: 0,
    removalCast: 0, cardsDrawn: 0, cardsDiscarded: 0, cardsMilled: 0, ...over,
  } as MatchSeat;
}

// Du stirbst in Zug 6, dein Partner gewinnt in Zug 12 (beide Team 1) gegen zwei Gegner (Team 2).
const teamMatch = (id: string): MatchRecord => ({
  v: 2, id, startedAt: "s", endedAt: "e", durationMs: 600000, source: "live", turns: 12,
  reason: "AllOpponentsLost", draw: false, counted: true,
  seats: [
    seat({ name: "Du", human: true, team: 1, eliminatedTurn: 6, lossReason: "LifeReachedZero" }),
    seat({ name: "Partner", deck: "Partner-Deck", team: 1, winner: true }),
    seat({ name: "A", deck: "Gegner A", team: 2, eliminatedTurn: 12 }),
    seat({ name: "B", deck: "Gegner B", team: 2, eliminatedTurn: 12 }),
  ],
});
const podMatch = (id: string): MatchRecord => ({
  ...teamMatch(id), seats: teamMatch(id).seats.map(({ team: _team, ...rest }) => rest),
});

function renderTiles(format: Format, records: MatchRecord[]) {
  render(<StatBlocks s={summarize(records, "Testdeck", format)!} format={format} explain={false} />);
}

describe("StatBlocks: Platz und Ausscheide-Zug je Format", () => {
  afterEach(() => cleanup());

  it("im Team gibt es keinen Ø Platz - der Partner zaehlt dort als Mitspieler, der dich ueberlebt hat", () => {
    renderTiles("team", [teamMatch("t-1")]);
    expect(screen.queryByText("Ø Platz")).not.toBeInTheDocument();
  });

  it("im Team bleibt der Ø Ausscheide-Zug (eine Tatsache, keine Wertung)", () => {
    renderTiles("team", [teamMatch("t-1")]);
    expect(screen.getByText("Ø Ausscheide-Zug")).toBeInTheDocument();
  });

  it("im Pod stehen beide Kacheln wie bisher", () => {
    renderTiles("pod", [podMatch("p-1")]);
    expect(screen.getByText("Ø Platz")).toBeInTheDocument();
    expect(screen.getByText("Ø Ausscheide-Zug")).toBeInTheDocument();
  });

  it("im Duell und bei Alle stehen beide nicht", () => {
    renderTiles("all", [podMatch("p-1")]);
    expect(screen.queryByText("Ø Platz")).not.toBeInTheDocument();
    expect(screen.queryByText("Ø Ausscheide-Zug")).not.toBeInTheDocument();
  });
});
