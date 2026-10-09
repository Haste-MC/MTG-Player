import { describe, expect, it } from "vitest";
import { gameOverText, hasTeams, partnerOf, teamMembers } from "./teams";
import type { Snapshot } from "./protocol";

// team: undefined = Feld fehlt (die Bridge sendet es nur in Team-Partien), -1 = ausdruecklich kein Team.
const seat = (id: number, name: string, team?: number) => ({
  id, name, isAi: id !== 0, life: 40, commanderDamage: {}, hand: [], librarySize: 60,
  graveyard: [], exile: [], command: [], battlefield: [], manaPool: {}, hasPriority: false, team,
});

const state = { players: [seat(0, "You", 1), seat(1, "AI 1", 1), seat(2, "AI 2", 2)], me: 0 } as unknown as Snapshot;
const free = { players: [seat(0, "You", -1), seat(1, "AI 1", -1)], me: 0 } as unknown as Snapshot;
const absent = { players: [seat(0, "You"), seat(1, "AI 1")], me: 0 } as unknown as Snapshot;

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
});
