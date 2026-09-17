import { create } from "zustand";
import type { Choice, Inbound, Snapshot } from "./protocol";

export interface AppState {
  screen: "lobby" | "table";
  precons: string[];
  state?: Snapshot;
  choice?: Choice;
  log: string[];
  winner?: string | null;
}

export const initialState: AppState = { screen: "lobby", precons: [], log: [] };

const LOG_MAX = 500;

/** Reine Übergangsfunktion – testbar ohne Socket oder React. */
export function reduce(s: AppState, m: Inbound): AppState {
  switch (m.type) {
    case "lobby":
      return { ...s, precons: m.precons, screen: s.state ? s.screen : "lobby" };
    case "state":
      return { ...s, state: m, screen: "table" };
    case "choice":
      return { ...s, choice: m };
    case "log":
      return { ...s, log: [...s.log, m.text].slice(-LOG_MAX) };
    case "gameOver":
      return { ...s, winner: m.winner ?? null };
    case "error":
      return { ...s, log: [...s.log, "⚠ " + m.text].slice(-LOG_MAX) };
    default:
      return s;
  }
}

interface Store extends AppState {
  apply: (m: Inbound) => void;
  clearChoice: () => void;
  backToLobby: () => void;
}

export const useStore = create<Store>((set) => ({
  ...initialState,
  apply: (m) => set((s) => reduce(s, m)),
  clearChoice: () => set({ choice: undefined }),
  backToLobby: () => set({ screen: "lobby", state: undefined, winner: undefined, choice: undefined }),
}));
