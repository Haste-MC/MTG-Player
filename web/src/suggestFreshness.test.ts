import { describe, expect, it } from "vitest";
import { STALE_AFTER_DAYS, suggestFreshness } from "./suggestFreshness";

const NOW = new Date("2026-09-25T12:00:00Z");

describe("suggestFreshness", () => {
  it("frischer Stand: Datum formatiert, nicht veraltet", () => {
    const f = suggestFreshness("2026-09-23T10:15:30Z", NOW);
    expect(f).toEqual({ label: "23.09.", stale: false });
  });

  it("genau sieben Tage alt gilt noch nicht als veraltet", () => {
    const fetched = new Date(NOW.getTime() - STALE_AFTER_DAYS * 24 * 60 * 60 * 1000).toISOString();
    expect(suggestFreshness(fetched, NOW)?.stale).toBe(false);
  });

  it("deutlich aelter als sieben Tage gilt als veraltet", () => {
    const f = suggestFreshness("2026-08-01T00:00:00Z", NOW);
    expect(f?.stale).toBe(true);
    expect(f?.label).toBe("01.08.");
  });

  it("fehlendes Datum liefert undefined (Datenbank-Rueckfall, keine Standzeile)", () => {
    expect(suggestFreshness(undefined, NOW)).toBeUndefined();
  });
});
