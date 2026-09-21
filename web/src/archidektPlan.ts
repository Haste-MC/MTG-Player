import type { ArchidektEntry, DeckInfo } from "./protocol";

/** Zustand einer Archidekt-Deckliste-Zeile gegenüber den eigenen (bereits importierten) Decks. "übernehmen": kein
 * eigenes Deck mit dieser Id, aber eines mit exakt gleichem Namen ohne archidekt-Tag - der Import ersetzt es
 * (Bridge, DeckSource.importArchidekt) statt ein "<Name> (<Id>)" daneben zu legen. */
export type EntryState = "neu" | "aktuell" | "geändert" | "übernehmen";

/** DeckInfo.archidekt ist ein String (Bridge-Tag), ArchidektEntry.id ein number aus der API -
 * der Vergleich läuft deshalb ueber String(id). Kein eigenes Deck mit dieser Id -> "übernehmen" bei einem
 * gleichnamigen Deck ohne Tag, sonst "neu" (auch wenn das gleichnamige Deck ein anderes Tag traegt: dann speichert
 * die Bridge mit Suffix); gleiches archidektUpdated -> "aktuell"; sonst (auch wenn archidektUpdated fehlt/null
 * ist) -> "geändert". */
export function classify(e: ArchidektEntry, own: DeckInfo[]): EntryState {
  const match = own.find((d) => d.archidekt != null && d.archidekt === String(e.id));
  if (!match) return own.some((d) => d.name === e.name && d.archidekt == null) ? "übernehmen" : "neu";
  return match.archidektUpdated === e.updatedAt ? "aktuell" : "geändert";
}

/** Vorbelegung der Auswahl beim Öffnen des Reiters: alle "geändert", Reihenfolge wie `entries`. */
export function defaultSelection(entries: ArchidektEntry[], own: DeckInfo[]): number[] {
  return entries.filter((e) => classify(e, own) === "geändert").map((e) => e.id);
}

/** Ids fuer "Alle aktualisieren": "aktuell" und "geändert" (neue und zu uebernehmende Decks muessen erst
 * ausgewaehlt werden), Reihenfolge wie `entries`. */
export function updateAllIds(entries: ArchidektEntry[], own: DeckInfo[]): number[] {
  return entries.filter((e) => { const s = classify(e, own); return s === "aktuell" || s === "geändert"; }).map((e) => e.id);
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
