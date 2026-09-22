import type { MatchRecord, MatchSeat } from "./protocol";

// Die Auswertung ist deckbezogen, nicht spielerbezogen: wer das Deck gespielt hat (Mensch oder KI - im
// Sparring, Stueck 3, spielt eine KI Kevins Deck), ist unerheblich; entscheidend ist nur seat.deck. Je
// Partie zaehlt aber hoechstens ein Sitz je Deck (siehe pickSeat) - ein Spiegel (dasselbe Deck auf zwei
// Sitzen) ist eine Partie, kein Doppelzaehler.

/** Ein Deck mit mindestens einer gewerteten Partie, fuer die Deckliste des Statistik-Screens. */
export interface DeckGames { deck: string; games: number }

/** Decks aus den gewerteten Partien (ueber alle Sitze, nicht nur den eigenen) mit Partienzahl,
 * absteigend sortiert (Partienzahl, dann Name). Ein Deck auf zwei Sitzen derselben Partie (Spiegel)
 * zaehlt fuer diese Partie nur einmal. */
export function deckGames(records: MatchRecord[]): DeckGames[] {
  const counts = new Map<string, number>();
  for (const r of records) {
    if (!r.counted) continue;
    const decks = new Set(r.seats.map((seat) => seat.deck));
    for (const deck of decks) counts.set(deck, (counts.get(deck) ?? 0) + 1);
  }
  return [...counts.entries()]
    .map(([deck, games]) => ({ deck, games }))
    .sort((a, b) => b.games - a.games || a.deck.localeCompare(b.deck));
}

/** Waehlt fuer `deck` innerhalb einer Partie hoechstens einen Sitz: bevorzugt den menschlichen, sonst
 * den ersten passenden (Reihenfolge von `record.seats`). undefined, wenn kein Sitz dieses Deck spielt. */
function pickSeat(record: MatchRecord, deck: string): { seat: MatchSeat; index: number } | undefined {
  const matching = record.seats
    .map((seat, index) => ({ seat, index }))
    .filter((e) => e.seat.deck === deck);
  if (matching.length === 0) return undefined;
  return matching.find((e) => e.seat.human) ?? matching[0];
}

export interface DeckSummary {
  games: number; wins: number; losses: number; draws: number; winRate: number;
  ci: [number, number]; avgTurns: number; avgDurationMs: number; mulliganRate: number; avgMulligans: number;
  avgLandsTurn3: number; avgLandsTurn5: number; missedLandDropRate: number; avgMissedLandDrops: number;
  avgSpells: number; avgSpellMana: number; avgCommanderTurn?: number; avgCommanderTax: number;
  avgDamageDealt: number; avgDamageTaken: number; lossReasons: Record<string, number>;
  opponents: { deck: string; games: number; wins: number }[];
}

/** Kennzahlen fuer `deck` ueber die gewerteten Partien, in denen ein Sitz genau diesen Deck-Namen traegt -
 * je Partie hoechstens ein Sitz (siehe pickSeat; ein Spiegel zaehlt also als eine Partie). Metriken
 * stammen vom jeweils gewaehlten Sitz, die Gegner-Tabelle von den uebrigen Sitzen derselben Partie.
 * undefined, wenn keine gewertete Partie mit diesem Deck existiert. */
export function summarize(records: MatchRecord[], deck: string): DeckSummary | undefined {
  const entries: { record: MatchRecord; seat: MatchSeat; index: number }[] = [];
  for (const r of records) {
    if (!r.counted) continue;
    const picked = pickSeat(r, deck);
    if (picked) entries.push({ record: r, seat: picked.seat, index: picked.index });
  }
  if (entries.length === 0) return undefined;

  const games = entries.length;
  const wins = entries.filter((e) => e.seat.winner).length;
  const draws = entries.filter((e) => e.record.draw).length;
  const losses = games - wins - draws;

  const lossReasons: Record<string, number> = {};
  for (const e of entries) {
    if (!e.seat.winner && e.seat.lossReason) {
      lossReasons[e.seat.lossReason] = (lossReasons[e.seat.lossReason] ?? 0) + 1;
    }
  }

  const opponents = new Map<string, { games: number; wins: number }>();
  for (const e of entries) {
    e.record.seats.forEach((other, j) => {
      if (j === e.index) return;
      const cur = opponents.get(other.deck) ?? { games: 0, wins: 0 };
      cur.games += 1;
      if (e.seat.winner) cur.wins += 1;
      opponents.set(other.deck, cur);
    });
  }

  const commanderTurns = entries
    .map((e) => e.seat.firstCommanderTurn)
    .filter((t): t is number => t != null);

  return {
    games, wins, losses, draws, winRate: wins / games, ci: wilson(wins, games),
    avgTurns: avg(entries.map((e) => e.record.turns)),
    avgDurationMs: avg(entries.map((e) => e.record.durationMs)),
    mulliganRate: entries.filter((e) => e.seat.mulligans >= 1).length / games,
    avgMulligans: avg(entries.map((e) => e.seat.mulligans)),
    avgLandsTurn3: avg(entries.map((e) => landsAtTurn(e.seat.landsByTurn, 3))),
    avgLandsTurn5: avg(entries.map((e) => landsAtTurn(e.seat.landsByTurn, 5))),
    missedLandDropRate: entries.filter((e) => e.seat.missedLandDrops >= 1).length / games,
    avgMissedLandDrops: avg(entries.map((e) => e.seat.missedLandDrops)),
    avgSpells: avg(entries.map((e) => e.seat.spells)),
    avgSpellMana: avg(entries.map((e) => e.seat.spellMana)),
    ...(commanderTurns.length > 0 ? { avgCommanderTurn: avg(commanderTurns) } : {}),
    avgCommanderTax: avg(entries.map((e) => e.seat.commanderTax)),
    avgDamageDealt: avg(entries.map((e) => e.seat.damageDealt)),
    avgDamageTaken: avg(entries.map((e) => e.seat.damageTaken)),
    lossReasons,
    opponents: [...opponents.entries()]
      .map(([oDeck, v]) => ({ deck: oDeck, games: v.games, wins: v.wins }))
      .sort((a, b) => b.games - a.games || a.deck.localeCompare(b.deck)),
  };
}

/** Laender bei `turn` (0 = Zugbeginn vor dem ersten eigenen Zug); landsByTurn hat nur ownTurns+1
 * Eintraege - schied der Sitz vorher aus (oder das Spiel war kuerzer), gilt der letzte Eintrag. */
function landsAtTurn(landsByTurn: number[], turn: number): number {
  if (landsByTurn.length === 0) return 0;
  return landsByTurn[Math.min(turn, landsByTurn.length - 1)] ?? 0;
}

function avg(nums: number[]): number {
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

const WILSON_Z = 1.959963985; // 95 %

/** 95-%-Wilson-Score-Intervall fuer `wins` von `games`; [0, 0] bei 0 Partien (keine Division durch 0). */
export function wilson(wins: number, games: number): [number, number] {
  if (games === 0) return [0, 0];
  const p = wins / games;
  const z2 = WILSON_Z * WILSON_Z;
  const denom = 1 + z2 / games;
  const center = p + z2 / (2 * games);
  const margin = WILSON_Z * Math.sqrt((p * (1 - p)) / games + z2 / (4 * games * games));
  return [Math.max(0, (center - margin) / denom), Math.min(1, (center + margin) / denom)];
}
