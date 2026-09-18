import { create } from "zustand";
import type { Choice, Inbound, Snapshot } from "./protocol";

export interface AppState {
  screen: "lobby" | "table";
  precons: string[];
  decks: string[];
  state?: Snapshot;
  choices: Choice[];
  log: string[];
  winner?: string | null;
  hover?: number;
}

export const initialState: AppState = { screen: "lobby", precons: [], decks: [], choices: [], log: [] };

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
    case "state":
      return { ...s, state: m, screen: "table" };
    case "choice":
      return { ...s, choices: addChoice(s.choices, m) };
    case "log":
      return { ...s, log: [...s.log, m.text].slice(-LOG_MAX) };
    case "gameOver":
      return { ...s, winner: m.winner ?? null, choices: [] };
    case "error":
      return { ...s, log: [...s.log, "⚠ " + m.text].slice(-LOG_MAX) };
    default:
      return s;
  }
}

interface Store extends AppState {
  apply: (m: Inbound) => void;
  clearChoice: (id: number) => void;
  backToLobby: () => void;
  setHover: (id?: number) => void;
}

export const useStore = create<Store>((set) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: (id) => set((s) => ({ choices: s.choices.filter((c) => c.id !== id) })),
  backToLobby: () => set({ screen: "lobby", state: undefined, winner: undefined, choices: [] }),
  setHover: (id) => set({ hover: id }),
}));
