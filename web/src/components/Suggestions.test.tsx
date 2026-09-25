// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { Finding } from "../findings";
import { initialState, useStore } from "../store";
import { send } from "../ws";
import Suggestions from "./Suggestions";

// Befund 3: die Bridge antwortet auf suggestCards bei unbekanntem Deck und bei jeder Ausnahme mit
// "error" - vorher blieb der Knopf dann fuer immer auf "lädt …" stehen (rein lokales useState konnte
// eine fehlgeschlagene Anfrage nicht von einer offenen unterscheiden). Dieser Test deckt genau den
// Fehlerfall ab: Klick -> error -> Knopf wieder benutzbar, ein zweiter Klick fragt erneut an.

vi.mock("../ws", () => ({ send: vi.fn() }));

// Eine Rolle mit Befund, damit roleGaps() nicht leer ist und der Knopf statt "Keine Lücke gefunden."
// tatsaechlich angezeigt wird (siehe findings.roleGaps).
const found: Finding[] = [{ level: "warn", title: "Mana-Screw", text: "…", role: "ramp" }];

describe("Suggestions", () => {
  beforeEach(() => {
    useStore.setState({ ...initialState });
    vi.mocked(send).mockClear();
  });

  afterEach(() => cleanup());

  it("bleibt nach einem Fehler der Bridge nicht auf \"lädt …\" haengen - der Knopf fragt erneut an", () => {
    render(<Suggestions deck="Krenko Goblins" found={found} />);

    const button = screen.getByRole("button", { name: "Vorschläge laden" });
    fireEvent.click(button);

    expect(send).toHaveBeenCalledWith({ type: "suggestCards", deck: "Krenko Goblins", roles: ["ramp"] });
    expect(screen.getByRole("button", { name: "lädt …" })).toBeDisabled();

    // Die Bridge lehnt ab (unbekanntes Deck oder jede andere Ausnahme - die Fehlermeldung selbst ist
    // hier nicht load-bearing, nur dass "error" ankommt).
    act(() => useStore.getState().apply({ type: "error", text: "Kartenvorschläge Krenko Goblins: unbekanntes Deck" }));

    const retryButton = screen.getByRole("button", { name: "Vorschläge laden" });
    expect(retryButton).not.toBeDisabled();

    fireEvent.click(retryButton);
    expect(send).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "lädt …" })).toBeDisabled();
  });

  it("zeigt einen erklärenden Satz statt nur der Überschrift, wenn die Antwort 0 Vorschläge trägt (Befund 7)", () => {
    render(<Suggestions deck="Krenko Goblins" found={found} />);
    act(() => useStore.getState().apply({ type: "cardSuggestions", deck: "Krenko Goblins", source: "edhrec", suggestions: [] }));

    expect(screen.getByText(/Farbidentität oder Bracket/)).toBeInTheDocument();
  });

  it("zeigt die Rollengruppen in der Reihenfolge der Vorschläge, nicht in einer festen Reihenfolge (Befund 6)", () => {
    render(<Suggestions deck="Krenko Goblins" found={found} />);
    act(() => useStore.getState().apply({
      type: "cardSuggestions", deck: "Krenko Goblins", source: "db",
      suggestions: [
        { name: "Fog", role: "wipeProtection", cmc: 1 },
        { name: "Rampant Growth", role: "ramp", cmc: 2 },
      ],
    }));

    // "Schutz vor Massenentfernung" (wipeProtection) steht in der Antwort VOR "Ramp" - obwohl eine feste
    // Rollenreihenfolge (wie in DeckAnalysisPanel.tsx) Ramp zuerst zeigen wuerde.
    const titles = screen.getAllByText(/^(Ramp|Schutz vor Massenentfernung)$/).map((el) => el.textContent);
    expect(titles).toEqual(["Schutz vor Massenentfernung", "Ramp"]);
  });
});
