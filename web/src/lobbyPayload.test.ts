import { describe, expect, it } from "vitest";
import { buildStartGame, DEFAULT_AI } from "./lobbyPayload";

const precon = (name: string) => ({ precon: name });

describe("buildStartGame", () => {
  it("menschliches Spiel: humanDeck + KIs aufgelöst -> Nachricht", () => {
    const msg = buildStartGame(false, precon("Abzan Armor [TDC] [2025]"), [precon("Adaptive Enchantment [C18] [2018]")], [DEFAULT_AI], 5);
    expect(msg).toEqual({
      type: "startGame",
      humanDeck: { precon: "Abzan Armor [TDC] [2025]" },
      opponents: [{ precon: "Adaptive Enchantment [C18] [2018]", name: "AI 1" }],
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
        { precon: "Abzan Armor [TDC] [2025]", name: "AI 1" },
        { precon: "Adaptive Enchantment [C18] [2018]", name: "AI 2" },
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
      opponents: [{ precon: "Adaptive Enchantment [C18] [2018]", name: "AI 1" }],
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
        { precon: "Abzan Armor [TDC] [2025]", name: "AI 1", ai: { mode: "sim", profile: "Reckless" } },
        { precon: "Adaptive Enchantment [C18] [2018]", name: "AI 2" },
      ],
      aiTimeout: 10,
    });
  });

  const drei = [precon("B"), precon("C"), precon("D")];
  const dreimalStandard = [DEFAULT_AI, DEFAULT_AI, DEFAULT_AI];

  it("Teams und Partnerhand landen in der Nutzlast", () => {
    expect(buildStartGame(false, precon("A"), drei, dreimalStandard, 5, [1, 1, 2, 2], true)).toEqual({
      type: "startGame",
      humanDeck: { precon: "A" },
      humanTeam: 1,
      revealPartnerHand: true,
      opponents: [
        { precon: "B", name: "AI 1", team: 1 },
        { precon: "C", name: "AI 2", team: 2 },
        { precon: "D", name: "AI 3", team: 2 },
      ],
    });
  });

  it("ohne Teams bleibt die Nutzlast unveraendert, auch wenn der Partnerhand-Haken noch gesetzt ist", () => {
    const alt = buildStartGame(false, precon("A"), [precon("B")], [DEFAULT_AI], 5);
    const neu = buildStartGame(false, precon("A"), [precon("B")], [DEFAULT_AI], 5, [-1, -1], true);
    expect(neu).toEqual({ type: "startGame", humanDeck: { precon: "A" }, opponents: [{ precon: "B", name: "AI 1" }] });
    expect(JSON.stringify(neu)).toBe(JSON.stringify(alt));
    expect(JSON.stringify(buildStartGame(false, precon("A"), [precon("B")], [DEFAULT_AI], 5, [], false))).toBe(JSON.stringify(alt));
  });

  it("Partnerhand nur, wenn der menschliche Sitz in einem Team ist und der Haken gesetzt", () => {
    const ohneHaken = buildStartGame(false, precon("A"), drei, dreimalStandard, 5, [1, 1, 2, 2], false);
    expect(ohneHaken).not.toHaveProperty("revealPartnerHand");
    expect(ohneHaken).toHaveProperty("humanTeam", 1);
  });

  it("Partnerhand geht nicht mit, wenn der Mensch allein in seinem Team ist ([1,2,2]): kein Partner, dessen Hand man sehen koennte", () => {
    const msg = buildStartGame(false, precon("A"), [precon("B"), precon("C")], [DEFAULT_AI, DEFAULT_AI], 5, [1, 2, 2], true);
    expect(msg).toHaveProperty("humanTeam", 1);
    expect(msg).not.toHaveProperty("revealPartnerHand");
  });

  it("Zuschauer-Modus: Teams gehoeren den KI-Sitzen, kein humanTeam, keine Partnerhand", () => {
    const msg = buildStartGame(true, undefined, drei, dreimalStandard, 5, [1, 1, 2], true);
    expect(msg).toEqual({
      type: "startGame",
      spectate: true,
      opponents: [
        { precon: "B", name: "AI 1", team: 1 },
        { precon: "C", name: "AI 2", team: 1 },
        { precon: "D", name: "AI 3", team: 2 },
      ],
    });
  });

  it("ein ueberzaehliger Teameintrag schlaegt auf keinen Sitz durch", () => {
    // Zwei Sitze, aber vier Eintraege: der Rest darf nirgends landen.
    const msg = buildStartGame(false, precon("A"), [precon("B")], [DEFAULT_AI], 5, [1, 2, 2, 2], false);
    expect(msg).toEqual({
      type: "startGame",
      humanDeck: { precon: "A" },
      humanTeam: 1,
      opponents: [{ precon: "B", name: "AI 1", team: 2 }],
    });
  });
});
