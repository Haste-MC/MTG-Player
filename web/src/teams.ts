import type { PlayerSnap, Snapshot } from "./protocol";

/** Hat der Sitz ein Team? Das Feld fehlt in Partien ohne Teams ganz, -1 heisst ebenfalls "kein Team". */
export function inTeam(p: PlayerSnap): boolean {
  return (p.team ?? -1) >= 0;
}

/** Partie mit Teams: mindestens ein Sitz traegt eine Nummer >= 0. */
export function hasTeams(state: Snapshot): boolean {
  return state.players.some(inTeam);
}

/** Alle Sitze eines Teams, in Sitzreihenfolge. */
export function teamMembers(state: Snapshot, team: number): PlayerSnap[] {
  return state.players.filter((p) => p.team === team);
}

/**
 * Der andere Sitz im Team von `me` (Standard: der eigene Sitz; im Zuschauer-Sitz gibt es keinen).
 * Ohne Teams, ohne den Sitz oder allein im Team: undefined.
 */
export function partnerOf(state: Snapshot, me: number | undefined = state.me): PlayerSnap | undefined {
  const seat = state.players.find((p) => p.id === me);
  if (!seat || !inTeam(seat)) return undefined;
  return state.players.find((p) => p.id !== seat.id && p.team === seat.team);
}

/**
 * Satz im Spielende-Dialog. Gewinnt ein Team, nennt er die Siegersitze einmal ("Team 1 wins — You and
 * AI 1"), auch wenn nur einer uebrig ist ("Team 1 wins — You"); ein ausgeschiedener Partner steht nicht
 * darin, denn er ist kein Sieger. Ein einzelner Sieger bleibt "You wins", ohne Sieger steht weiter
 * "Game over" (der Aufrufer entscheidet, ob daraus "Draw" wird). Die Bridge schickt nur das Kennzeichen
 * ("Team 1"), den Satz setzt der Browser zusammen.
 */
export function gameOverText(state: Snapshot, winner: string | null | undefined, winnerSeats: number[]): string {
  if (!winner) return "Game over";
  const names = state.players.filter((p) => winnerSeats.includes(p.id)).map((p) => p.name);
  const teamWin = state.players.some((p) => winnerSeats.includes(p.id) && inTeam(p) && winner === `Team ${p.team}`);
  if (teamWin && names.length > 0) return `${winner} wins — ${names.join(" and ")}`;
  return `${winner} wins`;
}

/**
 * Unter welchen Namen die Serie angezeigt wird: mit Teams je Team einmal ("Team 1", "Team 2", nach Nummer),
 * sonst je Sitz. Die Bridge meldet einen Teamsieg als winner "Team n" (store.ts zaehlt ihn unter genau dem),
 * ein Sitznamen-Eintrag wuerde in einer Team-Partie also nie Siege bekommen. Teams gibt es nur ganz oder
 * gar nicht (Teams.java: "entweder alle Sitze oder keiner"), freie Sitze neben Teams sind darum kein Fall.
 * Die Beschriftungen vermischen sich zwischen Serien nicht: humanTeam und team der Gegner stehen im
 * Serien-Schluessel (series.ts), ein Wechsel zwischen Teams und ohne Teams beginnt also eine neue Serie.
 */
export function seriesNames(state: Snapshot): string[] {
  if (!hasTeams(state)) return state.players.map((p) => p.name);
  const teams = state.players.filter(inTeam).map((p) => p.team as number);
  return [...new Set(teams)].sort((a, b) => a - b).map((t) => `Team ${t}`);
}
