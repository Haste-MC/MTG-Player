import { describe, expect, it } from "vitest";
import { type Pick, toRef } from "./deckref";

describe("toRef", () => {
  it("precon", () => {
    expect(toRef({ kind: "precon", value: "Abzan Armor [TDC] [2025]", name: "" })).toEqual({
      precon: "Abzan Armor [TDC] [2025]",
    });
  });

  it("saved", () => {
    expect(toRef({ kind: "saved", value: "Mein Deck", name: "" })).toEqual({ saved: "Mein Deck" });
  });

  it("text mit und ohne deckName", () => {
    const text = "1 Sol Ring\n1 Felothar the Steadfast\n";
    expect(toRef({ kind: "text", value: text, name: "Mein Deck" })).toEqual({ text, deckName: "Mein Deck" });
    expect(toRef({ kind: "text", value: text, name: "" })).toEqual({ text, deckName: undefined });
  });
});

describe("Gegner-Eintrag (name = Spielername)", () => {
  it("ueberschreibt den Spielernamen, ohne deckName zu verlieren", () => {
    // Regression: startGame.opponents[i] braucht einen Spielernamen ("KI 1") zusaetzlich zum
    // DeckRef - der darf den Speichernamen des Textdecks (deckName) nicht ueberschreiben, weil
    // beide frueher dasselbe Feld "name" geteilt haben.
    const textPick: Pick = { kind: "text", value: "1 Sol Ring\n1 Felothar the Steadfast\n", name: "Mein Deck" };
    const opponent = { ...toRef(textPick), name: "KI 1" };
    expect(opponent).toEqual({ text: textPick.value, deckName: "Mein Deck", name: "KI 1" });
  });
});
