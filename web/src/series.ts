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

/** Was der Spielende-Dialog nach dem letzten Spiel als Naechstes tun soll (Spec §1):
 *  - "decided", sobald ein Name die Serie fuer sich entschieden hat (seriesWinner) - egal ob die
 *    zuletzt gespielte Partie gewertet wurde, das Ergebnis steht ja schon fest.
 *  - "countdown" nur bei einer laufenden, noch offenen Serie (bestOf > 0) UND einer gewerteten letzten
 *    Partie - eine abgebrochene Partie (lastMatchCounted: false) darf nichts automatisch starten.
 *  - sonst "none": keine Serie (series fehlt), bestOf 0 oder eben eine abgebrochene letzte Partie. */
export function nextSeriesStep(
  series: Series | undefined,
  bestOf: number,
  lastMatchCounted: boolean,
): { kind: "countdown" } | { kind: "decided"; winner: string } | { kind: "none" } {
  if (!series || bestOf <= 0) return { kind: "none" };
  const winner = seriesWinner(series, bestOf);
  if (winner) return { kind: "decided", winner };
  if (!lastMatchCounted) return { kind: "none" };
  return { kind: "countdown" };
}
