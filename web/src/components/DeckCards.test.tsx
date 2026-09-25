// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { CardStatsMsg } from "../protocol";
import { initialState, useStore } from "../store";
import { send } from "../ws";
import DeckCards from "./DeckCards";

// Befund 7: fuer DeckCards.tsx gab es (anders als fuer Suggestions.tsx) noch keinen Komponententest -
// genau der Fall, den der Kommentar in store.ts ueber requestDeckCards beschwoert ("ein error der Bridge
// raeumt die offene Anfrage im Store weg, sonst haengt der Knopf fuer immer auf 'lädt …'"), war bislang
// ungeprueft. Aufbau 1:1 wie Suggestions.test.tsx.

vi.mock("../ws", () => ({ send: vi.fn() }));

/** Minimale Antwort auf deckCards - nur die Felder, die reduce()/DeckCards.tsx ueberhaupt anfassen. */
function cardStats(over: Partial<CardStatsMsg> & { deck: string }): CardStatsMsg {
  return { type: "cardStats", games: 0, withCardData: 0, enough: false, cards: [], ...over };
}

describe("DeckCards", () => {
  beforeEach(() => {
    useStore.setState({ ...initialState });
    vi.mocked(send).mockClear();
  });

  afterEach(() => cleanup());

  it("bleibt nach einem Fehler der Bridge nicht auf \"lädt …\" haengen - der Knopf fragt erneut an", () => {
    render(<DeckCards deck="Krenko Goblins" />);

    const button = screen.getByRole("button", { name: "Karten laden" });
    fireEvent.click(button);

    expect(send).toHaveBeenCalledWith({ type: "deckCards", deck: "Krenko Goblins" });
    expect(screen.getByRole("button", { name: "lädt …" })).toBeDisabled();

    // Die Bridge lehnt ab (unbekanntes Deck oder jede andere Ausnahme - der Text selbst ist hier nicht
    // load-bearing, nur dass "error" ankommt und den pendingCards-Eintrag wegraeumt).
    act(() => useStore.getState().apply({ type: "error", text: "Kartentabelle Krenko Goblins: unbekanntes Deck" }));

    const retryButton = screen.getByRole("button", { name: "Karten laden" });
    expect(retryButton).not.toBeDisabled();

    fireEvent.click(retryButton);
    expect(send).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "lädt …" })).toBeDisabled();
  });

  it("zeigt den Hinweis auf zu wenige Partien statt der Tabelle, solange enough falsch ist", () => {
    render(<DeckCards deck="Krenko Goblins" />);
    act(() => useStore.getState().apply(cardStats({ deck: "Krenko Goblins", games: 3, withCardData: 2, enough: false })));

    expect(screen.getByText(/Noch zu wenige Partien mit Aufzeichnung \(2 von 5 nötig\)/)).toBeInTheDocument();
  });

  it("zeigt den Erstlauf-Hinweis statt \"0 von 5 nötig\", wenn noch gar keine Partie Aufzeichnung hat", () => {
    render(<DeckCards deck="Krenko Goblins" />);
    act(() => useStore.getState().apply(cardStats({ deck: "Krenko Goblins", games: 4, withCardData: 0, enough: false })));

    expect(screen.getByText(/Noch keine Partie mit Aufzeichnung/)).toBeInTheDocument();
    expect(screen.queryByText(/0 von 5 nötig/)).not.toBeInTheDocument();
  });

  it("zeigt die Kartenzeilen und die Kopfzeile mit games/withCardData, wenn genug Partien vorliegen", () => {
    render(<DeckCards deck="Krenko Goblins" />);
    act(() => useStore.getState().apply(cardStats({
      deck: "Krenko Goblins", games: 9, withCardData: 9, enough: true,
      cards: [
        {
          name: "Sol Ring", imageKey: "sol-ring", handGames: 8, castGames: 8, avgCastTurn: 1, stuckGames: 0,
          neverDrawnGames: 1, counteredGames: 0, lostGames: 0,
        },
      ],
    })));

    expect(screen.getByText("9 von 9 gewerteten Partien mit Aufzeichnung")).toBeInTheDocument();
    expect(screen.getByText("Sol Ring")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "neu laden" })).toBeInTheDocument();
  });

  it("ein \"neu laden\" darf erneut anfragen, obwohl schon ein Stand vorliegt (dasselbe Muster wie Suggestions)", () => {
    render(<DeckCards deck="Krenko Goblins" />);
    act(() => useStore.getState().apply(cardStats({ deck: "Krenko Goblins", games: 9, withCardData: 9, enough: true })));

    fireEvent.click(screen.getByRole("button", { name: "neu laden" }));
    expect(send).toHaveBeenCalledWith({ type: "deckCards", deck: "Krenko Goblins" });
  });
});
