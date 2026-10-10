// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { Snapshot } from "../protocol";
import { initialState, useStore } from "../store";
import { send } from "../ws";
import Table from "./Table";

// Aufgabe 11: Gibt der Mensch in einer Teampartie auf, spielt der Partner weiter - der Tisch bleibt offen.
// Ist der EIGENE Sitz ausgeschieden (PlayerSnap.lost) und die Partie nicht zu Ende, zeigt die Fusszeile die
// Zuschauer-Leiste mit "End game" statt der Spieler-Leiste mit OK/Cancel/Concede. Alles andere bleibt.

vi.mock("../ws", () => ({ send: vi.fn() }));
// jsdom kennt scrollTo nicht, das Log (Log.tsx) ruft es beim Einhaengen.
Element.prototype.scrollTo = () => {};

const seat = (id: number, name: string, team: number | undefined, lost: boolean) => ({
  id, name, isAi: id !== 0, life: lost ? 0 : 40, commanderDamage: {}, hand: [], librarySize: 60,
  graveyard: [], exile: [], command: [], battlefield: [], manaPool: {}, hasPriority: false, lost,
  ...(team === undefined ? {} : { team }),
});

// Der Prompt ist absichtlich "lebendig" (OK und Cancel frei): ein ausgeschiedener Sitz darf ihn trotzdem
// nicht mehr als Bedienung anbieten.
const prompt = { seq: 3, message: "Play spells and abilities", okLabel: "Pass priority", cancelLabel: "Undo",
  okEnabled: true, cancelEnabled: true };

const snapshot = (over: Partial<Snapshot> & { players: ReturnType<typeof seat>[] }) => ({
  type: "state", turn: 5, phase: "MAIN1", activePlayer: 1, priorityPlayer: 1, me: 0, gameOver: false,
  stack: [], cards: {}, stops: { own: [], opp: [] }, fullControl: false, prompt, combat: [],
  ...over,
} as unknown as Snapshot);

const team = (meLost: boolean) => snapshot({
  players: [seat(0, "You", 1, meLost), seat(1, "Partner", 1, false), seat(2, "AI 2", 2, false), seat(3, "AI 3", 2, false)],
});

const zeige = (state: Snapshot, extra: Record<string, unknown> = {}) => {
  useStore.setState({ ...initialState, screen: "table", state, ...extra });
  return render(<Table />);
};

describe("Table: nach dem Aufgeben zuschauen (Aufgabe 11)", () => {
  beforeEach(() => vi.mocked(send).mockClear());
  afterEach(() => cleanup());

  it("eigener Sitz ausgeschieden: Zuschauer-Leiste mit End game, keine Spielerknoepfe", () => {
    zeige(team(true));
    expect(screen.getByRole("button", { name: "End game" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Concede" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Pass priority" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Undo" })).toBeNull();
    // der Tisch bleibt: die Sitze der anderen und der eigene Sitz sind weiter da
    expect(screen.getAllByText(/AI 2/).length).toBeGreaterThan(0);
    expect(screen.getByText("You are out. Your team plays on.")).toBeInTheDocument();
  });

  it("End game schickt concede", () => {
    zeige(team(true));
    fireEvent.click(screen.getByRole("button", { name: "End game" }));
    expect(send).toHaveBeenCalledWith({ type: "concede" });
  });

  it("Enter und Escape loesen am ausgeschiedenen Sitz nichts mehr aus", () => {
    zeige(team(true));
    fireEvent.keyDown(window, { key: "Enter" });
    fireEvent.keyDown(window, { key: "Escape" });
    expect(send).not.toHaveBeenCalled();
  });

  it("eigener Sitz im Spiel: normale Spielerleiste", () => {
    zeige(team(false));
    expect(screen.getByRole("button", { name: "Concede" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pass priority" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "End game" })).toBeNull();
  });

  it("nur der Partner ausgeschieden: der eigene Sitz bleibt am Steuer", () => {
    zeige(snapshot({
      players: [seat(0, "You", 1, false), seat(1, "Partner", 1, true), seat(2, "AI 2", 2, false), seat(3, "AI 3", 2, false)],
    }));
    expect(screen.getByRole("button", { name: "Concede" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "End game" })).toBeNull();
  });

  it("Partie ohne Teams, ich bin raus: die Partie laeuft ohne mich weiter, kein Team im Satz", () => {
    // 1v3 ohne Teams: Forge beendet die Partie nicht, wenn der Mensch stirbt - "Your team plays on" waere gelogen.
    zeige(snapshot({
      players: [seat(0, "You", undefined, true), seat(1, "AI 1", undefined, false), seat(2, "AI 2", undefined, false), seat(3, "AI 3", undefined, false)],
    }));
    expect(screen.getByText("You are out. The game continues without you.")).toBeInTheDocument();
    expect(screen.queryByText(/team/i)).toBeNull();
    expect(screen.getByRole("button", { name: "End game" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pass priority" })).toBeNull();
  });

  it("Partie ohne Teams, nichts verloren: unveraendert", () => {
    zeige(snapshot({ players: [seat(0, "You", undefined, false), seat(1, "AI 1", undefined, false)] }));
    expect(screen.getByRole("button", { name: "Concede" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "End game" })).toBeNull();
  });

  it("alter Snapshot ohne lost-Feld: unveraendert", () => {
    const alt = snapshot({ players: [seat(0, "You", 1, false), seat(1, "AI 1", 2, false)] });
    alt.players.forEach((p) => { delete (p as { lost?: boolean }).lost; });
    zeige(alt);
    expect(screen.getByRole("button", { name: "Concede" })).toBeInTheDocument();
  });

  it("Zuschauer (kein me) bleibt, wie er war: Zuschauer-Leiste mit eigenem Prompt", () => {
    zeige(snapshot({
      me: undefined, spectator: true,
      prompt: { ...prompt, message: "Watching", okLabel: "Pause", cancelLabel: "Step" },
      players: [seat(0, "AI 1", undefined, false), seat(1, "AI 2", undefined, false)],
    }));
    expect(screen.getByRole("button", { name: "End game" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pause" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Step" })).toBeEnabled();
  });

  it("Partie zu Ende: die Spielerleiste bleibt, der Dialog meldet das Ende", () => {
    zeige({ ...team(true), gameOver: true } as Snapshot, { winner: "Team 1", winnerSeats: [0, 1] });
    expect(screen.getByRole("button", { name: "Concede" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "End game" })).toBeNull();
  });
});
