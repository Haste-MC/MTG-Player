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

// Teams in der Lobby: die Verdrahtung zwischen Sitzliste, Teamwahl und startGame. Die Regeln selbst
// stehen in lineup.test.ts; hier geht es darum, dass die Sitze und ihre Teams im Gleichschritt bleiben.
describe("Lobby: Teams", () => {
  const SEAT = { mode: "standard", profile: "Default" };
  const text = (name: string) => ({ kind: "text", value: `1 Sol Ring ${name}`, name });

  /** Eine Lobby mit gemerkten Textdecks (ueberstehen dropMissing ohne Precon-Liste) und gemerkten Einstellungen. */
  function lobbyMit(aiSitze: number, einstellungen: object = {}) {
    localStorage.setItem("mtg.lobby.picks", JSON.stringify({
      human: text("Mensch"), ais: Array.from({ length: aiSitze }, (_, i) => text(`KI ${i + 1}`)),
    }));
    localStorage.setItem("mtg.lobby.ai", JSON.stringify({
      picks: Array.from({ length: aiSitze }, () => SEAT), timeout: 5, bestOf: 0, ...einstellungen,
    }));
    useStore.setState({ ...initialState, version: undefined, updateState: undefined,
      precons: [{ name: "Irgendeins", commanders: [], archidekt: null }] as never });
    vi.mocked(send).mockClear();
    render(<Lobby />);
  }
  const wahl = (name: string) => screen.getByRole("combobox", { name }) as HTMLSelectElement;
  const start = () => screen.getByRole("button", { name: "Spiel starten" });

  // Eigener Speicher je Test: dieses jsdom liefert hier kein brauchbares localStorage, und die Tests
  // duerfen sich ohnehin nicht gegenseitig beeinflussen.
  beforeEach(() => {
    const daten = new Map<string, string>();
    vi.stubGlobal("localStorage", {
      getItem: (k: string) => daten.get(k) ?? null,
      setItem: (k: string, v: string) => { daten.set(k, v); },
      removeItem: (k: string) => { daten.delete(k); },
      clear: () => daten.clear(),
    });
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("ohne Teams startet die Partie wie bisher, ohne ein Team-Feld", () => {
    lobbyMit(1);
    expect(screen.getByText("ohne Teams – jeder gegen jeden")).toBeInTheDocument();
    fireEvent.click(start());
    const msg = vi.mocked(send).mock.calls[0][0];
    expect(JSON.stringify(msg)).not.toMatch(/team|Team|revealPartnerHand/);
  });

  it("2v2-Schnellwahl bei vier Sitzen: Aufstellungszeile, Team im Payload, Partnerhand nur mit Haken", () => {
    lobbyMit(3);
    fireEvent.click(screen.getByRole("button", { name: "2v2" }));
    expect(screen.getByText("Team 1: You + AI 1 — Team 2: AI 2 + AI 3")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("Partnerhand zeigen"));
    fireEvent.click(start());
    const msg = vi.mocked(send).mock.calls[0][0] as { humanTeam: number; revealPartnerHand: boolean; opponents: { team: number }[] };
    expect(msg.humanTeam).toBe(1);
    expect(msg.revealPartnerHand).toBe(true);
    expect(msg.opponents.map((o) => o.team)).toEqual([1, 2, 2]);
  });

  it("zu zweit ist Team 1 gegen Team 2 ein Duell: Start gesperrt und Grund genannt", () => {
    lobbyMit(1);
    fireEvent.change(wahl("Team von You"), { target: { value: "1" } });
    fireEvent.change(wahl("Team von AI 1"), { target: { value: "2" } });
    expect(start()).toBeDisabled();
    expect(screen.getByText("Teams: mindestens ein Team braucht zwei Sitze.")).toBeInTheDocument();
  });

  it("halb gesetzt sperrt den Start", () => {
    lobbyMit(2);
    fireEvent.change(wahl("Team von You"), { target: { value: "1" } });
    expect(start()).toBeDisabled();
    expect(screen.getByText("Teams: entweder alle Sitze oder keiner.")).toBeInTheDocument();
  });

  it("ein neuer Sitz kommt ohne Team dazu und macht die vollstaendige Aufstellung wieder unvollstaendig", () => {
    lobbyMit(3, { teams: [1, 1, 2, 2] });
    expect(start()).not.toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "+ Gegner hinzufügen" }));
    expect(wahl("Team von AI 4").value).toBe("-1");
    expect(start()).toBeDisabled();
  });

  it("ein entfernter Sitz nimmt sein Team mit: die Teams der uebrigen bleiben bei ihren Sitzen", () => {
    lobbyMit(3, { teams: [1, 1, 2, 2] });
    // AI 1 (Team 1) entfernen: AI 2 und AI 3 (beide Team 2) ruecken auf Platz 1 und 2 und behalten Team 2.
    fireEvent.click(screen.getAllByTitle("Gegner entfernen")[0]);
    expect(wahl("Team von AI 1").value).toBe("2");
    expect(wahl("Team von AI 2").value).toBe("2");
    expect(screen.getByText("Team 1: You — Team 2: AI 1 + AI 2")).toBeInTheDocument();
    expect(start()).not.toBeDisabled();
  });

  it("Zuschauer-Modus an: der menschliche Sitz und sein Team verschwinden aus der Nachricht, danach sind sie wieder da", () => {
    lobbyMit(3, { teams: [1, 1, 2, 2] });
    fireEvent.click(screen.getByLabelText("Nur KI – zuschauen"));
    // Die KI-Sitze allein sind 1/2/2: gueltig, ohne einen Platz fuer "You".
    expect(screen.getByText("Team 1: AI 1 — Team 2: AI 2 + AI 3")).toBeInTheDocument();
    fireEvent.click(start());
    const msg = vi.mocked(send).mock.calls[0][0] as { humanTeam?: number; revealPartnerHand?: boolean; opponents: { team: number }[] };
    expect(msg).not.toHaveProperty("humanTeam");
    expect(msg).not.toHaveProperty("revealPartnerHand");
    expect(msg.opponents.map((o) => o.team)).toEqual([1, 2, 2]);
    fireEvent.click(screen.getByLabelText("Nur KI – zuschauen"));
    expect(screen.getByText("Team 1: You + AI 1 — Team 2: AI 2 + AI 3")).toBeInTheDocument();
  });

  it("Zuschauer-Modus aus einem einzelnen KI-Sitz heraus: der zweite Sitz kommt ohne Team dazu", () => {
    lobbyMit(1, { teams: [1, 1] });
    fireEvent.click(screen.getByLabelText("Nur KI – zuschauen"));
    // Zwei KI-Sitze (der gemerkte und ein neuer): der neue hat kein Team, die Aufstellung ist halb gesetzt.
    expect(wahl("Team von AI 2").value).toBe("-1");
    expect(start()).toBeDisabled();
  });

  it("die Teamwahl wird gemerkt", () => {
    lobbyMit(3);
    fireEvent.click(screen.getByRole("button", { name: "2v2" }));
    const gespeichert = JSON.parse(localStorage.getItem("mtg.lobby.ai") ?? "{}");
    expect(gespeichert.teams).toEqual([1, 1, 2, 2]);
  });
});
