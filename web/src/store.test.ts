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

  it("lobby setzt gespeicherte decks", () => {
    const s = reduce(initialState, { type: "lobby", precons: ["A"], decks: ["Mein Deck"] });
    expect(s.decks).toEqual(["Mein Deck"]);
    const s2 = reduce(s, { type: "lobby", precons: ["A"] });
    expect(s2.decks).toEqual([]);
  });

  it("lobby ohne KI-Felder -> Standardwerte (Modi, Default-Profil, 5s)", () => {
    const s = reduce(initialState, { type: "lobby", precons: ["A"] });
    expect(s.aiModes).toEqual(["standard", "hybrid", "sim"]);
    expect(s.aiProfiles).toEqual(["Default"]);
    expect(s.aiTimeout).toBe(5);
  });

  it("lobby uebernimmt aiModes/aiProfiles/aiTimeout von der Bridge", () => {
    const s = reduce(initialState, {
      type: "lobby",
      precons: ["A"],
      aiModes: ["standard", "hybrid", "sim"],
      aiProfiles: ["Default", "Cautious", "Experimental", "Reckless"],
      aiTimeout: 5,
    });
    expect(s.aiProfiles).toEqual(["Default", "Cautious", "Experimental", "Reckless"]);
    expect(s.aiTimeout).toBe(5);
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

  it("log speichert kind und card", () => {
    const s = reduce(initialState, { type: "log", text: "KI 1 spielt Forest", kind: "LAND", card: 12 });
    expect(s.log[0]).toEqual({ text: "KI 1 spielt Forest", kind: "LAND", card: 12 });
  });

  it("error landet als warn-eintrag im log", () => {
    const s = reduce(initialState, { type: "error", text: "kaputt" });
    expect(s.log[0].warn).toBe(true);
    expect(s.log[0].text).toContain("kaputt");
  });

  it("'das geht gerade nicht' wird ein toast statt einer log-zeile", () => {
    const s = reduce(initialState, { type: "error", text: "Das geht gerade nicht." });
    expect(s.log).toEqual([]);
    expect(s.toast?.text).toBe("Das geht gerade nicht.");
    // ein zweiter Hinweis zaehlt hoch, damit der Ausblend-Timer neu startet
    const s2 = reduce(s, { type: "error", text: "Das geht gerade nicht." });
    expect(s2.toast?.n).toBe(2);
  });

  it("log haengt an und ist auf 500 zeilen begrenzt", () => {
    let s = initialState;
    for (let i = 0; i < 600; i++) s = reduce(s, { type: "log", text: "z" + i });
    expect(s.log.length).toBe(500);
    expect(s.log[499].text).toBe("z599");
  });

  it("neues spiel leert das log", () => {
    let s = reduce(initialState, { type: "log", text: "alt" });
    s = reduce(s, snap({ turn: 0 }));
    // ein state mit turn 0 (Spielstart) leert das Log
    expect(s.log.length).toBe(0);
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

  it("log verwirft wiederholte zeilen mit derselben id (reconnect-replay)", () => {
    let s = reduce(initialState, { type: "log", text: "eins", id: 1 });
    s = reduce(s, { type: "log", text: "zwei", id: 2 });
    // Replay derselben zwei Zeilen (z. B. nach Reconnect) - keine zweite Kopie
    s = reduce(s, { type: "log", text: "eins", id: 1 });
    s = reduce(s, { type: "log", text: "zwei", id: 2 });
    expect(s.log.map((l) => l.text)).toEqual(["eins", "zwei"]);
  });

  it("log akzeptiert ids einer neuen partie, die nach einem leeren-event wieder bei 1 starten", () => {
    let s = reduce(initialState, { type: "log", text: "altes spiel", id: 5 });
    s = reduce(s, snap({ turn: 0 })); // Spielstart: leert log und lastLogId
    s = reduce(s, { type: "log", text: "neues spiel", id: 1 });
    expect(s.log.map((l) => l.text)).toEqual(["neues spiel"]);
  });

  it("reveal-choice wird wie jede andere choice gespeichert", () => {
    const c: Choice = { type: "choice", id: 9, kind: "reveal", title: "Aufgedeckt", message: "Bibliothek", options: [{ index: 0, label: "Blitz" }], min: 0, max: 0 };
    const s = reduce(initialState, c);
    expect(s.choices[0]?.id).toBe(9);
    expect(s.choices[0]?.kind).toBe("reveal");
  });
});
