import { describe, expect, it, vi } from "vitest";
import { loadAiSettings, saveAiSettings } from "./aiSettings";

const PROFILES = ["Default", "Cautious", "Experimental", "Reckless"];

function storageOf(value: string | null): Pick<Storage, "getItem"> {
  return { getItem: () => value };
}

describe("loadAiSettings", () => {
  it("leeres Storage -> Defaults", () => {
    expect(loadAiSettings(storageOf(null), PROFILES)).toEqual({ picks: [], timeout: 5 });
  });

  it("gespeichertes JSON mit unbekanntem Profil -> faellt auf Default zurueck", () => {
    const raw = JSON.stringify({ picks: [{ mode: "sim", profile: "Nichtexistent" }], timeout: 10 });
    expect(loadAiSettings(storageOf(raw), PROFILES)).toEqual({ picks: [{ mode: "sim", profile: "Default" }], timeout: 10 });
  });

  it("getItem wirft -> Defaults", () => {
    const throwing: Pick<Storage, "getItem"> = {
      getItem: () => { throw new Error("kaputt"); },
    };
    expect(loadAiSettings(throwing, PROFILES)).toEqual({ picks: [], timeout: 5 });
  });

  it("kaputtes JSON -> Defaults", () => {
    expect(loadAiSettings(storageOf("{nicht json"), PROFILES)).toEqual({ picks: [], timeout: 5 });
  });
});

describe("saveAiSettings", () => {
  it("schreibt JSON unter dem Schluessel mtg.lobby.ai", () => {
    const setItem = vi.fn();
    saveAiSettings({ setItem }, { picks: [{ mode: "sim", profile: "Reckless" }], timeout: 10 });
    expect(setItem).toHaveBeenCalledWith("mtg.lobby.ai", JSON.stringify({ picks: [{ mode: "sim", profile: "Reckless" }], timeout: 10 }));
  });

  it("setItem wirft -> kein Fehler nach aussen", () => {
    const setItem = () => { throw new Error("voll"); };
    expect(() => saveAiSettings({ setItem }, { picks: [], timeout: 5 })).not.toThrow();
  });
});
