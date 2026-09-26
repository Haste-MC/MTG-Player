import { beforeEach, describe, expect, it, vi } from "vitest";
import { INCORRECT_ACTION_TEXT, reduce, initialState, sparringOpponents, useStore } from "./store";
import type { ArchidektDecks, ArchidektProgress, CardStatsMsg, CardSuggestionsMsg, Choice, DeckInfo, MatchRecord, Snapshot, StartGame } from "./protocol";
import { send } from "./ws";

vi.mock("./ws", () => ({ send: vi.fn() }));

const deck = (name: string): DeckInfo => ({ name, commanders: [] });

const matchRecord = (over: Partial<MatchRecord> = {}): MatchRecord => ({
  id: "m1", startedAt: "2026-09-10T18:02:11.000Z", endedAt: "2026-09-10T18:44:33.000Z", durationMs: 2542000,
  source: "live", aiTimeout: 5, turns: 12, reason: "AllOpponentsLost", draw: false, counted: true, excludeReason: null,
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

  it("gameOver loescht eine laufende denk-anzeige", () => {
    const laufend = reduce(reduce(initialState, snap()), { type: "thinking", player: 2, seconds: 31 });
    expect(laufend.thinking).toBeDefined();
    expect(reduce(laufend, { type: "gameOver", winner: "KI 1" }).thinking).toBeUndefined();
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

describe("store: serien-countdown", () => {
  beforeEach(() => {
    useStore.setState({ ...initialState, seriesCountdown: undefined });
  });

  it("initialState hat keinen laufenden countdown", () => {
    expect(initialState.seriesCountdown).toBeUndefined();
  });

  it("startSeriesCountdown setzt die sekunden", () => {
    useStore.getState().startSeriesCountdown(5);
    expect(useStore.getState().seriesCountdown).toBe(5);
  });

  it("tickSeriesCountdown zaehlt herunter", () => {
    useStore.getState().startSeriesCountdown(3);
    useStore.getState().tickSeriesCountdown();
    expect(useStore.getState().seriesCountdown).toBe(2);
    useStore.getState().tickSeriesCountdown();
    expect(useStore.getState().seriesCountdown).toBe(1);
  });

  it("tickSeriesCountdown wird bei 1 zu undefined statt 0 anzuzeigen", () => {
    useStore.getState().startSeriesCountdown(1);
    useStore.getState().tickSeriesCountdown();
    expect(useStore.getState().seriesCountdown).toBeUndefined();
  });

  it("tickSeriesCountdown ohne laufenden countdown bleibt undefined", () => {
    useStore.getState().tickSeriesCountdown();
    expect(useStore.getState().seriesCountdown).toBeUndefined();
  });

  it("cancelSeriesCountdown bricht sofort ab", () => {
    useStore.getState().startSeriesCountdown(5);
    useStore.getState().cancelSeriesCountdown();
    expect(useStore.getState().seriesCountdown).toBeUndefined();
  });

  it("gameOver setzt einen laufenden countdown der vorigen partie zurueck", () => {
    useStore.getState().startSeriesCountdown(4);
    useStore.getState().apply({ type: "gameOver", winner: "Du" });
    expect(useStore.getState().seriesCountdown).toBeUndefined();
  });

  it("backToLobby raeumt einen laufenden countdown weg", () => {
    useStore.getState().startSeriesCountdown(4);
    useStore.getState().backToLobby();
    expect(useStore.getState().seriesCountdown).toBeUndefined();
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

  it("matchesTotal haelt fest, wie viele Partien die Bridge insgesamt hat (die Liste ist gedeckelt)", () => {
    const s = reduce(initialState, { type: "matches", matches: [matchRecord({ id: "m1" })], total: 412 });
    expect(s.matchesTotal).toBe(412);
  });

  it("ohne total (Bridge vor Runde A) ist matchesTotal die Laenge der Liste, nicht 0", () => {
    const s = reduce(initialState, { type: "matches", matches: [matchRecord({ id: "m1" }), matchRecord({ id: "m2" })] });
    expect(s.matchesTotal).toBe(2);
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

  it("ein state-Schnappschuss der laufenden Partie laesst den Statistik-Screen stehen", () => {
    // Reconnect/requestState waehrend die Statistik offen ist: der Snapshot kommt an (s.state wird
    // aktualisiert), reisst den Blick aber nicht an den Tisch zurueck - wie im lobby-Zweig.
    const laufend = { ...initialState, screen: "stats" as const, state: snap({ turn: 4 }) };
    const s = reduce(laufend, snap({ turn: 5 }));
    expect(s.screen).toBe("stats");
    expect(s.state?.turn).toBe(5);
  });

  it("ein Spielstart holt auch vom Statistik-Screen an den Tisch", () => {
    const s = reduce({ ...initialState, screen: "stats" }, snap({ turn: 0 }));
    expect(s.screen).toBe("table");
  });
});

describe("store: thinking", () => {
  it("thinking setzt Sitz und Sekunden", () => {
    const s = reduce(initialState, { type: "thinking", player: 2, seconds: 5 });
    expect(s.thinking).toEqual({ player: 2, seconds: 5 });
  });

  it("thinking ohne bekannten Sitz setzt die Anzeige trotzdem", () => {
    // Die Bridge laesst null-Felder weg (Jackson) - "player" kann fehlen, nicht nur null sein.
    const s = reduce(initialState, { type: "thinking", seconds: 8 });
    expect(s.thinking).toEqual({ player: undefined, seconds: 8 });
  });

  it("thinking mit seconds 0 loescht das Feld", () => {
    const laufend = reduce(initialState, { type: "thinking", player: 2, seconds: 42 });
    const s = reduce(laufend, { type: "thinking", seconds: 0 });
    expect(s.thinking).toBeUndefined();
  });

  it("ein state-Schnappschuss loescht die Anzeige (sichtbare Aktivitaet)", () => {
    const laufend = reduce({ ...initialState, state: snap({ turn: 4 }) }, { type: "thinking", player: 2, seconds: 12 });
    expect(laufend.thinking).toBeDefined();
    const s = reduce(laufend, snap({ turn: 5 }));
    expect(s.thinking).toBeUndefined();
  });

  it("backToLobby raeumt die Anzeige weg", () => {
    useStore.getState().apply({ type: "thinking", player: 1, seconds: 9 });
    expect(useStore.getState().thinking).toBeDefined();
    useStore.getState().backToLobby();
    expect(useStore.getState().thinking).toBeUndefined();
  });
});

describe("store: Deckanalyse und Partie-Detail", () => {
  const analysis = {
    cards: 100, lands: 36, basics: 22, avgCmc: 3.4,
    curve: { "0": 2, "1": 7, "2": 13, "3": 15, "4": 11, "5": 7, "6": 5, "7+": 4 },
    sources: { W: 0, U: 12, B: 15, R: 0, G: 0, any: 6 }, identity: ["U", "B"],
    categories: { ramp: 8, draw: 9, removal: 6, wipes: 1, counters: 3, flyerDefense: 2, wipeProtection: 0, recursion: 4, tutors: 2 },
    unclassified: 31,
  };
  /** Minimale Antwort auf suggestCards - nur die Felder, die reduce() ueberhaupt anfasst (Schluessel
   *  ist deck, siehe store.ts case "cardSuggestions"). */
  const suggestions = (deck: string): CardSuggestionsMsg => ({
    type: "cardSuggestions", deck, source: "edhrec", suggestions: [],
  });
  /** Derselbe Datensatz, aber mit Zeitachse - so kommt er von matchDetail zurueck. */
  const withTimeline = (id: string): MatchRecord => {
    const r = matchRecord({ id });
    return { ...r, v: 2, seats: [{ ...r.seats[0], timeline: [{ turn: 1, lands: 1, creatures: 0, life: 40, hand: 6, spells: 1 }] }] };
  };

  beforeEach(() => {
    useStore.setState({ ...initialState });
    vi.mocked(send).mockClear();
  });

  it("initialState startet ohne Analysen und ohne Details", () => {
    expect(initialState.deckAnalyses).toEqual({});
    expect(initialState.matchDetails).toEqual({});
  });

  it("deckAnalysis merkt die Analyse je Deckname", () => {
    const s = reduce(initialState, { type: "deckAnalysis", deck: "Koma Ramp", analysis });
    expect(s.deckAnalyses["Koma Ramp"]).toEqual(analysis);
    const zweite = reduce(s, { type: "deckAnalysis", deck: "Krenko Goblins", analysis: { ...analysis, lands: 34 } });
    expect(Object.keys(zweite.deckAnalyses).sort()).toEqual(["Koma Ramp", "Krenko Goblins"]);
    expect(zweite.deckAnalyses["Koma Ramp"]).toEqual(analysis);
  });

  it("cardSuggestions merkt die Antwort je Deckname", () => {
    const s = reduce(initialState, suggestions("Koma Ramp"));
    expect(s.suggestions["Koma Ramp"]).toEqual(suggestions("Koma Ramp"));
    // Eine zweite Antwort fuer ein anderes Deck ersetzt den ersten Stand nicht (dasselbe Muster wie
    // deckAnalysis oben, siehe store.ts case "cardSuggestions").
    const zweite = reduce(s, suggestions("Krenko Goblins"));
    expect(Object.keys(zweite.suggestions).sort()).toEqual(["Koma Ramp", "Krenko Goblins"]);
    expect(zweite.suggestions["Koma Ramp"]).toEqual(suggestions("Koma Ramp"));
  });

  it("match merkt das Detail je Id, ohne die Partienliste anzufassen", () => {
    const liste = reduce(initialState, { type: "matches", matches: [matchRecord({ id: "m1" })] });
    const s = reduce(liste, { type: "match", match: withTimeline("m1") });
    expect(s.matchDetails["m1"].seats[0].timeline).toHaveLength(1);
    expect(s.matches[0].seats[0].timeline).toBeUndefined();   // die Liste bleibt ohne Zeitachse
  });

  it("eine geloeschte Partie faellt mit der naechsten matches-Nachricht aus den Details", () => {
    const geladen = reduce(
      reduce(initialState, { type: "matches", matches: [matchRecord({ id: "m1" }), matchRecord({ id: "m2" })] }),
      { type: "match", match: withTimeline("m1") },
    );
    expect(geladen.matchDetails["m1"]).toBeDefined();
    const s = reduce(geladen, { type: "matches", matches: [matchRecord({ id: "m2" })] });
    expect(s.matchDetails).toEqual({});
  });

  it("requestDeckAnalysis fragt einmal und merkt sich die offene Anfrage", () => {
    useStore.getState().requestDeckAnalysis("Koma Ramp");
    expect(send).toHaveBeenCalledWith({ type: "analyzeDeck", deck: "Koma Ramp" });
    expect(useStore.getState().pendingAnalysis).toEqual(["Koma Ramp"]);
    useStore.getState().requestDeckAnalysis("Koma Ramp");
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("eine vorliegende Analyse wird nicht erneut angefragt", () => {
    useStore.getState().apply({ type: "deckAnalysis", deck: "Koma Ramp", analysis });
    expect(useStore.getState().pendingAnalysis).toEqual([]);
    useStore.getState().requestDeckAnalysis("Koma Ramp");
    expect(send).not.toHaveBeenCalled();
  });

  it("requestMatchDetail fragt einmal je Id, ein vorliegendes Detail gar nicht", () => {
    useStore.getState().requestMatchDetail("m1");
    useStore.getState().requestMatchDetail("m1");
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith({ type: "matchDetail", id: "m1" });
    useStore.getState().apply({ type: "match", match: withTimeline("m1") });
    expect(useStore.getState().pendingMatch).toEqual([]);
    useStore.getState().requestMatchDetail("m1");
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("ein Fehler der Bridge gibt die offenen Anfragen wieder frei", () => {
    useStore.getState().requestMatchDetail("weg");
    useStore.getState().requestDeckAnalysis("Kein Deck");
    useStore.getState().apply({ type: "error", text: "Partie weg: unbekannte Partie" });
    expect(useStore.getState().pendingMatch).toEqual([]);
    expect(useStore.getState().pendingAnalysis).toEqual([]);
    // Nach dem Fehler darf erneut gefragt werden (die Bridge kann inzwischen wieder koennen).
    useStore.getState().requestMatchDetail("weg");
    expect(send).toHaveBeenCalledTimes(3);
  });

  it("requestCardSuggestions fragt einmal je Deck und merkt sich die offene Anfrage", () => {
    useStore.getState().requestCardSuggestions("Koma Ramp", ["ramp"]);
    expect(send).toHaveBeenCalledWith({ type: "suggestCards", deck: "Koma Ramp", roles: ["ramp"] });
    expect(useStore.getState().pendingSuggestions).toEqual(["Koma Ramp"]);
    // Waehrend die Anfrage noch offen ist, schickt ein zweiter Klick nichts Neues.
    useStore.getState().requestCardSuggestions("Koma Ramp", ["ramp"]);
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("cardSuggestions raeumt die offene Anfrage weg", () => {
    useStore.getState().requestCardSuggestions("Koma Ramp", ["ramp"]);
    useStore.getState().apply(suggestions("Koma Ramp"));
    expect(useStore.getState().pendingSuggestions).toEqual([]);
  });

  it("ein vorliegender Stand haelt requestCardSuggestions NICHT ab (Befund 3: der \"neu laden\"-Knopf "
    + "muss nach einem Reimport erneut fragen duerfen)", () => {
    useStore.getState().apply(suggestions("Koma Ramp"));
    useStore.getState().requestCardSuggestions("Koma Ramp", ["ramp"]);
    expect(send).toHaveBeenCalledWith({ type: "suggestCards", deck: "Koma Ramp", roles: ["ramp"] });
  });

  it("ein Fehler der Bridge gibt eine offene suggestCards-Anfrage wieder frei (Befund 3)", () => {
    useStore.getState().requestCardSuggestions("gibt es nicht", ["ramp"]);
    expect(useStore.getState().pendingSuggestions).toEqual(["gibt es nicht"]);
    useStore.getState().apply({ type: "error", text: "Kartenvorschläge gibt es nicht: unbekanntes Deck" });
    expect(useStore.getState().pendingSuggestions).toEqual([]);
    // Der Knopf darf danach wieder anfragen - vorher blieb er (rein lokaler useState) fuer immer auf "lädt …".
    useStore.getState().requestCardSuggestions("gibt es nicht", ["ramp"]);
    expect(send).toHaveBeenCalledTimes(2);
  });

  // Befund 7: store.test.ts kannte cardStats bislang nicht - genau der Fall, den der Kommentar an
  // pendingCards in store.ts beschwoert (ein error der Bridge muss die offene Anfrage wegraeumen, sonst
  // haengt der Knopf in DeckCards.tsx fuer immer auf "lädt …"), war ungeprueft. Aufbau 1:1 wie bei
  // cardSuggestions/pendingSuggestions oben.
  const cardStats = (deck: string): CardStatsMsg => ({
    type: "cardStats", deck, games: 9, withCardData: 9, enough: true, cards: [],
  });

  it("cardStats merkt die Antwort je Deckname", () => {
    const s = reduce(initialState, cardStats("Koma Ramp"));
    expect(s.cardStats["Koma Ramp"]).toEqual(cardStats("Koma Ramp"));
    // Eine zweite Antwort fuer ein anderes Deck ersetzt den ersten Stand nicht (dasselbe Muster wie
    // deckAnalysis/cardSuggestions oben).
    const zweite = reduce(s, cardStats("Krenko Goblins"));
    expect(Object.keys(zweite.cardStats).sort()).toEqual(["Koma Ramp", "Krenko Goblins"]);
    expect(zweite.cardStats["Koma Ramp"]).toEqual(cardStats("Koma Ramp"));
  });

  it("requestDeckCards fragt einmal je Deck und merkt sich die offene Anfrage", () => {
    useStore.getState().requestDeckCards("Koma Ramp");
    expect(send).toHaveBeenCalledWith({ type: "deckCards", deck: "Koma Ramp" });
    expect(useStore.getState().pendingCards).toEqual(["Koma Ramp"]);
    // Waehrend die Anfrage noch offen ist, schickt ein zweiter Klick nichts Neues.
    useStore.getState().requestDeckCards("Koma Ramp");
    expect(send).toHaveBeenCalledTimes(1);
  });

  it("cardStats raeumt die offene Anfrage weg", () => {
    useStore.getState().requestDeckCards("Koma Ramp");
    useStore.getState().apply(cardStats("Koma Ramp"));
    expect(useStore.getState().pendingCards).toEqual([]);
  });

  it("ein vorliegender Stand haelt requestDeckCards NICHT ab (der \"neu laden\"-Knopf muss nach einer "
    + "weiteren Partie erneut fragen duerfen)", () => {
    useStore.getState().apply(cardStats("Koma Ramp"));
    useStore.getState().requestDeckCards("Koma Ramp");
    expect(send).toHaveBeenCalledWith({ type: "deckCards", deck: "Koma Ramp" });
  });

  it("ein Fehler der Bridge gibt eine offene deckCards-Anfrage wieder frei (Befund 7)", () => {
    useStore.getState().requestDeckCards("gibt es nicht");
    expect(useStore.getState().pendingCards).toEqual(["gibt es nicht"]);
    useStore.getState().apply({ type: "error", text: "Kartentabelle gibt es nicht: unbekanntes Deck" });
    expect(useStore.getState().pendingCards).toEqual([]);
    // Der Knopf darf danach wieder anfragen - vorher blieb er fuer immer auf "lädt …" (derselbe Fehler
    // wie einst Befund 3 bei den Kartenvorschlaegen).
    useStore.getState().requestDeckCards("gibt es nicht");
    expect(send).toHaveBeenCalledTimes(2);
  });
});

describe("store: Update-Hinweis (Aufgabe 6)", () => {
  beforeEach(() => {
    useStore.setState({ ...initialState });
    vi.mocked(send).mockClear();
  });

  const version = { type: "version" as const, current: "1.40.0", latest: "1.41.0", url: "https://x", sha256: "abc", notes: "" };

  it("version wird uebernommen", () => {
    const s = reduce(initialState, version);
    expect(s.version).toEqual(version);
  });

  it("updateState wird uebernommen", () => {
    const updateState = { type: "updateState" as const, state: "pruefen" as const, text: "" };
    const s = reduce(initialState, updateState);
    expect(s.updateState).toEqual(updateState);
  });

  it("requestUpdate schickt applyUpdate und zeigt schon vorher \"laden\" an - die Bridge meldet diesen "
    + "Zustand zwar praktisch sofort, aber der Knopf soll nicht bis dahin anklickbar bleiben", () => {
    useStore.getState().requestUpdate();
    expect(send).toHaveBeenCalledWith({ type: "applyUpdate" });
    expect(useStore.getState().updateState).toEqual({ type: "updateState", state: "laden", text: "" });
  });

  it("dismissUpdate blendet nur im Store-Zustand aus, ohne zu speichern - ein weiterer Aufruf ist "
    + "wirkungslos (kein Fehler), initialState startet unberuehrt", () => {
    expect(initialState.updateDismissed).toBe(false);
    useStore.getState().dismissUpdate();
    expect(useStore.getState().updateDismissed).toBe(true);
    useStore.getState().dismissUpdate();
    expect(useStore.getState().updateDismissed).toBe(true);
  });
});

describe("store: sparring", () => {
  beforeEach(() => {
    useStore.setState({ ...initialState, sparring: undefined });
    vi.mocked(send).mockClear();
  });

  it("sparringProgress uebernimmt den Stand der Bridge", () => {
    const s = reduce(initialState, {
      type: "sparringProgress", done: 3, total: 5, current: "Koma Ramp", errors: [], running: true,
    });
    expect(s.sparring).toEqual({ running: true, done: 3, total: 5, current: "Koma Ramp", errors: [] });
  });

  it("fehlendes current wird null, Fehler und das Ende des Laufs kommen mit", () => {
    const s = reduce(initialState, {
      type: "sparringProgress", done: 5, total: 5, errors: ["Koma Ramp: Kindprozess: exit=1"], running: false,
    });
    expect(s.sparring).toEqual({
      running: false, done: 5, total: 5, current: null, errors: ["Koma Ramp: Kindprozess: exit=1"],
    });
  });

  it("startSparring schickt sparringStart und zeigt sofort einen laufenden Lauf", () => {
    useStore.getState().startSparring("World-Eater", 20);
    expect(send).toHaveBeenCalledWith({ type: "sparringStart", deck: "World-Eater", games: 20 });
    expect(useStore.getState().sparring).toEqual({
      running: true, done: 0, total: 20, current: null, errors: [], starting: true,
    });
    // Die erste Meldung der Bridge ersetzt den Startzustand samt "starting".
    useStore.getState().apply({ type: "sparringProgress", done: 0, total: 20, current: "Koma Ramp", errors: [], running: true });
    expect(useStore.getState().sparring?.starting).toBeUndefined();
  });

  it("ein Fehler vor der ersten Meldung raeumt den Startzustand weg", () => {
    useStore.getState().startSparring("World-Eater", 5);
    useStore.getState().apply({ type: "error", text: "Sparring: keine Gegner im Bracket 3: Bracket setzen oder Decks importieren" });
    expect(useStore.getState().sparring).toBeUndefined();
  });

  it("ein Fehler waehrend eines laufenden Laufs laesst den Fortschritt stehen", () => {
    useStore.getState().apply({ type: "sparringProgress", done: 2, total: 5, current: "Koma Ramp", errors: [], running: true });
    useStore.getState().apply({ type: "error", text: "Partie weg: unbekannte Partie" });
    expect(useStore.getState().sparring?.done).toBe(2);
  });

  it("cancelSparring schickt sparringCancel", () => {
    useStore.getState().cancelSparring();
    expect(send).toHaveBeenCalledWith({ type: "sparringCancel" });
  });
});

describe("sparringOpponents", () => {
  const d = (name: string, bracket: number | null): DeckInfo => ({ name, commanders: [], bracket });
  const three = [d("A", 3), d("B", 3), d("C", 3), d("D", 3), d("E", 5)];

  it("zieht aus demselben Bracket, ohne das eigene Deck", () => {
    expect(sparringOpponents(three, "A")).toEqual({ saved: true, bracket: 3, names: ["B", "C", "D"], widened: false });
  });

  it("weniger als drei im eigenen Bracket -> Bracket ±1", () => {
    const decks = [d("A", 3), d("B", 3), d("C", 2), d("D", 4), d("E", 1)];
    expect(sparringOpponents(decks, "A")).toEqual({ saved: true, bracket: 3, names: ["B", "C", "D"], widened: true });
  });

  it("kein Gegner im Bracket -> leere Liste (die Bridge lehnt den Start ab)", () => {
    expect(sparringOpponents([d("A", 3), d("B", 1)], "A")).toEqual({ saved: true, bracket: 3, names: [], widened: true });
  });

  it("Deck ohne Bracket spielt gegen die Decks ohne Bracket, ohne Erweiterung", () => {
    const decks = [d("A", null), d("B", null), d("C", 3)];
    expect(sparringOpponents(decks, "A")).toEqual({ saved: true, bracket: null, names: ["B"], widened: false });
  });

  it("ein nicht gespeichertes Deck (Precon, geloescht) kann kein Sparring", () => {
    expect(sparringOpponents(three, "Precon XY")).toEqual({ saved: false, bracket: null, names: [], widened: false });
    expect(sparringOpponents(three, undefined)).toEqual({ saved: false, bracket: null, names: [], widened: false });
  });
});
