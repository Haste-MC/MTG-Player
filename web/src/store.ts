import { create } from "zustand";
import type { ArchidektEntry, ArchidektProgress, CardStatsMsg, CardSuggestionsMsg, Choice, DeckAnalysis, DeckInfo, Inbound, MatchRecord, Snapshot, StartGame } from "./protocol";
import { recordResult, type Series, startSeries } from "./series";
import { send } from "./ws";

export interface LogEntry { text: string; kind?: string; card?: number; warn?: boolean; id?: number }

/** Stand eines Sparring-Laufs im Board (siehe SparringProgress). `starting` ist der Zustand zwischen dem
 *  Klick auf „Sparring starten“ und der ersten Fortschrittsmeldung der Bridge: Zahlen sind dann nur die
 *  eigene Annahme (done 0, total = gewaehlte Partienzahl), damit der Knopf nicht erst nach dem Laden von
 *  Forge im Kindprozess reagiert. */
export interface SparringState {
  running: boolean; done: number; total: number; current?: string | null; errors: string[]; starting?: boolean;
}

/** Ab so vielen Gegnern im eigenen Bracket wird nicht auf ±1 erweitert (Bridge: SparringOpponents.MIN_SAME_BRACKET). */
export const MIN_SAME_BRACKET = 3;

/** Wen das Sparring fuer `deck` ziehen wuerde - dieselbe Regel wie SparringOpponents auf der Bridge
 *  (Spec Stueck 3 §2), hier nur fuer den Hinweis unter dem Knopf. Die Wahrheit bleibt die Bridge: sie
 *  liest die Deckdateien, dieser Nachbau die lobby-Liste.
 *
 *  `saved` ist false, wenn `deck` gar kein gespeichertes Deck ist (ein Precon oder ein geloeschtes Deck
 *  aus einer alten Partie) - dann geht kein Sparring. `widened` heisst: im eigenen Bracket sind es
 *  weniger als MIN_SAME_BRACKET, gezogen wird darum aus Bracket ±1. Ein Deck ohne Bracket spielt gegen
 *  die Decks ohne Bracket, ohne jede Erweiterung. */
export interface SparringOpponents { saved: boolean; bracket: number | null; names: string[]; widened: boolean }

export function sparringOpponents(decks: DeckInfo[], deck?: string): SparringOpponents {
  const own = decks.find((d) => d.name === deck);
  if (!own) return { saved: false, bracket: null, names: [], widened: false };
  const bracket = own.bracket ?? null;
  const pick = (min: number, max: number) =>
    decks.filter((d) => d.name !== deck && d.bracket != null && d.bracket >= min && d.bracket <= max).map((d) => d.name);
  if (bracket === null) {
    return { saved: true, bracket: null, widened: false, names: decks.filter((d) => d.name !== deck && d.bracket == null).map((d) => d.name) };
  }
  const same = pick(bracket, bracket);
  if (same.length >= MIN_SAME_BRACKET) return { saved: true, bracket, names: same, widened: false };
  return { saved: true, bracket, names: pick(bracket - 1, bracket + 1), widened: true };
}

