import { describe, expect, it } from "vitest";
import { amountsValid, cardlistDirections, isPermutation, remaining } from "./dialogs";

describe("isPermutation", () => {
  it("akzeptiert nur vollstaendige permutationen", () => {
    expect(isPermutation([2, 0, 1], 3)).toBe(true);
    expect(isPermutation([0, 0, 1], 3)).toBe(false);
    expect(isPermutation([0, 1], 3)).toBe(false);
    expect(isPermutation([0, 1, 3], 3)).toBe(false);
  });
});

describe("amountsValid", () => {
  it("summe muss stimmen", () => {
    expect(amountsValid([1, 2], 3, [undefined, undefined], false)).toBe(true);
    expect(amountsValid([1, 1], 3, [undefined, undefined], false)).toBe(false);
  });
  it("max je eintrag und atLeastOne", () => {
    expect(amountsValid([3, 0], 3, [2, 5], false)).toBe(false);
    expect(amountsValid([3, 0], 3, [undefined, undefined], true)).toBe(false);
    expect(amountsValid([2, 1], 3, [2, 5], true)).toBe(true);
  });
  it("negatives ist ungueltig", () => {
    expect(amountsValid([4, -1], 3, [undefined, undefined], false)).toBe(false);
  });
});

describe("remaining", () => {
  it("rest bis zur summe", () => {
    expect(remaining([1, 1], 5)).toBe(3);
  });
});

describe("cardlistDirections", () => {
  it("ohne flags (alte bridge) ist alles erlaubt", () => {
    expect(cardlistDirections(undefined)).toEqual({ top: true, bottom: true, anywhere: true });
  });
  it("nur top erlaubt nur oben", () => {
    expect(cardlistDirections(["top"])).toEqual({ top: true, bottom: false, anywhere: false });
  });
  it("anywhere erlaubt alles", () => {
    expect(cardlistDirections(["anywhere"])).toEqual({ top: true, bottom: true, anywhere: true });
  });
});
