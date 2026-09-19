import { describe, expect, it } from "vitest";
import { buildStartGame, DEFAULT_AI } from "./lobbyPayload";

const precon = (name: string) => ({ precon: name });

describe("buildStartGame", () => {
  it("menschliches Spiel: humanDeck + KIs aufgelöst -> Nachricht", () => {
    const msg = buildStartGame(false, precon("Abzan Armor [TDC] [2025]"), [precon("Adaptive Enchantment [C18] [2018]")], [DEFAULT_AI], 5);
    expect(msg).toEqual({
      type: "startGame",
      humanDeck: { precon: "Abzan Armor [TDC] [2025]" },
      opponents: [{ precon: "Adaptive Enchantment [C18] [2018]", name: "KI 1" }],
    });
  });

  it("menschliches Spiel: fehlendes eigenes Deck -> undefined", () => {
    expect(buildStartGame(false, undefined, [precon("Adaptive Enchantment [C18] [2018]")], [DEFAULT_AI], 5)).toBeUndefined();
  });

  it("Zuschauer-Modus: 2 KIs aufgelöst -> Nachricht ohne humanDeck-Schlüssel", () => {
    const msg = buildStartGame(
      true,
      undefined,
      [precon("Abzan Armor [TDC] [2025]"), precon("Adaptive Enchantment [C18] [2018]")],
      [DEFAULT_AI, DEFAULT_AI],
      5,
    );
    expect(msg).toEqual({
      type: "startGame",
      spectate: true,
      opponents: [
        { precon: "Abzan Armor [TDC] [2025]", name: "KI 1" },
        { precon: "Adaptive Enchantment [C18] [2018]", name: "KI 2" },
      ],
    });
    expect(msg && "humanDeck" in msg).toBe(false);
  });

  it("Zuschauer-Modus: nur 1 KI -> undefined", () => {
    expect(buildStartGame(true, undefined, [precon("Abzan Armor [TDC] [2025]")], [DEFAULT_AI], 5)).toBeUndefined();
  });

  it("Standard-Picks + Timeout 5 -> Payload ohne ai/aiTimeout (bestehende Erwartung unveraendert)", () => {
    const msg = buildStartGame(
      false,
      precon("Abzan Armor [TDC] [2025]"),
      [precon("Adaptive Enchantment [C18] [2018]")],
      [{ mode: "standard", profile: "Default" }],
      5,
    );
    expect(msg).toEqual({
      type: "startGame",
      humanDeck: { precon: "Abzan Armor [TDC] [2025]" },
      opponents: [{ precon: "Adaptive Enchantment [C18] [2018]", name: "KI 1" }],
    });
  });

  it("abweichende KI-Werte + Bedenkzeit -> ai je Slot und aiTimeout im Payload", () => {
    const msg = buildStartGame(
      true,
      undefined,
      [precon("Abzan Armor [TDC] [2025]"), precon("Adaptive Enchantment [C18] [2018]")],
      [{ mode: "sim", profile: "Reckless" }, DEFAULT_AI],
      10,
    );
    expect(msg).toEqual({
      type: "startGame",
      spectate: true,
      opponents: [
        { precon: "Abzan Armor [TDC] [2025]", name: "KI 1", ai: { mode: "sim", profile: "Reckless" } },
        { precon: "Adaptive Enchantment [C18] [2018]", name: "KI 2" },
      ],
      aiTimeout: 10,
    });
  });
});