export interface AppState {
  screen: "lobby" | "table" | "stats";
  precons: DeckInfo[];
  decks: DeckInfo[];
  /** Von der Bridge angebotene KI-Modi/-Profile und die Standard-Bedenkzeit (Sekunden) - siehe Lobby.tsx. */
  aiModes: string[];
  aiProfiles: string[];
  aiTimeout: number;
  state?: Snapshot;
  choices: Choice[];
  log: LogEntry[];
  /** Kategorien (LogEntry.kind), die im Log-Panel ausgeblendet sind; MANA/PHASE standardmäßig gedimmt weg. */
  hiddenKinds: string[];
  /** höchste bereits übernommene LogLine.id dieser Partie – macht den Reconnect-Replay idempotent
   *  (Zeilen mit id <= lastLogId sind schon im Log und werden verworfen). */
  lastLogId: number;
  winner?: string | null;
  hover?: number;
  /** Kurzer Hinweis am Prompt (z. B. Forges flashIncorrectAction) statt einer Log-Zeile; n zaehlt hoch,
   *  damit ein wiederholter Hinweis den Ausblend-Timer neu startet (siehe Prompt.tsx). */
  toast?: { text: string; n: number };
  /** Zuletzt gesendete startGame-Nachricht ("Revanche" schickt sie unveraendert erneut). */
  lastStart?: StartGame;
  /** Laufende Serie ueber dieselbe Deck-Konstellation, siehe series.ts; undefined vor dem ersten Start. */
  series?: Series;
  /** Serienlaenge aus der Lobby (0 = keine Serie). */
  bestOf: number;
  /** Sekunden bis zum automatischen Start des naechsten Spiels der Serie (Spielende-Dialog,
   *  siehe nextSeriesStep in series.ts); undefined = kein Countdown. Laeuft nur, waehrend der Dialog
   *  offen ist - Table.tsx raeumt den Interval-Timer beim Schliessen/Unmount auf. */
  seriesCountdown?: number;
  /** true zwischen noteStart() und dem naechsten Snapshot: der naechste turn-0-state ist sicher ein
   *  Spielstart, kein Reconnect-Replay der alten Partie (die z. B. bei einem sofortigen Concede selbst
   *  bei turn 0 geendet haben kann). Wird bei jedem Snapshot und bei jedem error zurueckgesetzt. */
  expectNewMatch: boolean;
  /** Archidekt-Reiter der Lobby: zuletzt geladene Deckliste eines Kontos und der Fortschritt eines
   *  laufenden archidektImport. decks/progress fehlen, bevor je eine Antwort kam; loading ist waehrend
   *  einer laufenden archidektList-Anfrage true, ein "error" beendet es (unabhaengig vom Grund). */
  archidekt: { username?: string; decks?: ArchidektEntry[]; loading: boolean; progress?: ArchidektProgress };
  /** Laeuft die Bridge gerade laenger als 3 s ohne sichtbare Aktivitaet, steht hier die bisherige Dauer
   *  und (wenn bekannt) der Sitz mit Prioritaet - siehe Thinking und components/Thinking.tsx. Die Bridge
   *  schickt seconds: 0, sobald wieder etwas passiert; dann faellt das Feld weg. Auch jeder Snapshot
   *  loescht es: ein neuer Zustand ist sichtbare Aktivitaet, die Anzeige waere sonst kurz falsch. */
  thinking?: { player?: number | null; seconds: number };
  /** Partienliste der Bridge (siehe matches-Nachricht) - bei Verbindung und nach jeder Aenderung
   *  komplett ersetzt, nie zusammengefuehrt. Grundlage fuer matchStats.ts. Die Bridge schickt hoechstens
   *  die letzten 300 Datensaetze und laesst die Zeitachse weg (sie kommt einzeln per matchDetail). */
  matches: MatchRecord[];
  /** Gespeicherte Partien insgesamt (matches-Nachricht, Feld total). Ist der Wert groesser als
   *  matches.length, zeigt der Statistik-Screen nur den juengeren Teil. Eine Bridge vor Runde A schickt
   *  kein total - dann ist es die Laenge der Liste, denn mehr weiss der Client nicht. */
  matchesTotal: number;
  /** Vollstaendige Partien (mit Zeitachse) je Id, einzeln per matchDetail nachgeladen - die Liste in
   *  `matches` traegt die Zeitachse nicht. Was einmal da ist, bleibt gemerkt: die Zeitachse einer
   *  beendeten Partie aendert sich nicht mehr, ein zweites Aufklappen muss also nicht erneut fragen.
   *  Eine geloeschte Partie faellt mit der naechsten matches-Nachricht wieder heraus. */
  matchDetails: Record<string, MatchRecord>;
  /** Deckanalysen je Deckname (analyzeDeck/deckAnalysis) - sie haengen am Deckinhalt, nicht an Partien,
   *  und werden darum ueber die ganze Sitzung gemerkt. */
  deckAnalyses: Record<string, DeckAnalysis>;
  /** Kartenvorschlaege je Deckname (suggestCards/cardSuggestions, Stueck 2) - dasselbe Muster wie
   *  deckAnalyses: sie haengen am Deckinhalt und den fehlenden Rollen, nicht an einer einzelnen Partie,
   *  und bleiben darum ueber die ganze Sitzung gemerkt. Die Anzeige selbst folgt erst noch. */
  suggestions: Record<string, CardSuggestionsMsg>;
  /** Kartentabelle je Deckname (deckCards/cardStats, Stueck 6) - dasselbe Muster wie suggestions: haengt
   *  am Deckinhalt und den gespeicherten Partien, nicht an einer einzelnen Partie, und bleibt darum ueber
   *  die ganze Sitzung gemerkt. */
  cardStats: Record<string, CardStatsMsg>;
  /** Angefragt, aber noch ohne Antwort: verhindert, dass dieselbe Partie bzw. dasselbe Deck bei jedem
   *  Klick erneut angefragt wird. Ein error der Bridge raeumt beide Listen leer (welche Anfrage
   *  fehlschlug, sagt die Fehlermeldung nicht) - der naechste Versuch darf sonst nie mehr fragen. */
  pendingMatch: string[];
  pendingAnalysis: string[];
  /** Angefragt, aber noch ohne Antwort (suggestCards) - dasselbe Muster wie pendingMatch/pendingAnalysis
   *  (Befund 3): ohne das blieb der Knopf in Suggestions.tsx nach einem Fehler (unbekanntes Deck, jede
   *  Ausnahme auf der Bridge) fuer immer auf "lädt …" stehen, weil das vorher rein lokale useState eine
   *  fehlgeschlagene Anfrage nicht von einer noch offenen unterscheiden konnte. */
  pendingSuggestions: string[];
  /** Angefragt, aber noch ohne Antwort (deckCards, Stueck 6) - dasselbe Muster wie pendingSuggestions:
   *  ein error der Bridge muss den Eintrag hier raeumen, sonst haengt der Knopf in DeckCards.tsx fuer
   *  immer auf "lädt …" (derselbe Fehler wie einst Befund 3 bei den Kartenvorschlaegen). */
  pendingCards: string[];
  /** Laufendes bzw. zuletzt gelaufenes Sparring (Statistik-Board). undefined, solange in dieser Sitzung
   *  keines gestartet wurde und keine Fortschrittsmeldung kam; nach dem Lauf bleibt der letzte Stand mit
   *  running: false stehen (Partienzahl und Fehlerliste sind dann das Ergebnis). */
  sparring?: SparringState;
}

