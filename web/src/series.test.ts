import { describe, expect, it } from "vitest";
import { formatSeries, recordResult, seriesKey, seriesWinner, startSeries } from "./series";
import type { StartGame } from "./protocol";

const msg: StartGame = { type: "startGame", humanDeck: { precon: "A" }, opponents: [{ precon: "B", name: "KI 1" }] };

describe("series", () => {
  it("key ignoriert spielernamen", () => {
    const other: StartGame = { ...msg, opponents: [{ precon: "B", name: "Gegner" }] };
    expect(seriesKey(msg)).toBe(seriesKey(other));
    expect(seriesKey(msg)).not.toBe(seriesKey({ ...msg, opponents: [{ precon: "C", name: "KI 1" }] }));
  });
  it("startSeries behaelt eine serie mit gleichem key und setzt sonst zurueck", () => {
    const s = recordResult(startSeries(undefined, msg), "Du");
    expect(startSeries(s, msg)).toBe(s);
    expect(startSeries(s, { ...msg, humanDeck: { precon: "X" } })).toEqual({ key: seriesKey({ ...msg, humanDeck: { precon: "X" } }), wins: {}, games: 0 });
  });
  it("recordResult zaehlt siege und spiele", () => {
    let s = startSeries(undefined, msg);
    s = recordResult(s, "Du"); s = recordResult(s, "KI 1"); s = recordResult(s, "Du"); s = recordResult(s, null);
    expect(s.wins).toEqual({ Du: 2, "KI 1": 1 });
    expect(s.games).toBe(4);
  });
  it("seriesWinner ab ceil(n/2) siegen, bei bestOf 0 nie", () => {
    const s = { key: "k", wins: { Du: 2, "KI 1": 1 }, games: 3 };
    expect(seriesWinner(s, 3)).toBe("Du");
    expect(seriesWinner(s, 5)).toBeUndefined();
    expect(seriesWinner(s, 0)).toBeUndefined();
  });
  it("formatSeries in namensreihenfolge mit nullen", () => {
    expect(formatSeries({ key: "k", wins: { "KI 1": 1 }, games: 1 }, ["Du", "KI 1"])).toBe("Du 0 · KI 1 1");
  });
});
