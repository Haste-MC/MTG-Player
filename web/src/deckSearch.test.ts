import { describe, expect, it } from "vitest";
import { filterDecks } from "./deckSearch";
import type { DeckInfo } from "./protocol";

const decks: DeckInfo[] = [
  { name: "Felothar Landfall", commanders: [{ name: "Felothar the Steadfast", imageKey: "c:Felothar the Steadfast|TDC|1" }], archidekt: "12345" },
  { name: "Abzan Armor", commanders: [{ name: "Anafenza, the Foremost" }] },
  { name: "Kalamax Storm", commanders: [{ name: "Kalamax, the Stormsire" }] },
];

describe("filterDecks", () => {
  it("leere Query -> alle", () => {
    expect(filterDecks(decks, "")).toEqual(decks);
    expect(filterDecks(decks, "   ")).toEqual(decks);
  });

  it("trifft ueber den Commander-Namen (case-insensitiv)", () => {
    expect(filterDecks(decks, "felo").map((d) => d.name)).toEqual(["Felothar Landfall"]);
  });

  it("trifft ueber den Deck-Namen, Gross-/Kleinschreibung egal", () => {
    expect(filterDecks(decks, "ABZAN").map((d) => d.name)).toEqual(["Abzan Armor"]);
  });

  it("Query wird getrimmt", () => {
    expect(filterDecks(decks, "  kalamax ").map((d) => d.name)).toEqual(["Kalamax Storm"]);
  });

  it("kein Treffer -> []", () => {
    expect(filterDecks(decks, "xyz")).toEqual([]);
  });
});
