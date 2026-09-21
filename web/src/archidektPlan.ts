import type { ArchidektEntry, DeckInfo } from "./protocol";

/** Zustand einer Archidekt-Deckliste-Zeile gegenüber den eigenen (bereits importierten) Decks. */
export type EntryState = "neu" | "aktuell" | "geändert";

/** DeckInfo.archidekt ist ein String (Bridge-Tag), ArchidektEntry.id ein number aus der API -
 * der Vergleich läuft deshalb ueber String(id). Kein eigenes Deck mit dieser Id -> "neu"; gleiches
 * archidektUpdated -> "aktuell"; sonst (auch wenn archidektUpdated fehlt/null ist) -> "geändert". */
export function classify(e: ArchidektEntry, own: DeckInfo[]): EntryState {
  const match = own.find((d) => d.archidekt != null && d.archidekt === String(e.id));
  if (!match) return "neu";
  return match.archidektUpdated === e.updatedAt ? "aktuell" : "geändert";
}

/** Vorbelegung der Auswahl beim Öffnen des Reiters: alle "geändert", Reihenfolge wie `entries`. */
export function defaultSelection(entries: ArchidektEntry[], own: DeckInfo[]): number[] {
  return entries.filter((e) => classify(e, own) === "geändert").map((e) => e.id);
}

/** Ids fuer "Alle aktualisieren": "aktuell" und "geändert" (neue Decks muessen erst ausgewaehlt werden),
 * Reihenfolge wie `entries`. */
export function updateAllIds(entries: ArchidektEntry[], own: DeckInfo[]): number[] {
  return entries.filter((e) => classify(e, own) !== "neu").map((e) => e.id);
}

/** Anzeige von `archidektProgress.current`: die Bridge kennt bei einem neuen Deck nur die Id ("Deck <id>",
 * die Konto-Liste liegt nur im Client) - hier wird sie auf den Namen aus der Liste abgebildet, sonst
 * bleibt der Text wie er ist (gespeicherter Name oder unbekannte Id). */
export function progressLabel(current: string, entries: ArchidektEntry[] | undefined): string {
  const m = /^Deck (\d+)$/.exec(current);
  if (!m) return current;
  const hit = entries?.find((e) => String(e.id) === m[1]);
  return hit ? hit.name : current;
}
