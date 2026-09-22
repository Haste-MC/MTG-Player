import { beforeEach, describe, expect, it, vi } from "vitest";
import { INCORRECT_ACTION_TEXT, reduce, initialState, useStore } from "./store";
import type { ArchidektDecks, ArchidektProgress, Choice, DeckInfo, MatchRecord, Snapshot, StartGame } from "./protocol";
import { send } from "./ws";

vi.mock("./ws", () => ({ send: vi.fn() }));

const deck = (name: string): DeckInfo => ({ name, commanders: [] });

const matchRecord = (over: Partial<MatchRecord> = {}): MatchRecord => ({
  id: "m1", startedAt: "2026-09-10T18:02:11.000Z", endedAt: "2026-09-10T18:44:33.000Z", durationMs: 2542000,
  source: "live", turns: 12, reason: "AllOpponentsLost", draw: false, counted: true, excludeReason: null,
  seats: [
    {
      name: "Du", deck: "Titania, Gaea Incarnate", human: true, ai: null, winner: true, lossReason: null,
      eliminatedTurn: null, mulligans: 0, lands: 10, landsByTurn: [0, 1, 2, 3], missedLandDrops: 0,
      firstMissedLandDrop: null, spells: 10, spellMana: 25, commanderCasts: 1, commanderTax: 0,
      firstCommanderTurn: 4, damageDealt: 21, damageTaken: 10, combatDamageTaken: 8, lifeEnd: 30, poisonEnd: 0,
    },
  ],
  ...over,
});

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
    const s = reduce(initialState, { type: "lobby", precons: [deck("A"), deck("B")] });
    expect(s.precons).toEqual([deck("A"), deck("B")]);
    expect(s.screen).toBe("lobby");
  });

  it("lobby setzt gespeicherte decks", () => {
    const mine: DeckInfo = { name: "Mein Deck", commanders: [{ name: "Felothar the Steadfast", imageKey: "c:Felothar the Steadfast|TDC|1" }], archidekt: "12345" };
    const s = reduce(initialState, { type: "lobby", precons: [deck("A")], decks: [mine] });
    expect(s.decks).toEqual([mine]);
    const s2 = reduce(s, { type: "lobby", precons: [deck("A")] });
    expect(s2.decks).toEqual([]);
  });

  it("lobby ohne KI-Felder -> Standardwerte (Modi, Default-Profil, 5s)", () => {
    const s = reduce(initialState, { type: "lobby", precons: [deck("A")] });
    expect(s.aiModes).toEqual(["standard", "hybrid", "sim"]);
    expect(s.aiProfiles).toEqual(["Default"]);
    expect(s.aiTimeout).toBe(5);
  });

  it("lobby uebernimmt aiModes/aiProfiles/aiTimeout von der Bridge", () => {
    const s = reduce(initialState, {
      type: "lobby",
      precons: [deck("A")],
      aiModes: ["standard", "hybrid", "sim"],
      aiProfiles: ["Default", "Cautious", "Experimental", "Reckless"],
      aiTimeout: 5,
    });
    expect(s.aiModes).toEqual(["standard", "hybrid", "sim"]);
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

  it("neuer state (turn 0) nach gameOver und noteStart ist eine neue partie - winner und log werden geleert", () => {
    let s = reduce(initialState, snap());
    s = reduce(s, { type: "log", text: "altes spiel", id: 5 });
    s = reduce(s, { type: "gameOver", winner: "KI 1" });
    expect(s.winner).toBe("KI 1");
    // "Nochmal spielen" im Overlay ruft noteStart() vor dem send() auf (siehe store.ts) - das setzt
    // expectNewMatch. s.state ist zu diesem Zeitpunkt noch die alte Partie; erst der naechste
    // turn-0-Snapshot mit gesetztem Flag zaehlt als Spielstart.
    s = { ...s, expectNewMatch: true };
    s = reduce(s, snap({ turn: 0 }));
    expect(s.winner).toBeUndefined();
    expect(s.log).toEqual([]);
    expect(s.lastLogId).toBe(0);
    expect(s.expectNewMatch).toBe(false);
  });

  it("turn-0-state nach gameOver OHNE expectNewMatch ist ein reconnect-replay - log und winner bleiben", () => {
    // Sofortiges Concede kann selbst bei turn 0 enden; kommt danach per Reconnect derselbe Snapshot
    // erneut an, ohne dass "Nochmal spielen" geklickt wurde (expectNewMatch also nicht gesetzt ist),
    // darf das nicht als neuer Spielstart durchgehen - sonst wuerden Log und Overlay mitten im
    // offenen Spielende-Dialog verschwinden.
    let s = reduce(initialState, snap({ turn: 0 }));
    s = reduce(s, { type: "log", text: "partie zu ende bei zug 0", id: 5 });
    s = reduce(s, { type: "gameOver", winner: "KI 1" });
    expect(s.expectNewMatch).toBe(false);
    s = reduce(s, snap({ turn: 0 }));
    expect(s.winner).toBe("KI 1");
    expect(s.log.map((l) => l.text)).toEqual(["partie zu ende bei zug 0"]);
    expect(s.lastLogId).toBe(5);
  });

  it("error setzt expectNewMatch zurueck (fehlgeschlagenes startGame darf das flag nicht scharf lassen)", () => {
    const s0 = { ...initialState, expectNewMatch: true };
    const s = reduce(s0, { type: "error", text: "Unbekannte Karte: Foo" });
    expect(s.expectNewMatch).toBe(false);
    // gilt auch fuer den flashIncorrectAction-Sonderfall (Toast statt Log-Zeile)
    const s2 = reduce(s0, { type: "error", text: INCORRECT_ACTION_TEXT });
    expect(s2.expectNewMatch).toBe(false);
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

describe("store: serie", () => {
  const msg: StartGame = { type: "startGame", humanDeck: { precon: "A" }, opponents: [{ precon: "B", name: "KI 1" }] };

  beforeEach(() => {
    useStore.setState({ ...initialState, lastStart: undefined, series: undefined, winner: undefined, state: undefined });
  });

  it("noteStart merkt lastStart und startet die serie", () => {
    useStore.getState().noteStart(msg);
    expect(useStore.getState().lastStart).toEqual(msg);
    expect(useStore.getState().series).toEqual({ key: expect.any(String), wins: {}, games: 0 });
  });

  it("gameOver mit winner zaehlt in der serie", () => {
    useStore.getState().noteStart(msg);
    useStore.getState().apply({ type: "gameOver", winner: "Du" });
    expect(useStore.getState().series?.wins.Du).toBe(1);
    expect(useStore.getState().series?.games).toBe(1);
  });

  it("gameOver ohne serie laesst series undefined", () => {
    const s = reduce(initialState, { type: "gameOver", winner: "Du" });
    expect(s.series).toBeUndefined();
  });

  it("backToLobby behaelt lastStart und series", () => {
    useStore.getState().noteStart(msg);
    useStore.getState().apply({ type: "gameOver", winner: "Du" });
    useStore.getState().backToLobby();
    expect(useStore.getState().screen).toBe("lobby");
    expect(useStore.getState().series?.wins.Du).toBe(1);
    expect(useStore.getState().lastStart).toEqual(msg);
  });

  it("noteStart mit gleichem key behaelt den stand, resetSeries setzt zurueck", () => {
    useStore.getState().noteStart(msg);
    useStore.getState().apply({ type: "gameOver", winner: "Du" });
    useStore.getState().noteStart(msg);
    expect(useStore.getState().series?.wins.Du).toBe(1);
    useStore.getState().resetSeries();
    expect(useStore.getState().series).toBeUndefined();
  });

  it("expectNewMatch: noteStart setzt das flag, der erste turn-0-state loescht es (doppelklick-schutz)", () => {
    expect(useStore.getState().expectNewMatch).toBe(false);
    useStore.getState().noteStart(msg);
    expect(useStore.getState().expectNewMatch).toBe(true);   // Table/Lobby sperren jetzt "Nochmal spielen"/"Spiel starten"
    useStore.getState().apply(snap({ turn: 0 }));
    expect(useStore.getState().expectNewMatch).toBe(false);
    expect(useStore.getState().screen).toBe("table");
  });

  it("setBestOf setzt bestOf, default 0", () => {
    expect(useStore.getState().bestOf).toBe(0);
    useStore.getState().setBestOf(5);
    expect(useStore.getState().bestOf).toBe(5);
  });
});

describe("store: archidekt", () => {
  const entries: ArchidektDecks["decks"] = [
    { id: 1, name: "Deck 1", updatedAt: "2026-09-01T00:00:00.000000Z", art: "https://example.com/a.webp" },
  ];

  beforeEach(() => {
    useStore.setState({ ...initialState, archidekt: { loading: false } });
    vi.mocked(send).mockClear();
  });

  it("archidektDecks setzt die liste und beendet loading", () => {
    const loading = reduce({ ...initialState, archidekt: { loading: true } }, { type: "archidektDecks", username: "kevin", decks: entries });
    expect(loading.archidekt).toEqual({ username: "kevin", decks: entries, loading: false });
  });

  it("archidektProgress landet in archidekt.progress", () => {
    const progress: ArchidektProgress = { type: "archidektProgress", done: 2, total: 5, current: "Koma, World-Eater", errors: [] };
    const s = reduce({ ...initialState, archidekt: { loading: true } }, progress);
    expect(s.archidekt.progress).toEqual(progress);
  });

  it("archidektProgress am ende (done === total, current fehlt) bleibt als fertig im store stehen", () => {
    const done: ArchidektProgress = { type: "archidektProgress", done: 5, total: 5, errors: [] };
    const s = reduce({ ...initialState, archidekt: { loading: true } }, done);
    expect(s.archidekt.progress).toEqual({ type: "archidektProgress", done: 5, total: 5, errors: [] });
    expect(s.archidekt.progress?.current).toBeUndefined();
  });

  it("error beendet loading", () => {
    const s = reduce({ ...initialState, archidekt: { loading: true, username: "kevin" } }, { type: "error", text: "Archidekt: HTTP 500" });
    expect(s.archidekt.loading).toBe(false);
    expect(s.archidekt.username).toBe("kevin");
  });

  it("requestArchidektList setzt loading und sendet archidektList", () => {
    useStore.getState().requestArchidektList("kevin");
    expect(useStore.getState().archidekt.loading).toBe(true);
    expect(send).toHaveBeenCalledWith({ type: "archidektList", username: "kevin" });
  });
});

describe("store: matches", () => {
  it("initialState startet mit einer leeren Partienliste", () => {
    expect(initialState.matches).toEqual([]);
  });

  it("matches ersetzt die Partienliste", () => {
    const list = [matchRecord({ id: "m1" }), matchRecord({ id: "m2" })];
    const s = reduce(initialState, { type: "matches", matches: list });
    expect(s.matches).toEqual(list);
  });

  it("eine erneute matches-Nachricht ersetzt die vorige Liste, statt sie zu ergaenzen", () => {
    const first = reduce(initialState, { type: "matches", matches: [matchRecord({ id: "m1" })] });
    const second = reduce(first, { type: "matches", matches: [matchRecord({ id: "m2" })] });
    expect(second.matches.map((m) => m.id)).toEqual(["m2"]);
  });

  it("openStats oeffnet den Screen, backToLobby schliesst ihn wieder", () => {
    useStore.getState().openStats();
    expect(useStore.getState().screen).toBe("stats");
    useStore.getState().backToLobby();
    expect(useStore.getState().screen).toBe("lobby");
  });

  it("eine lobby-Nachricht laesst den Statistik-Screen stehen", () => {
    const s = reduce({ ...initialState, screen: "stats" }, { type: "lobby", precons: [deck("A")] });
    expect(s.screen).toBe("stats");
    expect(s.precons).toEqual([deck("A")]);
  });
});
