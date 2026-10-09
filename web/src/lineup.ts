/**
 * Teamzuordnung der Lobby: NO_TEAM (-1) = kein Team. Reihenfolge wie die Sitze (Mensch zuerst, dann KI 1..n).
 *
 * Die Regeln spiegeln bridge/.../match/Teams.java genau: die Bridge lehnt alles andere mit einem Fehler ab,
 * und "Spiel starten" darf nie etwas senden, das dort abprallt.
 */

export const NO_TEAM = -1;
/** Hoechste Teamnummer, die die Bridge annimmt (Teams.MAX). */
export const MAX_TEAM = 6;
/** Teamnummern, die der Auswahlkasten der Lobby anbietet; mehr Teams als Sitzpaare gibt es nie. */
export const TEAM_CHOICES = [1, 2, 3];

/**
 * Warum die Aufstellung nicht startbar ist (deutscher Text fuer die Lobby), oder undefined, wenn sie gueltig ist.
 *
 * Gueltig ist: kein Sitz hat ein Team, oder alle haben eins, es sind mindestens zwei verschiedene und
 * mindestens ein Team hat zwei oder mehr Sitze. Ein Team aus nur einem Sitz ist ein Jeder-gegen-jeden-Sitz;
 * sind alle allein (zu zweit also "Team 1 gegen Team 2"), ist es kein Team-Spiel, sondern ein gewoehnliches
 * Duell - das lehnt die Bridge ab, also auch die Lobby.
 */
export function lineupProblem(teams: number[]): string | undefined {
  const gesetzt = teams.filter((t) => t !== NO_TEAM);
  if (gesetzt.length === 0) return undefined;
  if (gesetzt.some((t) => !Number.isInteger(t) || t < 1 || t > MAX_TEAM)) {
    return `Teams: Nummer 1 bis ${MAX_TEAM}.`;
  }
  if (gesetzt.length !== teams.length) return "Teams: entweder alle Sitze oder keiner.";
  const verschieden = new Set(teams).size;
  if (verschieden < 2) return "Teams: mindestens zwei verschiedene Teams.";
  if (verschieden === teams.length) return "Teams: mindestens ein Team braucht zwei Sitze.";
  return undefined;
}

export function lineupValid(teams: number[]): boolean {
  return lineupProblem(teams) === undefined;
}

/** "Team 1: You + AI 1 — Team 2: AI 2 + AI 3"; ohne Teams der leere String. */
export function lineupText(names: string[], teams: number[]): string {
  if (teams.every((t) => t === NO_TEAM)) return "";
  const groups = new Map<number, string[]>();
  teams.forEach((t, i) => {
    if (t === NO_TEAM) return;
    groups.set(t, [...(groups.get(t) ?? []), names[i] ?? `Sitz ${i + 1}`]);
  });
  return [...groups.entries()]
    .sort(([a], [b]) => a - b)
    .map(([t, members]) => `Team ${t}: ${members.join(" + ")}`)
    .join(" — ");
}

/** Schnellwahl: genau vier Sitze werden zu 1/1/2/2, sonst gibt es nichts zu setzen. */
export function twoVsTwo(seatCount: number): number[] | undefined {
  return seatCount === 4 ? [1, 1, 2, 2] : undefined;
}

/*
 * Die Lobby haelt die Teams getrennt vom Sitzplan: eine Zahl fuer den Menschen und eine Liste im
 * Gleichschritt mit den KI-Sitzen. Die Reihenfolge der Nachricht (Mensch zuerst, im Zuschauer-Modus
 * nur KIs) wird erst hier daraus gebaut - so kann beim Umschalten des Zuschauer-Modus oder beim
 * Hinzufuegen und Entfernen eines Sitzes kein Eintrag fuer einen Sitz uebrig bleiben, den es nicht mehr gibt.
 */

/** Teams in Sitzreihenfolge der Nachricht. */
export function seatTeams(spectate: boolean, humanTeam: number, aiTeams: number[]): number[] {
  return spectate ? [...aiTeams] : [humanTeam, ...aiTeams];
}

/** Auf genau `count` KI-Sitze bringen: fehlende ohne Team, ueberzaehlige weg. */
export function fitTeams(aiTeams: number[], count: number): number[] {
  return Array.from({ length: count }, (_, i) => aiTeams[i] ?? NO_TEAM);
}

export function withoutSeat(aiTeams: number[], index: number): number[] {
  return aiTeams.filter((_, i) => i !== index);
}

/** Gegenstueck zu seatTeams: eine Aufstellung in Sitzreihenfolge zurueck auf Mensch und KIs verteilen. */
export function splitSeats(spectate: boolean, teams: number[], humanTeam: number): { humanTeam: number; aiTeams: number[] } {
  return spectate
    ? { humanTeam, aiTeams: teams }
    : { humanTeam: teams[0] ?? NO_TEAM, aiTeams: teams.slice(1) };
}