export const initialState: AppState = {
  screen: "lobby", precons: [], decks: [], choices: [], log: [], hiddenKinds: ["MANA", "PHASE"], lastLogId: 0,
  aiModes: ["standard", "hybrid", "sim"], aiProfiles: ["Default"], aiTimeout: 5, bestOf: 0, expectNewMatch: false,
  archidekt: { loading: false }, matches: [], matchesTotal: 0, matchDetails: {}, deckAnalyses: {},
  suggestions: {}, cardStats: {}, pendingMatch: [], pendingAnalysis: [], pendingSuggestions: [], pendingCards: [],
};

const LOG_MAX = 500;
/** Forges flashIncorrectAction kommt als error mit genau diesem Text (WebGuiGame) – ist kein Log-Ereignis. */
export const INCORRECT_ACTION_TEXT = "Das geht gerade nicht.";

/** Fügt eine choice ein (oder ersetzt dieselbe id), aufsteigend nach id sortiert. */
function addChoice(choices: Choice[], c: Choice): Choice[] {
  return [...choices.filter((x) => x.id !== c.id), c].sort((a, b) => a.id - b.id);
}

/** Reine Übergangsfunktion – testbar ohne Socket oder React. */
export function reduce(s: AppState, m: Inbound): AppState {
  switch (m.type) {
    case "lobby":
      return {
        ...s,
        precons: m.precons,
        decks: m.decks ?? [],
        aiModes: m.aiModes ?? initialState.aiModes,
        aiProfiles: m.aiProfiles ?? initialState.aiProfiles,
        aiTimeout: m.aiTimeout ?? initialState.aiTimeout,
        // Der Statistik-Screen bleibt stehen: eine frische lobby-Nachricht (Reconnect, Deck-Import)
        // darf den Blick auf die Partien nicht wegreissen.
        screen: s.state || s.screen === "stats" ? s.screen : "lobby",
      };
    case "state": {
      // Spielstart nach der Lobby (erster Snapshot, s.state war noch undefined) oder Replay aus dem
      // Spielende-Overlay ("Nochmal spielen"/"Neue Serie" - noteStart hat expectNewMatch gesetzt): Log der
      // Vorpartie leeren und winner zuruecksetzen. winner selbst ist als Indiz ungeeignet: ein turn-0-state
      // kann nach einem sofortigen Concede per Reconnect erneut ankommen, waehrend das Overlay noch offen
      // ist - dann ist es kein Spielstart, sondern ein Replay derselben (beendeten) Partie.
      const isNewMatch = m.turn === 0 && (s.state === undefined || s.expectNewMatch);
      const freshLog = isNewMatch ? [] : s.log;
      const lastLogId = isNewMatch ? 0 : s.lastLogId;
      return {
        // Wie im lobby-Zweig: der Statistik-Screen bleibt stehen. Ein state-Schnappschuss kommt auch
        // ohne Zutun (Reconnect, requestState der laufenden Partie) und duerfte den Blick auf die
        // Partien nicht wegreissen - nur ein Spielstart holt einen wieder an den Tisch.
        ...s, state: m, screen: s.screen === "stats" && !isNewMatch ? "stats" : "table",
        log: freshLog, lastLogId, expectNewMatch: false, thinking: undefined,
        winner: isNewMatch ? undefined : s.winner,
      };
    }
    case "choice":
      return { ...s, choices: addChoice(s.choices, m) };
    case "log": {
      // Reconnect-Replay: Zeilen mit einer id, die schon uebernommen wurde, sind Duplikate - verwerfen.
      if (m.id !== undefined && m.id <= s.lastLogId) return s;
      const lastLogId = m.id !== undefined ? m.id : s.lastLogId;
      return { ...s, log: [...s.log, { text: m.text, kind: m.kind, card: m.card, id: m.id }].slice(-LOG_MAX), lastLogId };
    }
    case "thinking":
      // seconds: 0 ist das Ende der Stille - Feld loeschen statt eine "0 s"-Zeile stehen zu lassen.
      return m.seconds > 0 ? { ...s, thinking: { player: m.player, seconds: m.seconds } } : { ...s, thinking: undefined };
    case "gameOver":
      // thinking mit raus: die Partie ist vorbei, es rechnet niemand mehr. Die Bridge schickt zwar beim
      // Schliessen des Tickers ihr seconds: 0 hinterher, aber ein Reconnect mitten im Spielende-Overlay
      // liesse sonst "KI 2 denkt ... 31 s" unter dem Ergebnis stehen. seriesCountdown mit raus: ein neuer
      // Spielende-Dialog faengt immer frisch an (Table.tsx startet ihn per nextSeriesStep neu) - sonst
      // koennte ein alter Countdown-Stand kurz aufblitzen, bevor der Effekt ihn neu setzt.
      return {
        ...s, winner: m.winner ?? null, choices: [], thinking: undefined, seriesCountdown: undefined,
        series: s.series ? recordResult(s.series, m.winner ?? null) : s.series,
      };
    case "error": {
      // Ein fehlgeschlagenes startGame (z. B. Deckfehler) darf expectNewMatch nicht scharf lassen - sonst
      // wuerde der naechste turn-0-Snapshot (Reconnect der alten Partie) faelschlich als Spielstart gelten.
      // Ein error beendet auch ein laufendes archidektList (unabhaengig davon, ob er davon stammt).
      const archidekt = { ...s.archidekt, loading: false };
      // Offene matchDetail-/analyzeDeck-/suggestCards-Anfragen gelten nach einem Fehler als erledigt: die
      // Meldung sagt nicht, welche gemeint war, und eine haengende Anfrage wuerde jeden weiteren Versuch
      // blockieren (Befund 3: genau das liess den "Vorschläge laden"-Knopf vorher auf "lädt …" haengen).
      const pending = { pendingMatch: [], pendingAnalysis: [], pendingSuggestions: [], pendingCards: [] };
      // Ein Fehler zwischen Klick und erster Fortschrittsmeldung ("keine Gegner im Bracket 3",
      // "Sparring läuft noch") beendet den Startzustand - sonst stuende die Fortschrittszeile fuer
      // immer bei 0. Laeuft anderswo wirklich ein Lauf, stellt ihn die naechste Fortschrittsmeldung
      // wieder her. Einen laufenden Lauf ruehrt ein Fehler nicht an: er meldet sein Ende selbst.
      const sparring = s.sparring?.starting ? undefined : s.sparring;
      if (m.text === INCORRECT_ACTION_TEXT) return { ...s, toast: { text: m.text, n: (s.toast?.n ?? 0) + 1 }, expectNewMatch: false, archidekt, sparring, ...pending };
      return { ...s, log: [...s.log, { text: "⚠ " + m.text, warn: true }].slice(-LOG_MAX), expectNewMatch: false, archidekt, sparring, ...pending };
    }
    case "archidektDecks":
      return { ...s, archidekt: { username: m.username, decks: m.decks, loading: false } };
    case "archidektProgress":
      return { ...s, archidekt: { ...s.archidekt, progress: m } };
    case "sparringProgress":
      // Die Bridge ist die Wahrheit ueber den Lauf: ihre Zahlen ersetzen den Startzustand komplett
      // (auch total - ein Lauf kann mit weniger Partien gestartet worden sein, als der Knopf schickte).
      return { ...s, sparring: { running: m.running, done: m.done, total: m.total, current: m.current ?? null, errors: m.errors } };
    case "matches": {
      // Geloeschte Partien auch aus den gemerkten Details werfen - sonst zeigt ein spaeterer Datensatz
      // mit derselben Id (theoretisch) die alte Zeitachse, und der Speicher waechst ohne Grund.
      const ids = new Set(m.matches.map((r) => r.id));
      const kept = Object.entries(s.matchDetails).filter(([id]) => ids.has(id));
      return {
        ...s, matches: m.matches, matchesTotal: m.total ?? m.matches.length,
        matchDetails: kept.length === Object.keys(s.matchDetails).length ? s.matchDetails : Object.fromEntries(kept),
      };
    }
    case "match":
      // Antwort auf matchDetail: der vollstaendige Datensatz EINER Partie (mit Zeitachse). Die Liste in
      // s.matches bleibt, wie sie ist - sie ist der Stand der Bridge, das Detail nur ihr Anhang.
      return {
        ...s, matchDetails: { ...s.matchDetails, [m.match.id]: m.match },
        pendingMatch: s.pendingMatch.filter((id) => id !== m.match.id),
      };
    case "deckAnalysis":
      return {
        ...s, deckAnalyses: { ...s.deckAnalyses, [m.deck]: m.analysis },
        pendingAnalysis: s.pendingAnalysis.filter((deck) => deck !== m.deck),
      };
    case "cardSuggestions":
      // Schluessel ist der Deckname, nicht die Rolle: das Board haelt so mehrere Decks nebeneinander,
      // und eine neue Anfrage fuer dasselbe Deck ersetzt den alten Stand komplett (wie bei deckAnalysis).
      return {
        ...s, suggestions: { ...s.suggestions, [m.deck]: m },
        pendingSuggestions: s.pendingSuggestions.filter((deck) => deck !== m.deck),
      };
    case "cardStats":
      // Dasselbe Muster wie cardSuggestions: Schluessel ist der Deckname, eine neue Anfrage ersetzt den
      // alten Stand komplett - so zeigt "neu laden" nach einer weiteren Partie die frischen Zahlen.
      return {
        ...s, cardStats: { ...s.cardStats, [m.deck]: m },
        pendingCards: s.pendingCards.filter((deck) => deck !== m.deck),
      };
    default:
      return s;
  }
}

