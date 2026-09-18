import { describe, expect, it } from "vitest";
import { reduce, initialState } from "./store";
import type { Choice, Snapshot } from "./protocol";

const snap = (over: Partial<Snapshot> = {}): Snapshot => ({
  type: "state",
  turn: 1,
  phase: "MAIN1",
  me: 1,
  gameOver: false,
  players: [],
  stack: [],
  cards: {},
  stops: { own: [], opp: [] },
  fullControl: false,
  prompt: { message: "", okLabel: "OK", cancelLabel: "Cancel", okEnabled: false, cancelEnabled: false, seq: 0 },
  ...over,
});

describe("reduce", () => {
  it("lobby setzt precons und screen", () => {
    const s = reduce(initialState, { type: "lobby", precons: ["A", "B"] });
    expect(s.precons).toEqual(["A", "B"]);
    expect(s.screen).toBe("lobby");
  });

  it("state wechselt auf den tisch und ersetzt den snapshot", () => {
    const s = reduce(initialState, snap({ turn: 3 }));
    expect(s.screen).toBe("table");
    expect(s.state?.turn).toBe(3);
  });

  it("choice ueberlebt nachfolgende state-updates", () => {
    const c: Choice = { type: "choice", id: 7, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const s1 = reduce(initialState, c);
    expect(s1.choices.map((x) => x.id)).toEqual([7]);
    const s2 = reduce(s1, snap({ turn: 2 }));
    expect(s2.choices.map((x) => x.id)).toEqual([7]);
    expect(s2.state?.turn).toBe(2);
  });

  it("mehrere offene choices sammeln sich aufsteigend nach id", () => {
    const c7: Choice = { type: "choice", id: 7, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const c3: Choice = { type: "choice", id: 3, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const s = reduce(reduce(initialState, c7), c3);
    expect(s.choices.map((c) => c.id)).toEqual([3, 7]);
  });

  it("log haengt an und ist auf 500 zeilen begrenzt", () => {
    let s = initialState;
    for (let i = 0; i < 600; i++) s = reduce(s, { type: "log", text: "z" + i });
    expect(s.log.length).toBe(500);
    expect(s.log[499]).toBe("z599");
  });

  it("gameOver setzt winner, state bleibt", () => {
    const s = reduce(reduce(initialState, snap()), { type: "gameOver", winner: "KI 1" });
    expect(s.winner).toBe("KI 1");
    expect(s.state).toBeDefined();
  });

  it("gameOver leert offene choices", () => {
    const c7: Choice = { type: "choice", id: 7, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const c3: Choice = { type: "choice", id: 3, kind: "confirm", title: "T", message: "?", options: [], min: 1, max: 1 };
    const withChoices = reduce(reduce(initialState, c7), c3);
    const s = reduce(withChoices, { type: "gameOver", winner: "KI 1" });
    expect(s.choices).toEqual([]);
  });

  it("error landet im log", () => {
    const s = reduce(initialState, { type: "error", text: "kaputt" });
    expect(s.log[0]).toContain("kaputt");
  });

  it("reveal-choice wird wie jede andere choice gespeichert", () => {
    const c: Choice = { type: "choice", id: 9, kind: "reveal", title: "Aufgedeckt", message: "Bibliothek", options: [{ index: 0, label: "Blitz" }], min: 0, max: 0 };
    const s = reduce(initialState, c);
    expect(s.choices[0]?.id).toBe(9);
    expect(s.choices[0]?.kind).toBe("reveal");
  });
});
