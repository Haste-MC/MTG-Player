import { describe, expect, it } from "vitest";
import { begrenzeHoehe, geleseneHoehe, merkeHoehe, MIN_EIGENE, MIN_HOEHE, SPEICHER_SCHLUESSEL } from "./tableLayout";

/** Winziger Ersatz fuer localStorage; der dritte Fall wirft, wie ein gesperrter Speicher. */
function speicher(start: Record<string, string> = {}, wirft = false): Storage {
  const daten = { ...start };
  return {
    getItem: (k: string) => { if (wirft) throw new Error("gesperrt"); return daten[k] ?? null; },
    setItem: (k: string, v: string) => { if (wirft) throw new Error("gesperrt"); daten[k] = v; },
    removeItem: (k: string) => { if (wirft) throw new Error("gesperrt"); delete daten[k]; },
    clear: () => { for (const k of Object.keys(daten)) delete daten[k]; },
    key: () => null,
    length: 0,
  } as unknown as Storage;
}

describe("begrenzeHoehe", () => {
  it("zu klein wird auf die Mindesthoehe gehoben", () => {
    expect(begrenzeHoehe(10, 900)).toBe(MIN_HOEHE);
    expect(begrenzeHoehe(-50, 900)).toBe(MIN_HOEHE);
  });

  it("zu gross: der eigenen Haelfte bleiben ihre 500px", () => {
    expect(begrenzeHoehe(5000, 900)).toBe(900 - MIN_EIGENE);
    expect(begrenzeHoehe(5000, 1400)).toBe(1400 - MIN_EIGENE);
  });

  it("dazwischen bleibt es beim gezogenen Wert", () => {
    expect(begrenzeHoehe(300, 900)).toBe(300);
  });

  it("winziges Fenster: die Mindesthoehe gewinnt gegen den Deckel", () => {
    expect(begrenzeHoehe(300, 400)).toBe(MIN_HOEHE);
  });
});

describe("geleseneHoehe", () => {
  it("nichts gemerkt = so viel wie noetig", () => {
    expect(geleseneHoehe(speicher(), 900)).toBeUndefined();
  });

  it("gemerkte Hoehe kommt begrenzt zurueck", () => {
    expect(geleseneHoehe(speicher({ [SPEICHER_SCHLUESSEL]: "5000" }), 900)).toBe(900 - MIN_EIGENE);
  });

  it("Unsinn im Speicher wird ignoriert statt uebernommen", () => {
    expect(geleseneHoehe(speicher({ [SPEICHER_SCHLUESSEL]: "abc" }), 900)).toBeUndefined();
    expect(geleseneHoehe(speicher({ [SPEICHER_SCHLUESSEL]: "0" }), 900)).toBeUndefined();
  });

  it("gesperrter Speicher kostet nicht den Tisch", () => {
    expect(geleseneHoehe(speicher({}, true), 900)).toBeUndefined();
    expect(() => merkeHoehe(speicher({}, true), 300)).not.toThrow();
  });
});

describe("merkeHoehe", () => {
  it("merkt und vergisst wieder", () => {
    const s = speicher();
    merkeHoehe(s, 321);
    expect(s.getItem(SPEICHER_SCHLUESSEL)).toBe("321");
    merkeHoehe(s, undefined);
    expect(s.getItem(SPEICHER_SCHLUESSEL)).toBeNull();
  });
});