interface Store extends AppState {
  apply: (m: Inbound) => void;
  clearChoice: (id: number) => void;
  /** Zurueck zur Lobby (aus dem Spielende-Overlay oder aus der Statistik) - verwirft den Tischzustand. */
  backToLobby: () => void;
  /** Statistik-Screen oeffnen (Knopf in der Lobby); die Partien liegen schon im Store. */
  openStats: () => void;
  setHover: (id?: number) => void;
  toggleKind: (kind: string) => void;
  clearToast: () => void;
  /** Vor dem Senden von startGame aufrufen: merkt die Nachricht und fuehrt die Serie fort bzw. beginnt eine neue. */
  noteStart: (msg: StartGame) => void;
  resetSeries: () => void;
  setBestOf: (n: number) => void;
  /** Startet den Countdown bis zum naechsten Serienspiel (Sekunden bis zum Auto-Start). */
  startSeriesCountdown: (seconds: number) => void;
  /** Eine Sekunde runter; bei 1 -> undefined statt eine "0 …" anzuzeigen (Table.tsx sendet dann sofort). */
  tickSeriesCountdown: () => void;
  /** "Serie beenden": bricht den Countdown sofort ab, ohne das naechste Spiel zu starten. */
  cancelSeriesCountdown: () => void;
  /** Setzt archidekt.loading und schickt archidektList - die Bridge antwortet mit archidektDecks/error. */
  requestArchidektList: (username: string) => void;
  /** Laedt eine Partie vollstaendig nach (matchDetail), wenn sie nicht schon vorliegt oder unterwegs ist. */
  requestMatchDetail: (id: string) => void;
  /** Fordert die Deckanalyse an (analyzeDeck), wenn sie nicht schon vorliegt oder unterwegs ist. */
  requestDeckAnalysis: (deck: string) => void;
  /** Fordert Kartenvorschlaege an (suggestCards, Befund 3) - anders als requestDeckAnalysis OHNE Sperre
   *  gegen einen schon vorliegenden Stand: der "neu laden"-Knopf muss nach einem Reimport erneut fragen
   *  duerfen, auch wenn suggestions[deck] noch den alten Stand traegt. Nur eine bereits laufende Anfrage
   *  fuer dasselbe Deck wird nicht doppelt geschickt. */
  requestCardSuggestions: (deck: string, roles: string[]) => void;
  /** Fordert die Kartentabelle an (deckCards, Stueck 6) - dasselbe Muster wie requestCardSuggestions:
   *  OHNE Sperre gegen einen schon vorliegenden Stand, damit "neu laden" nach einer weiteren Partie
   *  erneut fragen darf. Nur eine bereits laufende Anfrage fuer dasselbe Deck wird nicht doppelt geschickt. */
  requestDeckCards: (deck: string) => void;
  /** Startet ein Sparring (sparringStart) und setzt den Startzustand - die Bridge uebernimmt mit ihrer
   *  ersten Fortschrittsmeldung. KI, Bedenkzeit und Zugdeckel bleiben die Voreinstellung der Bridge. */
  startSparring: (deck: string, games: number) => void;
  /** Bricht den laufenden Lauf ab (sparringCancel); die Bridge meldet das Ende mit running: false. */
  cancelSparring: () => void;
}

