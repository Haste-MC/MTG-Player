import { describe, expect, it } from "vitest";
import { classify, defaultSelection, progressLabel, updateAllIds } from "./archidektPlan";
import type { ArchidektEntry, DeckInfo } from "./protocol";

const entry = (over: Partial<ArchidektEntry> = {}): ArchidektEntry => ({
  id: 1, name: "Deck", updatedAt: "2026-01-01T00:00:00.000000Z", ...over,
});
const deck = (over: Partial<DeckInfo> = {}): DeckInfo => ({ name: "Mein Deck", commanders: [], ...over });

describe("classify", () => {
  it("kein eigenes Deck mit dieser Id -> neu", () => {
    expect(classify(entry({ id: 1 }), [deck({ archidekt: "2" })])).toBe("neu");
  });

  it("keine eigenen Decks ueberhaupt -> neu", () => {
    expect(classify(entry({ id: 1 }), [])).toBe("neu");
  });

  it("eigenes Deck ohne archidekt-Tag (null) -> neu", () => {
    expect(classify(entry({ id: 1 }), [deck({ archidekt: null })])).toBe("neu");
  });

  it("gleiche Id, gleiches archidektUpdated -> aktuell", () => {
    const e = entry({ id: 12345, updatedAt: "2026-09-18T12:00:00.000000Z" });
    const own = [deck({ archidekt: "12345", archidektUpdated: "2026-09-18T12:00:00.000000Z" })];
    expect(classify(e, own)).toBe("aktuell");
  });

  it("gleiche Id, neueres updatedAt -> geändert", () => {
    const e = entry({ id: 12345, updatedAt: "2026-09-21T09:00:00.000000Z" });
    const own = [deck({ archidekt: "12345", archidektUpdated: "2026-09-18T12:00:00.000000Z" })];
    expect(classify(e, own)).toBe("geändert");
  });

  it("gleiche Id, eigenes Deck ohne archidektUpdated (null) -> geändert", () => {
    const e = entry({ id: 12345 });
    const own = [deck({ archidekt: "12345", archidektUpdated: null })];
    expect(classify(e, own)).toBe("geändert");
  });

  it("gleiche Id, eigenes Deck ohne archidektUpdated (fehlend) -> geändert", () => {
    const e = entry({ id: 12345 });
    const own = [deck({ archidekt: "12345" })];
    expect(classify(e, own)).toBe("geändert");
  });

  it("Id-Vergleich als String (DeckInfo.archidekt ist String, ArchidektEntry.id number)", () => {
    const e = entry({ id: 99, updatedAt: "2026-09-01T00:00:00.000000Z" });
    const own = [deck({ archidekt: "99", archidektUpdated: "2026-09-01T00:00:00.000000Z" })];
    expect(classify(e, own)).toBe("aktuell");
  });

  it("kein Id-Treffer, aber eigenes Deck gleichen Namens ohne archidekt -> übernehmen", () => {
    expect(classify(entry({ id: 1, name: "Koma Ramp" }), [deck({ name: "Koma Ramp" })])).toBe("übernehmen");
    expect(classify(entry({ id: 1, name: "Koma Ramp" }), [deck({ name: "Koma Ramp", archidekt: null })])).toBe("übernehmen");
  });

  it("gleicher Name, aber archidekt-Tag mit anderer Id -> neu (Bridge speichert mit Suffix)", () => {
    expect(classify(entry({ id: 1, name: "Koma Ramp" }), [deck({ name: "Koma Ramp", archidekt: "2" })])).toBe("neu");
  });

  it("Namensvergleich exakt (Gross-/Kleinschreibung, Leerzeichen)", () => {
    expect(classify(entry({ id: 1, name: "Koma Ramp" }), [deck({ name: "koma ramp" })])).toBe("neu");
    expect(classify(entry({ id: 1, name: "Koma Ramp" }), [deck({ name: "Koma Ramp " })])).toBe("neu");
  });

  it("Id-Treffer geht vor Namenstreffer", () => {
    const e = entry({ id: 1, name: "Koma Ramp", updatedAt: "same" });
    const own = [deck({ name: "Koma Ramp" }), deck({ name: "Anders", archidekt: "1", archidektUpdated: "same" })];
    expect(classify(e, own)).toBe("aktuell");
  });
});

describe("defaultSelection", () => {
  it("waehlt nur geänderte Ids, Reihenfolge wie entries", () => {
    const own = [
      deck({ archidekt: "1", archidektUpdated: "old" }),
      deck({ archidekt: "2", archidektUpdated: "same" }),
    ];
    const entries = [
      entry({ id: 2, updatedAt: "same" }), // aktuell
      entry({ id: 1, updatedAt: "new" }), // geändert
      entry({ id: 3, updatedAt: "x" }), // neu
    ];
    expect(defaultSelection(entries, own)).toEqual([1]);
  });

  it("nichts geändert -> leer", () => {
    const own = [deck({ archidekt: "1", archidektUpdated: "same" })];
    const entries = [entry({ id: 1, updatedAt: "same" }), entry({ id: 2, updatedAt: "x" })];
    expect(defaultSelection(entries, own)).toEqual([]);
  });

  it("übernehmen ist nicht vorausgewaehlt", () => {
    const own = [deck({ name: "Koma Ramp" }), deck({ archidekt: "1", archidektUpdated: "old" })];
    const entries = [entry({ id: 5, name: "Koma Ramp" }), entry({ id: 1, updatedAt: "new" })];
    expect(defaultSelection(entries, own)).toEqual([1]);
  });
});

describe("updateAllIds", () => {
  it("aktuell + geändert, nicht neu, Reihenfolge wie entries", () => {
    const own = [
      deck({ archidekt: "1", archidektUpdated: "old" }),
      deck({ archidekt: "2", archidektUpdated: "same" }),
    ];
    const entries = [
      entry({ id: 3, updatedAt: "x" }), // neu
      entry({ id: 2, updatedAt: "same" }), // aktuell
      entry({ id: 1, updatedAt: "new" }), // geändert
    ];
    expect(updateAllIds(entries, own)).toEqual([2, 1]);
  });

  it("alles neu -> leer", () => {
    const entries = [entry({ id: 1 }), entry({ id: 2 })];
    expect(updateAllIds(entries, [])).toEqual([]);
  });

  it("übernehmen gehoert nicht zu \"Alle aktualisieren\"", () => {
    const own = [deck({ name: "Koma Ramp" }), deck({ archidekt: "1", archidektUpdated: "same" })];
    const entries = [entry({ id: 5, name: "Koma Ramp" }), entry({ id: 1, updatedAt: "same" })];
    expect(updateAllIds(entries, own)).toEqual([1]);
  });
});

describe("progressLabel", () => {
  it("\"Deck <id>\" wird auf den Namen aus der Konto-Liste abgebildet", () => {
    expect(progressLabel("Deck 12345", [entry({ id: 12345, name: "Kalamax" })])).toBe("Kalamax");
  });

  it("unbekannte Id bleibt \"Deck <id>\"", () => {
    expect(progressLabel("Deck 7", [entry({ id: 12345, name: "Kalamax" })])).toBe("Deck 7");
    expect(progressLabel("Deck 7", undefined)).toBe("Deck 7");
  });

  it("gespeicherter Name (Resync) bleibt unveraendert", () => {
    expect(progressLabel("Pilze", [entry({ id: 1, name: "Fun With Fungus" })])).toBe("Pilze");
  });
});
