import { create } from "zustand";
import type { Choice, Inbound, Snapshot } from "./protocol";

export interface LogEntry { text: string; kind?: string; card?: number; warn?: boolean }

export interface AppState {
  screen: "lobby" | "table";
  precons: string[];
  decks: string[];
  state?: Snapshot;
  choices: Choice[];
  log: LogEntry[];
  /** Kategorien (LogEntry.kind), die im Log-Panel ausgeblendet sind; MANA/PHASE standardmäßig gedimmt weg. */
  hiddenKinds: string[];
  winner?: string | null;
  hover?: number;
}

export const initialState: AppState = {
  screen: "lobby", precons: [], decks: [], choices: [], log: [], hiddenKinds: ["MANA", "PHASE"],
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
      const freshLog = m.turn === 0 && s.state === undefined ? [] : s.log;
      return { ...s, state: m, screen: "table", log: freshLog };
    }
    case "choice":
      return { ...s, choices: addChoice(s.choices, m) };
    case "log":
      return { ...s, log: [...s.log, { text: m.text, kind: m.kind, card: m.card }].slice(-LOG_MAX) };
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
