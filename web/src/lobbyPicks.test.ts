import { describe, expect, it, vi } from "vitest";
import { dropMissing, loadPicks, savePicks, type LobbyPicks } from "./lobbyPicks";
import { EMPTY_PICK } from "./deckref";
import type { DeckInfo } from "./protocol";

function storageOf(value: string | null): Pick<Storage, "getItem"> {
  return { getItem: () => value };
}

const DEFAULTS: LobbyPicks = { human: EMPTY_PICK, ais: [] };

describe("loadPicks", () => {
  it("leeres Storage -> Default", () => {
    expect(loadPicks(() => storageOf(null))).toEqual(DEFAULTS);
  });

  it("kaputtes JSON -> Default", () => {
    expect(loadPicks(() => storageOf("{nicht json"))).toEqual(DEFAULTS);
  });

  it("Zugriff auf den Storage-Bezeichner wirft -> Default", () => {
    const getStorage = (): Pick<Storage, "getItem"> => { throw new DOMException("blockiert"); };
    expect(loadPicks(getStorage)).toEqual(DEFAULTS);
  });

  it("ungueltiger kind -> EMPTY, gueltige Picks bleiben", () => {
    const raw = JSON.stringify({
      human: { kind: "kaputt", value: "x", name: "" },
      ais: [{ kind: "saved", value: "Mein Deck", name: "" }, { kind: "precon", value: 5 }],
    });
    expect(loadPicks(() => storageOf(raw))).toEqual({
      human: EMPTY_PICK,
      ais: [{ kind: "saved", value: "Mein Deck", name: "" }, EMPTY_PICK],
    });
  });
});

describe("savePicks", () => {
  it("Rundtrip ueber den Schluessel mtg.lobby.picks", () => {
    const picks: LobbyPicks = {
      human: { kind: "precon", value: "Tinker Time (TDC)", name: "" },
      ais: [{ kind: "text", value: "1 Forest", name: "Test" }, { kind: "archidekt", value: "12345", name: "" }],
    };
    const setItem = vi.fn();
    savePicks(() => ({ setItem }), picks);
    expect(setItem).toHaveBeenCalledWith("mtg.lobby.picks", JSON.stringify(picks));
    const stored = setItem.mock.calls[0][1] as string;
    expect(loadPicks(() => storageOf(stored))).toEqual(picks);
  });

  it("setItem wirft -> kein Fehler nach aussen", () => {
    const setItem = () => { throw new Error("voll"); };
    expect(() => savePicks(() => ({ setItem }), DEFAULTS)).not.toThrow();
  });
});

describe("dropMissing", () => {
  const precons: DeckInfo[] = [{ name: "Tinker Time (TDC)", commanders: [] }];
  const decks: DeckInfo[] = [{ name: "Mein Deck", commanders: [] }];

  it("precon-Pick ohne passendes Deck -> EMPTY, vorhandener bleibt", () => {
    const p: LobbyPicks = {
      human: { kind: "precon", value: "Weg (XYZ)", name: "" },
      ais: [{ kind: "precon", value: "Tinker Time (TDC)", name: "" }],
    };
    expect(dropMissing(p, precons, decks)).toEqual({ human: EMPTY_PICK, ais: p.ais });
  });

  it("saved-Pick ohne passendes Deck -> EMPTY, vorhandener bleibt", () => {
    const p: LobbyPicks = {
      human: { kind: "saved", value: "Mein Deck", name: "" },
      ais: [{ kind: "saved", value: "Geloescht", name: "" }],
    };
    expect(dropMissing(p, precons, decks)).toEqual({ human: p.human, ais: [EMPTY_PICK] });
  });

  it("text/archidekt bleiben unberuehrt", () => {
    const p: LobbyPicks = {
      human: { kind: "text", value: "1 Forest", name: "T" },
      ais: [{ kind: "archidekt", value: "12345", name: "" }],
    };
    expect(dropMissing(p, [], [])).toEqual(p);
  });
});
