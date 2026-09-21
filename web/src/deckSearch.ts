import type { DeckInfo } from "./protocol";

/** Deck-Suche der Lobby: Deck- oder Commander-Name enthaelt die (getrimmte) Query, ohne Beachtung der
 * Gross-/Kleinschreibung; leere Query liefert alle Decks unveraendert. */
export function filterDecks(decks: DeckInfo[], query: string): DeckInfo[] {
  const q = query.trim().toLowerCase();
  if (!q) return decks;
  return decks.filter((d) => d.name.toLowerCase().includes(q) || d.commanders.some((c) => c.name.toLowerCase().includes(q)));
}
