import { create } from "zustand";
import type { Choice, Inbound, Snapshot } from "./protocol";

export interface LogEntry { text: string; kind?: string; card?: number; warn?: boolean; id?: number }

export interface AppState {
  screen: "lobby" | "table";
  precons: string[];
  decks: string[];
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
}

export const initialState: AppState = {
  screen: "lobby", precons: [], decks: [], choices: [], log: [], hiddenKinds: ["MANA", "PHASE"], lastLogId: 0,
};

const LOG_MAX = 500;

/** Fügt eine choice ein (oder ersetzt dieselbe id), aufsteigend nach id sortiert. */
function addChoice(choices: Choice[], c: Choice): Choice[] {
  return [...choices.filter((x) => x.id !== c.id), c].sort((a, b) => a.id - b.id);
}

/** Reine Übergangsfunktion – testbar ohne Socket oder React. */
export function reduce(s: AppState, m: Inbound): AppState {
  switch (m.type) {
    case "lobby":
      return { ...s, precons: m.precons, decks: m.decks ?? [], screen: s.state ? s.screen : "lobby" };
    case "state": {
      // Spielstart nach der Lobby (erster Snapshot, s.state war noch undefined): Log der Vorpartie leeren.
      const isNewMatch = m.turn === 0 && s.state === undefined;
      const freshLog = isNewMatch ? [] : s.log;
      const lastLogId = isNewMatch ? 0 : s.lastLogId;
      return { ...s, state: m, screen: "table", log: freshLog, lastLogId };
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
      return { ...s, winner: m.winner ?? null, choices: [] };
    case "error":
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
}

export const useStore = create<Store>((set) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: (id) => set((s) => ({ choices: s.choices.filter((c) => c.id !== id) })),
  backToLobby: () => set({ screen: "lobby", state: undefined, winner: undefined, choices: [] }),
  setHover: (id) => set({ hover: id }),
  toggleKind: (kind) => set((s) => ({
    hiddenKinds: s.hiddenKinds.includes(kind) ? s.hiddenKinds.filter((k) => k !== kind) : [...s.hiddenKinds, kind],
  })),
}));
