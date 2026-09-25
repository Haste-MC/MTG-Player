// Alter des EDHREC-Stands hinter den Kartenvorschlaegen (Stueck 7): reine Formatierung, ohne DOM, damit
// sie ohne Browser testbar ist (siehe Suggestions.tsx fuer die Anzeige selbst).

/** Ab wann ein Stand als veraltet gilt - dieselbe Frist wie Edhrec.MAX_AGE auf der Bridge (7 Tage): die
 *  Bridge faellt erst danach auf einen abgelaufenen Zwischenstand zurueck, und genau ab diesem Alter soll
 *  Kevin auch auf dem Board sehen, dass er ggf. veraltete Zahlen anschaut. */
export const STALE_AFTER_DAYS = 7;

const MS_PER_DAY = 24 * 60 * 60 * 1000;

export interface SuggestFreshness {
  /** "23.09." - Tag und Monat, deutsches Format, beide zweistellig, ohne Jahr. */
  label: string;
  /** true, wenn der Stand mehr als STALE_AFTER_DAYS Tage alt ist ("älter als sieben Tage" - auf den Tag
   *  genau sieben Tage gilt noch NICHT als veraltet). */
  stale: boolean;
}

/** Bereitet CardSuggestionsMsg.fetched fuer die Anzeige auf. `fetched` fehlt beim Datenbank-Rueckfall
 *  (kein EDHREC-Datum) - dann gibt es auch keine Standzeile, die Funktion liefert `undefined`. `now` ist
 *  ein Parameter (nicht `new Date()` fest verdrahtet), damit die Tests ohne Systemuhr-Mock auskommen. */
export function suggestFreshness(fetched: string | undefined, now: Date): SuggestFreshness | undefined {
  if (!fetched) return undefined;
  const d = new Date(fetched);
  if (Number.isNaN(d.getTime())) return undefined;
  const day = String(d.getDate()).padStart(2, "0");
  const month = String(d.getMonth() + 1).padStart(2, "0");
  const ageMs = now.getTime() - d.getTime();
  return { label: `${day}.${month}.`, stale: ageMs > STALE_AFTER_DAYS * MS_PER_DAY };
}
