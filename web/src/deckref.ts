import type { DeckRef } from "./protocol";

/** Auswahl eines Decks in der Lobby-UI. "name" ist hier der (optionale) Speichername fuer ein
 * Textdeck - wird beim Versand als "deckName" auf DeckRef abgebildet, NICHT als "name" (das ist
 * bei einem Gegner-Eintrag der Spielername, siehe Outbound.startGame in protocol.ts). */
export type Pick = { kind: "precon" | "saved" | "text" | "archidekt"; value: string; name: string };

/** Noch nichts gewaehlt - Ausgangszustand jedes Deck-Slots in der Lobby. */
export const EMPTY_PICK: Pick = { kind: "precon", value: "", name: "" };

export function toRef(p: Pick): DeckRef | undefined {
  if (p.kind === "text") return p.value.trim() ? { text: p.value, deckName: p.name.trim() || undefined } : undefined;
  if (p.kind === "archidekt") return p.value.trim() ? { archidekt: p.value.trim(), deckName: p.name.trim() || undefined } : undefined;
  if (!p.value) return undefined;
  return p.kind === "precon" ? { precon: p.value } : { saved: p.value };
}