export const useStore = create<Store>((set, get) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: (id) => set((s) => ({ choices: s.choices.filter((c) => c.id !== id) })),
  backToLobby: () => set({
    screen: "lobby", state: undefined, winner: undefined, choices: [], thinking: undefined, seriesCountdown: undefined,
  }),
  openStats: () => set({ screen: "stats" }),
  setHover: (id) => set({ hover: id }),
  clearToast: () => set({ toast: undefined }),
  noteStart: (msg) => set((s) => ({ lastStart: msg, series: startSeries(s.series, msg), expectNewMatch: true })),
  resetSeries: () => set({ series: undefined }),
  setBestOf: (n) => set({ bestOf: n }),
  startSeriesCountdown: (seconds) => set({ seriesCountdown: seconds }),
  tickSeriesCountdown: () => set((s) => ({
    seriesCountdown: s.seriesCountdown === undefined || s.seriesCountdown <= 1 ? undefined : s.seriesCountdown - 1,
  })),
  cancelSeriesCountdown: () => set({ seriesCountdown: undefined }),
  toggleKind: (kind) => set((s) => ({
    hiddenKinds: s.hiddenKinds.includes(kind) ? s.hiddenKinds.filter((k) => k !== kind) : [...s.hiddenKinds, kind],
  })),
  requestArchidektList: (username) => {
    set((s) => ({ archidekt: { ...s.archidekt, loading: true } }));
    send({ type: "archidektList", username });
  },
  requestMatchDetail: (id) => {
    const s = get();
    if (s.matchDetails[id] || s.pendingMatch.includes(id)) return;
    set({ pendingMatch: [...s.pendingMatch, id] });
    send({ type: "matchDetail", id });
  },
  requestDeckAnalysis: (deck) => {
    const s = get();
    if (s.deckAnalyses[deck] || s.pendingAnalysis.includes(deck)) return;
    set({ pendingAnalysis: [...s.pendingAnalysis, deck] });
    send({ type: "analyzeDeck", deck });
  },
  requestCardSuggestions: (deck, roles) => {
    const s = get();
    if (s.pendingSuggestions.includes(deck)) return;
    set({ pendingSuggestions: [...s.pendingSuggestions, deck] });
    send({ type: "suggestCards", deck, roles });
  },
  requestDeckCards: (deck) => {
    const s = get();
    if (s.pendingCards.includes(deck)) return;
    set({ pendingCards: [...s.pendingCards, deck] });
    send({ type: "deckCards", deck });
  },
  startSparring: (deck, games) => {
    // Fehlerliste bewusst leer: der neue Lauf faengt bei null an, die Fehler des vorigen sind erledigt.
    set({ sparring: { running: true, done: 0, total: games, current: null, errors: [], starting: true } });
    send({ type: "sparringStart", deck, games });
  },
  cancelSparring: () => send({ type: "sparringCancel" }),
}));
