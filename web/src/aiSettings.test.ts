import { describe, expect, it, vi } from "vitest";
import { loadAiSettings, restoreSlots, saveAiSettings } from "./aiSettings";
import { DEFAULT_AI } from "./lobbyPayload";

const PROFILES = ["Default", "Cautious", "Experimental", "Reckless"];

function storageOf(value: string | null): Pick<Storage, "getItem"> {
  return { getItem: () => value };
}

describe("loadAiSettings", () => {
  it("leeres Storage -> Defaults", () => {
    expect(loadAiSettings(() => storageOf(null), PROFILES)).toEqual({ picks: [], timeout: 5 });
  });

  it("gespeichertes JSON mit unbekanntem Profil -> faellt auf Default zurueck", () => {
    const raw = JSON.stringify({ picks: [{ mode: "sim", profile: "Nichtexistent" }], timeout: 10 });
    expect(loadAiSettings(() => storageOf(raw), PROFILES)).toEqual({ picks: [{ mode: "sim", profile: "Default" }], timeout: 10 });
  });

  it("getItem wirft -> Defaults", () => {
    const throwing: Pick<Storage, "getItem"> = {
      getItem: () => { throw new Error("kaputt"); },
    };
    expect(loadAiSettings(() => throwing, PROFILES)).toEqual({ picks: [], timeout: 5 });
  });

  it("kaputtes JSON -> Defaults", () => {
    expect(loadAiSettings(() => storageOf("{nicht json"), PROFILES)).toEqual({ picks: [], timeout: 5 });
  });

  it("schon der Zugriff auf den Storage-Bezeichner wirft (z. B. blockierter Storage) -> Defaults", () => {
    // Simuliert, dass allein das Auswerten von `localStorage` wirft - nicht erst ein Methodenaufruf
    // darauf. Der Getter wird erst INNERHALB von loadAiSettings aufgerufen, also im try/catch.
    const getStorage = (): Pick<Storage, "getItem"> => { throw new DOMException("blockiert"); };
    expect(loadAiSettings(getStorage, PROFILES)).toEqual({ picks: [], timeout: 5 });
  });
});

describe("saveAiSettings", () => {
  it("schreibt JSON unter dem Schluessel mtg.lobby.ai", () => {
    const setItem = vi.fn();
    saveAiSettings(() => ({ setItem }), { picks: [{ mode: "sim", profile: "Reckless" }], timeout: 10 });
    expect(setItem).toHaveBeenCalledWith("mtg.lobby.ai", JSON.stringify({ picks: [{ mode: "sim", profile: "Reckless" }], timeout: 10 }));
  });

  it("setItem wirft -> kein Fehler nach aussen", () => {
    const setItem = () => { throw new Error("voll"); };
    expect(() => saveAiSettings(() => ({ setItem }), { picks: [], timeout: 5 })).not.toThrow();
  });

  it("schon der Zugriff auf den Storage-Bezeichner wirft -> kein Fehler nach aussen", () => {
    const getStorage = (): Pick<Storage, "setItem"> => { throw new DOMException("blockiert"); };
    expect(() => saveAiSettings(getStorage, { picks: [], timeout: 5 })).not.toThrow();
  });
});

describe("restoreSlots", () => {
  it("Roundtrip mit 3 Picks bleibt 3", () => {
    const picks = [
      { mode: "sim" as const, profile: "Reckless" },
      { mode: "hybrid" as const, profile: "Cautious" },
      { mode: "standard" as const, profile: "Default" },
    ];
    expect(restoreSlots(picks, { min: 1, max: 5 })).toEqual(picks);
  });

  it("weniger Picks als min -> mit DEFAULT_AI bis min aufgefuellt", () => {
    expect(restoreSlots([], { min: 1, max: 5 })).toEqual([DEFAULT_AI]);
    expect(restoreSlots([], { min: 2, max: 6 })).toEqual([DEFAULT_AI, DEFAULT_AI]);
  });

  it("mehr Picks als max -> auf max gekappt", () => {
    const picks = Array.from({ length: 8 }, (_, i) => ({ mode: "standard" as const, profile: `P${i}` }));
    const result = restoreSlots(picks, { min: 1, max: 5 });
    expect(result).toHaveLength(5);
    expect(result).toEqual(picks.slice(0, 5));
  });
});
