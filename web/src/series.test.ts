import { describe, expect, it } from "vitest";
import { formatSeries, nextSeriesStep, recordResult, seriesKey, seriesWinner, shouldArmCountdown, startSeries } from "./series";
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

  describe("nextSeriesStep", () => {
    it("none ohne serie oder bei bestOf 0", () => {
      const s = recordResult(startSeries(undefined, msg), "Du");
      expect(nextSeriesStep(undefined, 3, true)).toEqual({ kind: "none" });
      expect(nextSeriesStep(s, 0, true)).toEqual({ kind: "none" });
    });
    it("none wenn die letzte partie nicht gewertet wurde (abgebrochen)", () => {
      const s = recordResult(startSeries(undefined, msg), "Du");
      expect(nextSeriesStep(s, 3, false)).toEqual({ kind: "none" });
    });
    it("countdown bei laufender, offener serie mit gewerteter letzter partie", () => {
      const s = recordResult(startSeries(undefined, msg), "Du");
      expect(nextSeriesStep(s, 3, true)).toEqual({ kind: "countdown" });
    });
    it("decided sobald jemand ceil(bestOf/2) siege hat - auch wenn die letzte partie nicht gewertet wurde", () => {
      let s = startSeries(undefined, msg);
      s = recordResult(s, "Du");
      s = recordResult(s, "Du");
      expect(nextSeriesStep(s, 3, true)).toEqual({ kind: "decided", winner: "Du" });
      expect(nextSeriesStep(s, 3, false)).toEqual({ kind: "decided", winner: "Du" });
    });
  });

  describe("shouldArmCountdown", () => {
    const countdown = { kind: "countdown" as const };
    const base = { dialogOpen: true, expectNewMatch: false, autoStarted: false, seriesCountdown: undefined };

    it("ja bei offenem dialog, countdown-schritt, ohne laufenden versuch oder countdown", () => {
      expect(shouldArmCountdown(countdown, base)).toBe(true);
    });
    it("nein ohne offenen dialog", () => {
      expect(shouldArmCountdown(countdown, { ...base, dialogOpen: false })).toBe(false);
    });
    it("nein bei kind decided/none", () => {
      expect(shouldArmCountdown({ kind: "decided", winner: "Du" }, base)).toBe(false);
      expect(shouldArmCountdown({ kind: "none" }, base)).toBe(false);
    });
    it("nein waehrend ein start schon unterwegs ist (expectNewMatch)", () => {
      expect(shouldArmCountdown(countdown, { ...base, expectNewMatch: true })).toBe(false);
    });
    it("nein wenn schon ein countdown laeuft", () => {
      expect(shouldArmCountdown(countdown, { ...base, seriesCountdown: 3 })).toBe(false);
    });
    it("nein nach einem automatischen versuch - auch wenn expectNewMatch durch einen error wieder false wurde", () => {
      // Das ist der Review-Fall: die Bridge lehnt den automatischen Start mit "error" ab, expectNewMatch
      // faellt zurueck auf false - ohne autoStarted wuerde das einen zweiten Countdown anstossen und der
      // fehlgeschlagene Start wiederholte sich endlos.
      expect(shouldArmCountdown(countdown, { ...base, autoStarted: true })).toBe(false);
      expect(shouldArmCountdown(countdown, { ...base, autoStarted: true, expectNewMatch: false })).toBe(false);
    });
  });
});
