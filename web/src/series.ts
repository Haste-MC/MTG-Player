import type { StartGame } from "./protocol";

/** Laufende Serie ("Best of n") ueber dieselbe Deck-Konstellation. wins: Siege je Spielername
 * (GameOver.winner); games zaehlt auch Partien ohne Sieger (Unentschieden/Abbruch). */
export interface Series { key: string; wins: Record<string, number>; games: number }

/** Identitaet einer Serie: Sitzmodus und Decks - Spielernamen ("KI 1") und Bedenkzeit gehoeren nicht dazu. */
export function seriesKey(msg: StartGame): string {
  return JSON.stringify({
    spectate: msg.spectate,
    humanDeck: msg.humanDeck,
    opponents: msg.opponents.map(({ name: _name, ...deck }) => deck),
  });
}

/** Gleiche Konstellation wie zuvor -> die Serie laeuft weiter (prev bleibt), sonst beginnt eine neue. */
export function startSeries(prev: Series | undefined, msg: StartGame): Series {
  const key = seriesKey(msg);
  return prev && prev.key === key ? prev : { key, wins: {}, games: 0 };
}

export function recordResult(s: Series, winner: string | null): Series {
  const wins = winner === null ? s.wins : { ...s.wins, [winner]: (s.wins[winner] ?? 0) + 1 };
  return { ...s, wins, games: s.games + 1 };
}

/** Name des Serien-Siegers, sobald jemand ceil(bestOf/2) Siege hat; bestOf 0 = keine Serie -> nie. */
export function seriesWinner(s: Series, bestOf: number): string | undefined {
  if (bestOf <= 0) return undefined;
  const needed = Math.ceil(bestOf / 2);
  return Object.keys(s.wins).find((name) => s.wins[name] >= needed);
}

/** Anzeige "Du 2 · KI 1 1" - in der Reihenfolge von names, fehlende Eintraege als 0. */
export function formatSeries(s: Series, names: string[]): string {
  return names.map((n) => `${n} ${s.wins[n] ?? 0}`).join(" · ");
}
