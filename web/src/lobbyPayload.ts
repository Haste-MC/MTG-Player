import type { DeckRef, Outbound } from "./protocol";

/**
 * Reine Übersetzung der Lobby-Auswahl in die startGame-Nachricht - testbar ohne React/Socket.
 * Menschliches Spiel: humanDeck muss aufgelöst sein, jeder KI-Slot ebenso.
 * KI-only-Zuschauer-Modus (spectate): kein humanDeck (Lobby.tsx blendet "Dein Deck" dafür aus),
 * mindestens 2 aufgelöste KI-Decks (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
 * Undefined, solange nicht spielbereit - der "Spiel starten"-Button bleibt dann disabled.
 */
export function buildStartGame(
  spectate: boolean,
  human: DeckRef | undefined,
  ais: (DeckRef | undefined)[],
): Outbound | undefined {
  if (ais.some((r) => r === undefined)) return undefined;
  const opponents = ais.map((r, i) => ({ ...(r as DeckRef), name: `KI ${i + 1}` }));

  if (spectate) {
    if (ais.length < 2) return undefined;
    return { type: "startGame", spectate: true, opponents };
  }
  if (human === undefined) return undefined;
  return { type: "startGame", humanDeck: human, opponents };
}
