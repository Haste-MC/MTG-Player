// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import type { UpdateStateMsg, VersionMsg } from "../protocol";
import { initialState, useStore } from "../store";
import { send } from "../ws";
import Lobby from "./Lobby";

// Review-Nachtrag zu Aufgabe 6: update.test.ts prueft nur die reine Funktion updateBanner, store.test.ts
// nur den Reducer/die Store-Aktionen - die eigentliche Knopf-Verdrahtung in Lobby.tsx (welcher Knopf ruft
// welche Store-Aktion, welcher Text steht im Fehlerfall, verschwindet "Aktualisieren" wirklich zugunsten
// von "Erneut versuchen") war bislang ungeprueft. Dasselbe Muster wie DeckCards.test.tsx/
// Suggestions.test.tsx: Attrappe fuer ws, jsdom, Klicks statt nur Zustands-Vergleiche.

vi.mock("../ws", () => ({ send: vi.fn() }));

const VERSION: VersionMsg = {
  type: "version", current: "1.40.0", latest: "1.41.0", url: "https://example.invalid/x.zip",
  sha256: "abc", notes: "",
};

describe("Lobby: Update-Hinweis (Aufgabe 6)", () => {
  beforeEach(() => {
    // version/updateState stehen NICHT in initialState (beide optional, kein Default) - ein reines
    // {...initialState} wuerde sie bei einem zustand.setState-Merge von einem vorigen Test stehen lassen
    // (dasselbe Muster wie store.test.ts es fuer "sparring" explizit macht). Ohne die beiden hier zeigte
    // "Später" faelschlich noch den updateState des vorigen Tests.
    useStore.setState({ ...initialState, version: undefined, updateState: undefined });
    vi.mocked(send).mockClear();
  });

  afterEach(() => cleanup());

  it("ohne \"version\" ist keine Leiste zu sehen", () => {
    render(<Lobby />);
    expect(screen.queryByText(/verfügbar/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aktualisieren" })).not.toBeInTheDocument();
  });

  it("Aktualisieren schickt applyUpdate; ein Fehler zeigt den Grund und macht den Knopf wieder "
    + "benutzbar (als \"Erneut versuchen\"); ein erneuter Klick schickt wieder applyUpdate", () => {
    render(<Lobby />);
    act(() => useStore.getState().apply(VERSION));
    expect(screen.getByText("Version 1.41.0 verfügbar")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Aktualisieren" }));
    expect(send).toHaveBeenCalledWith({ type: "applyUpdate" });
    expect(send).toHaveBeenCalledTimes(1);

    const fehler: UpdateStateMsg = { type: "updateState", state: "fehler", text: "Prüfsumme stimmt nicht überein" };
    act(() => useStore.getState().apply(fehler));

    // Der Grund aus der Bridge steht da, wortgleich - keine feste Ersatzformulierung.
    expect(screen.getByText("Prüfsumme stimmt nicht überein")).toBeInTheDocument();
    // "Aktualisieren" ist weg, "Später" ebenso (Spec: im Fehlerfall nur der Knopf zum erneuten Versuch).
    expect(screen.queryByRole("button", { name: "Aktualisieren" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Später" })).not.toBeInTheDocument();

    const retry = screen.getByRole("button", { name: "Erneut versuchen" });
    expect(retry).not.toBeDisabled();

    fireEvent.click(retry);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenLastCalledWith({ type: "applyUpdate" });
  });

  it("\"Später\" entfernt die Leiste, ohne applyUpdate zu schicken und ohne etwas in localStorage zu "
    + "speichern (Brief: \"nichts wird dauerhaft gespeichert\")", () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    render(<Lobby />);
    act(() => useStore.getState().apply(VERSION));
    expect(screen.getByText("Version 1.41.0 verfügbar")).toBeInTheDocument();
    setItem.mockClear(); // Lobby speichert beim Laden selbst KI-/Deck-Einstellungen - nur der Klick zaehlt hier.

    fireEvent.click(screen.getByRole("button", { name: "Später" }));

    expect(screen.queryByText(/verfügbar/)).not.toBeInTheDocument();
    expect(send).not.toHaveBeenCalled();
    expect(setItem).not.toHaveBeenCalled();
    setItem.mockRestore();
  });

  it("Fortschrittszustaende (z. B. \"pruefen\") zeigen nur Text, keine Knoepfe", () => {
    render(<Lobby />);
    act(() => useStore.getState().apply(VERSION));
    const pruefen: UpdateStateMsg = { type: "updateState", state: "pruefen", text: "" };
    act(() => useStore.getState().apply(pruefen));

    expect(screen.getByText("Prüft die Datei …")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Aktualisieren" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Später" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Erneut versuchen" })).not.toBeInTheDocument();
  });
});
