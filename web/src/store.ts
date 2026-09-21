import { create } from "zustand";
import type { Choice, DeckInfo, Inbound, Snapshot, StartGame } from "./protocol";
import { recordResult, type Series, startSeries } from "./series";

export interface LogEntry { text: string; kind?: string; card?: number; warn?: boolean; id?: number }

export interface AppState {
  screen: "lobby" | "table";
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
}

export const initialState: AppState = {
  screen: "lobby", precons: [], decks: [], choices: [], log: [], hiddenKinds: ["MANA", "PHASE"], lastLogId: 0,
  aiModes: ["standard", "hybrid", "sim"], aiProfiles: ["Default"], aiTimeout: 5, bestOf: 0,
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
        screen: s.state ? s.screen : "lobby",
      };
    case "state": {
      // Spielstart nach der Lobby (erster Snapshot, s.state war noch undefined) oder Replay aus dem
      // Spielende-Overlay ("Nochmal spielen"/"Neue Serie" - s.state ist da noch die alte Partie, aber
      // winner ist gesetzt): Log der Vorpartie leeren und winner zuruecksetzen.
      const isNewMatch = m.turn === 0 && (s.state === undefined || s.winner !== undefined);
      const freshLog = isNewMatch ? [] : s.log;
      const lastLogId = isNewMatch ? 0 : s.lastLogId;
      return { ...s, state: m, screen: "table", log: freshLog, lastLogId, winner: isNewMatch ? undefined : s.winner };
    }
    case "choice":
      return { ...s, choices: addChoice(s.choices, m) };
    case "log": {
      // Reconnect-Replay: Zeilen mit einer id, die schon uebernommen wurde, sind Duplikate - verwerfen.
      if (m.id !== undefined && m.id <= s.lastLogId) return s;
      const lastLogId = m.id !== undefined ? m.id : s.lastLogId;
      return { ...s, log: [...s.log, { text: m.text, kind: m.kind, card: m.card, id: m.id }].slice(-LOG_MAX), lastLogId };
    }
    case "gameOver":
      return { ...s, winner: m.winner ?? null, choices: [], series: s.series ? recordResult(s.series, m.winner ?? null) : s.series };
    case "error":
      if (m.text === INCORRECT_ACTION_TEXT) return { ...s, toast: { text: m.text, n: (s.toast?.n ?? 0) + 1 } };
      return { ...s, log: [...s.log, { text: "⚠ " + m.text, warn: true }].slice(-LOG_MAX) };
    default:
      return s;
  }
}

interface Store extends AppState {
  apply: (m: Inbound) => void;
  clearChoice: (id: number) => void;
  backToLobby: () => void;
  setHover: (id?: number) => void;
  toggleKind: (kind: string) => void;
  clearToast: () => void;
  /** Vor dem Senden von startGame aufrufen: merkt die Nachricht und fuehrt die Serie fort bzw. beginnt eine neue. */
  noteStart: (msg: StartGame) => void;
  resetSeries: () => void;
  setBestOf: (n: number) => void;
}

export const useStore = create<Store>((set) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: (id) => set((s) => ({ choices: s.choices.filter((c) => c.id !== id) })),
  backToLobby: () => set({ screen: "lobby", state: undefined, winner: undefined, choices: [] }),
  setHover: (id) => set({ hover: id }),
  clearToast: () => set({ toast: undefined }),
  noteStart: (msg) => set((s) => ({ lastStart: msg, series: startSeries(s.series, msg) })),
  resetSeries: () => set({ series: undefined }),
  setBestOf: (n) => set({ bestOf: n }),
  toggleKind: (kind) => set((s) => ({
    hiddenKinds: s.hiddenKinds.includes(kind) ? s.hiddenKinds.filter((k) => k !== kind) : [...s.hiddenKinds, kind],
  })),
}));
