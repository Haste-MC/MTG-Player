import { describe, expect, it } from "vitest";
import { gameOverText, hasTeams, partnerOf, seriesNames, teamMembers } from "./teams";
import { formatSeries, recordResult, startSeries } from "./series";
import type { Snapshot, StartGame } from "./protocol";

// team: undefined = Feld fehlt (die Bridge sendet es nur in Team-Partien), -1 = ausdruecklich kein Team.
const seat = (id: number, name: string, team?: number) => ({
  id, name, isAi: id !== 0, life: 40, commanderDamage: {}, hand: [], librarySize: 60,
  graveyard: [], exile: [], command: [], battlefield: [], manaPool: {}, hasPriority: false, team,
});

const state = { players: [seat(0, "You", 1), seat(1, "AI 1", 1), seat(2, "AI 2", 2)], me: 0 } as unknown as Snapshot;
const free = { players: [seat(0, "You", -1), seat(1, "AI 1", -1)], me: 0 } as unknown as Snapshot;
const absent = { players: [seat(0, "You"), seat(1, "AI 1")], me: 0 } as unknown as Snapshot;

const opp = (team?: number) => ({ precon: "B", name: "KI", team });
const teamMsg: StartGame = { type: "startGame", humanDeck: { precon: "A" }, humanTeam: 1, opponents: [opp(1), opp(2)] };
const freeMsg: StartGame = { type: "startGame", humanDeck: { precon: "A" }, opponents: [opp(), opp()] };

describe("teams", () => {
  it("erkennt eine Partie mit Teams", () => {
    expect(hasTeams(state)).toBe(true);
    expect(hasTeams(free)).toBe(false);
  });

  it("kennt ohne team-Feld keine Teams - das Feld fehlt in Partien ohne Teams", () => {
    expect(hasTeams(absent)).toBe(false);
    expect(partnerOf(absent)).toBeUndefined();
    expect(teamMembers(absent, 1)).toEqual([]);
  });

  it("findet den Partner des eigenen Sitzes", () => {
    expect(partnerOf(state)?.name).toBe("AI 1");
    expect(partnerOf(free)).toBeUndefined();
  });

  it("findet den Partner eines anderen Sitzes, wenn me angegeben ist", () => {
    expect(partnerOf(state, 1)?.name).toBe("You");
  });

  it("hat allein im Team keinen Partner", () => {
    expect(partnerOf(state, 2)).toBeUndefined();
  });

  it("listet die Mitglieder eines Teams", () => {
    expect(teamMembers(state, 1).map((p) => p.name)).toEqual(["You", "AI 1"]);
  });

  it("schreibt den Spielende-Satz", () => {
    expect(gameOverText(state, "Team 1", [0, 1])).toBe("Team 1 wins — You and AI 1");
    expect(gameOverText(free, "You", [0])).toBe("You wins");
    expect(gameOverText(state, null, [])).toBe("Game over");
  });

  it("nennt bei einem Teamsieg auch einen einzelnen Ueberlebenden - und keinen toten Partner", () => {
    // Sitz 2 (Team 2) lebt allein: der Satz bleibt gleich gebaut wie bei zwei Siegern, nur mit einem Namen.
    expect(gameOverText(state, "Team 2", [2])).toBe("Team 2 wins — AI 2");
    // Sitz 1 ist ausgeschieden: er steht weder im Satz noch unter den Siegern des Datensatzes.
    expect(gameOverText(state, "Team 1", [0])).toBe("Team 1 wins — You");
  });

  it("haengt einem einzelnen Sieger ohne Team keinen zweiten Namen an", () => {
    expect(gameOverText(free, "You", [0])).toBe("You wins");
    // auch in einer Team-Partie: heisst der Sieger wie ein Sitz und nicht wie ein Team, ist er ein Einzelner
    expect(gameOverText(state, "You", [0])).toBe("You wins");
  });

  it("bleibt bei einem Teamsieg ohne Sitzliste beim Teamnamen", () => {
    expect(gameOverText(state, "Team 1", [])).toBe("Team 1 wins");
  });

  it("beschriftet die Serie je Team, nicht je Sitz: sonst stuenden in einer 2v2-Serie vier Sitze mit 0 Siegen, "
    + "weil die Bridge Teamsiege unter \"Team n\" meldet", () => {
    expect(seriesNames(state)).toEqual(["Team 1", "Team 2"]);
  });

  it("sortiert die Teams nach Nummer, nicht nach Sitzreihenfolge, und nennt jedes nur einmal", () => {
    const umgekehrt = { players: [seat(0, "You", 2), seat(1, "AI 1", 1), seat(2, "AI 2", 2), seat(3, "AI 3", 1)], me: 0 } as unknown as Snapshot;
    expect(seriesNames(umgekehrt)).toEqual(["Team 1", "Team 2"]);
  });

  it("beschriftet ohne Teams weiter je Sitz - mit -1 wie mit fehlendem Feld", () => {
    expect(seriesNames(free)).toEqual(["You", "AI 1"]);
    expect(seriesNames(absent)).toEqual(["You", "AI 1"]);
  });

  it("zeigt den Serienstand eines Team-Matches mit den Teams und ihren Siegen", () => {
    let s = startSeries(undefined, teamMsg);
    s = recordResult(s, "Team 2");
    s = recordResult(s, "Team 1");
    s = recordResult(s, "Team 2");
    expect(formatSeries(s, seriesNames(state))).toBe("Team 1 1 · Team 2 2");
  });

  it("laesst einen Team-Sieg ohne Eintrag auf 0 stehen, bis er faellt - der Anfangsstand nennt beide Teams", () => {
    expect(formatSeries(startSeries(undefined, teamMsg), seriesNames(state))).toBe("Team 1 0 · Team 2 0");
  });

  it("mischt die Beschriftungen nicht, wenn eine Serie ohne Teams mit Teams weiterlaeuft (und umgekehrt): "
    + "humanTeam und team der Gegner stehen im Serien-Schluessel, die Serie beginnt also neu", () => {
    const ohne = recordResult(startSeries(undefined, freeMsg), "You");
    const mit = startSeries(ohne, teamMsg);
    expect(mit).not.toBe(ohne);
    expect(mit.wins).toEqual({});
    expect(formatSeries(mit, seriesNames(state))).toBe("Team 1 0 · Team 2 0");
    const zurueck = startSeries(recordResult(mit, "Team 1"), freeMsg);
    expect(zurueck.wins).toEqual({});
    expect(formatSeries(zurueck, seriesNames(free))).toBe("You 0 · AI 1 0");
  });
});
