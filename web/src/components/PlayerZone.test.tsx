// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { Snapshot } from "../protocol";
import PlayerZone from "./PlayerZone";

// Teams am Tisch (Aufgabe 7): die Kopfzeile des Sitzes traegt eine kleine Teammarke, der Partner des eigenen
// Sitzes zusaetzlich das Wort "Partner". Die Tests sichern, dass die Marke nur bei echten Teams erscheint
// (Feld fehlt / -1 = kein Team) und dass der Zuschauer-Sitz nie einen "Partner" bekommt.

vi.mock("../ws", () => ({ send: vi.fn() }));
afterEach(cleanup);

// Minimaler Sitz; die uebrigen Felder interessieren die Kopfzeile nicht. team undefined = Feld fehlt ganz.
const seat = (id: number, name: string, team?: number) => ({
  id, name, isAi: id !== 0, life: 40, commanderDamage: {}, hand: [], librarySize: 60,
  graveyard: [], exile: [], command: [], battlefield: [], manaPool: {}, hasPriority: false,
  ...(team === undefined ? {} : { team }),
});

const snapshot = (players: ReturnType<typeof seat>[], me: number | undefined = 0) => ({
  type: "state", turn: 1, phase: "MAIN1", turnPlayer: 0, priority: 0, me, gameOver: false,
  players, stack: [], cards: {}, stops: { own: [], opp: [] }, fullControl: false,
  prompt: { seq: 0, text: "", options: [] }, combat: [],
} as unknown as Snapshot);

const state = snapshot([seat(0, "You", 1), seat(1, "AI 1", 1), seat(2, "AI 2", 2), seat(3, "AI 3", 2)]);

describe("PlayerZone mit Teams", () => {
  it("zeigt die Teammarke und den Partner", () => {
    render(<PlayerZone p={state.players[1]} state={state} compact={false} />);
    expect(screen.getByTitle("Team 1")).toHaveTextContent("T1");
    expect(screen.getByText("Partner")).toBeInTheDocument();
  });

  it("ohne Teams keine Marke", () => {
    const frei = snapshot([seat(0, "You", -1), seat(1, "AI 1", -1)]);
    render(<PlayerZone p={frei.players[1]} state={frei} compact={false} />);
    expect(screen.queryByText(/^T\d/)).toBeNull();
    expect(screen.queryByText("Partner")).toBeNull();
  });

  it("fehlendes team-Feld ist kein Team (kein undefined >= 0)", () => {
    const frei = snapshot([seat(0, "You"), seat(1, "AI 1")]);
    const { container } = render(<PlayerZone p={frei.players[1]} state={frei} compact />);
    expect(container.querySelector(".team-tag")).toBeNull();
    expect(container.querySelector(".player")!.className).not.toMatch(/team-edge/);
  });

  // Die Bridge nimmt Teamnummern bis 6 an (Teams.java), die Lobby bietet heute nur 1-3. Ohne diesen Fall
  // koennte jemand die Marke spaeter aus einer Tabelle fuer 1-3 ableiten, ohne dass ein Test rot wird.
  it("teamnummer jenseits der lobby-auswahl wird weiter angezeigt", () => {
    const sechs = snapshot([seat(0, "You", 5), seat(1, "AI 1", 5), seat(2, "AI 2", 6)]);
    render(<PlayerZone p={sechs.players[2]} state={sechs} compact />);
    expect(screen.getByTitle("Team 6")).toHaveTextContent("T6");
  });

  it("Gegner aus dem anderen Team traegt seine Marke, aber nicht das Wort Partner", () => {
    render(<PlayerZone p={state.players[2]} state={state} compact />);
    expect(screen.getByTitle("Team 2")).toHaveTextContent("T2");
    expect(screen.queryByText("Partner")).toBeNull();
  });

  it("der eigene Sitz ist nicht sein eigener Partner", () => {
    render(<PlayerZone p={state.players[0]} state={state} compact={false} />);
    expect(screen.getByTitle("Team 1")).toHaveTextContent("T1");
    expect(screen.queryByText("Partner")).toBeNull();
  });

  it("im Zuschauer-Sitz (me fehlt) gibt es Marken, aber keinen Partner", () => {
    const zu = snapshot([seat(1, "AI 1", 1), seat(2, "AI 2", 1), seat(3, "AI 3", 2), seat(4, "AI 4", 2)], undefined);
    render(<PlayerZone p={zu.players[1]} state={zu} compact spectator />);
    expect(screen.getByTitle("Team 1")).toHaveTextContent("T1");
    expect(screen.queryByText("Partner")).toBeNull();
  });

  it("das Panel bekommt den Rahmenton seines Teams, die Sitzordnung bleibt unberuehrt", () => {
    const { container } = render(<PlayerZone p={state.players[3]} state={state} compact />);
    const panel = container.querySelector(".player")!;
    expect(panel).toHaveClass("team-edge-2");
    expect(panel).toHaveClass("compact");
  });
});
