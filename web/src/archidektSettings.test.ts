import { describe, expect, it, vi } from "vitest";
import { loadArchidektUser, saveArchidektUser } from "./archidektSettings";

function storageOf(value: string | null): Pick<Storage, "getItem"> {
  return { getItem: () => value };
}

describe("loadArchidektUser", () => {
  it("leeres Storage -> \"\"", () => {
    expect(loadArchidektUser(() => storageOf(null))).toBe("");
  });

  it("gespeicherter Benutzername wird geladen", () => {
    expect(loadArchidektUser(() => storageOf("kevin"))).toBe("kevin");
  });

  it("getItem wirft -> \"\"", () => {
    const throwing: Pick<Storage, "getItem"> = {
      getItem: () => { throw new Error("kaputt"); },
    };
    expect(loadArchidektUser(() => throwing)).toBe("");
  });

  it("schon der Zugriff auf den Storage-Bezeichner wirft (z. B. blockierter Storage) -> \"\"", () => {
    const getStorage = (): Pick<Storage, "getItem"> => { throw new DOMException("blockiert"); };
    expect(loadArchidektUser(getStorage)).toBe("");
  });
});

describe("saveArchidektUser", () => {
  it("schreibt den Benutzernamen unter mtg.lobby.archidekt", () => {
    const setItem = vi.fn();
    saveArchidektUser(() => ({ setItem }), "kevin");
    expect(setItem).toHaveBeenCalledWith("mtg.lobby.archidekt", "kevin");
  });

  it("Rundtrip", () => {
    const setItem = vi.fn();
    saveArchidektUser(() => ({ setItem }), "kevin");
    expect(loadArchidektUser(() => storageOf(setItem.mock.calls[0][1] as string))).toBe("kevin");
  });

  it("setItem wirft -> kein Fehler nach aussen", () => {
    const setItem = () => { throw new Error("voll"); };
    expect(() => saveArchidektUser(() => ({ setItem }), "kevin")).not.toThrow();
  });

  it("schon der Zugriff auf den Storage-Bezeichner wirft -> kein Fehler nach aussen", () => {
    const getStorage = (): Pick<Storage, "setItem"> => { throw new DOMException("blockiert"); };
    expect(() => saveArchidektUser(getStorage, "kevin")).not.toThrow();
  });
});
