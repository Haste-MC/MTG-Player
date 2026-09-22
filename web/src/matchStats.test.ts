import { describe, expect, it } from "vitest";
import { deckGames, summarize, wilson } from "./matchStats";
import type { MatchRecord } from "./protocol";

// Gleicher Inhalt wie fixtures/matches.json (Statistik-Screen/Screenshots, siehe scripts/shot.mjs) - hier als
// getypte Konstante, damit kein resolveJsonModule noetig ist (kein anderer Test im Repo importiert JSON).
const records: MatchRecord[] = [
  {
    id: "2026-09-10T18:02:11Z-a1b2", startedAt: "2026-09-10T18:02:11.000Z", endedAt: "2026-09-10T18:44:33.000Z",
    durationMs: 2542000, source: "live", turns: 12, reason: "AllOpponentsLost", draw: false, counted: true,
    excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true, ai: null, winner: true, lossReason: null,
        eliminatedTurn: null, mulligans: 0, lands: 10, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 8, 9],
        missedLandDrops: 0, firstMissedLandDrop: null, spells: 10, spellMana: 25, commanderCasts: 1,
        commanderTax: 0, firstCommanderTurn: 4, damageDealt: 21, damageTaken: 10, combatDamageTaken: 8,
        lifeEnd: 30, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false, ai: { mode: "sim", profile: "Default" },
        winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 12, mulligans: 1, lands: 9,
        landsByTurn: [0, 1, 2, 3, 4, 5, 5, 6, 7, 7, 8, 8, 9], missedLandDrops: 1, firstMissedLandDrop: 6,
        spells: 8, spellMana: 19, commanderCasts: 2, commanderTax: 2, firstCommanderTurn: 3, damageDealt: 10,
        damageTaken: 21, combatDamageTaken: 18, lifeEnd: 0, poisonEnd: 0,
      },
    ],
  },
  {
    id: "2026-09-12T20:15:44Z-c3d4", startedAt: "2026-09-12T20:15:44.000Z", endedAt: "2026-09-12T20:34:44.000Z",
    durationMs: 1150000, source: "live", turns: 9, reason: "AllOpponentsLost", draw: false, counted: true,
    excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true, ai: null, winner: false,
        lossReason: "LifeReachedZero", eliminatedTurn: 2, mulligans: 1, lands: 2, landsByTurn: [0, 1, 2],
        missedLandDrops: 1, firstMissedLandDrop: 2, spells: 4, spellMana: 9, commanderCasts: 0,
        commanderTax: 0, firstCommanderTurn: null, damageDealt: 8, damageTaken: 25, combatDamageTaken: 20,
        lifeEnd: 0, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Muldrotha Reanimator", human: false, ai: { mode: "sim", profile: "Default" },
        winner: true, lossReason: null, eliminatedTurn: null, mulligans: 0, lands: 9,
        landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 11, spellMana: 30, commanderCasts: 1, commanderTax: 0, firstCommanderTurn: 5,
        damageDealt: 25, damageTaken: 8, combatDamageTaken: 5, lifeEnd: 33, poisonEnd: 0,
      },
    ],
  },
  {
    id: "2026-09-14T09:05:00Z-e5f6", startedAt: "2026-09-14T09:05:00.000Z", endedAt: "2026-09-14T09:11:40.000Z",
    durationMs: 400000, source: "live", turns: 5, reason: "Conceded", draw: false, counted: false,
    excludeReason: "aufgegeben",
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true, ai: null, winner: false,
        lossReason: "Conceded", eliminatedTurn: 5, mulligans: 0, lands: 5, landsByTurn: [0, 1, 2, 3, 4, 5],
        missedLandDrops: 0, firstMissedLandDrop: null, spells: 3, spellMana: 6, commanderCasts: 0,
        commanderTax: 0, firstCommanderTurn: null, damageDealt: 2, damageTaken: 5, combatDamageTaken: 5,
        lifeEnd: 35, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false, ai: { mode: "sim", profile: "Default" },
        winner: true, lossReason: null, eliminatedTurn: null, mulligans: 0, lands: 5,
        landsByTurn: [0, 1, 2, 3, 4, 5], missedLandDrops: 0, firstMissedLandDrop: null, spells: 4,
        spellMana: 10, commanderCasts: 0, commanderTax: 0, firstCommanderTurn: null, damageDealt: 5,
        damageTaken: 2, combatDamageTaken: 2, lifeEnd: 38, poisonEnd: 0,
      },
    ],
  },
  {
    id: "2026-09-18T14:30:00Z-9a1c", startedAt: "2026-09-18T14:30:00.000Z", endedAt: "2026-09-18T14:45:00.000Z",
    durationMs: 900000, source: "live", turns: 8, reason: "AllOpponentsLost", draw: false, counted: true,
    excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Krenko Goblins", human: true, ai: null, winner: true, lossReason: null,
        eliminatedTurn: null, mulligans: 0, lands: 8, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8],
        missedLandDrops: 0, firstMissedLandDrop: null, spells: 14, spellMana: 20, commanderCasts: 3,
        commanderTax: 4, firstCommanderTurn: 2, damageDealt: 26, damageTaken: 9, combatDamageTaken: 9,
        lifeEnd: 29, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false, ai: { mode: "sim", profile: "Default" },
        winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 8, mulligans: 1, lands: 7,
        landsByTurn: [0, 1, 2, 3, 4, 5, 6, 6, 7], missedLandDrops: 1, firstMissedLandDrop: 5, spells: 9,
        spellMana: 18, commanderCasts: 1, commanderTax: 0, firstCommanderTurn: 4, damageDealt: 9,
        damageTaken: 26, combatDamageTaken: 20, lifeEnd: 0, poisonEnd: 0,
      },
    ],
  },
];

describe("wilson", () => {
  it("95%-Intervall fuer 7 von 10 Siegen", () => {
    const [lo, hi] = wilson(7, 10);
    expect(lo).toBeCloseTo(0.397, 3);
    expect(hi).toBeCloseTo(0.892, 3);
  });

  it("0 Partien -> [0, 0]", () => {
    expect(wilson(0, 0)).toEqual([0, 0]);
  });
});

describe("deckGames", () => {
  it("ignoriert nicht gewertete Partien und sortiert absteigend nach Partienzahl, dann Name", () => {
    expect(deckGames(records)).toEqual([
      { deck: "Meren Aristocrats", games: 2 },
      { deck: "Titania, Gaea Incarnate", games: 2 },
      { deck: "Krenko Goblins", games: 1 },
      { deck: "Muldrotha Reanimator", games: 1 },
    ]);
  });

  it("leere Eingabe -> leere Liste", () => {
    expect(deckGames([])).toEqual([]);
  });
});

describe("summarize", () => {
  it("Titania: Bilanz, Zuege, Mulligans, Laender bis Zug 3, Todesursachen, Gegner-Tabelle", () => {
    const s = summarize(records, "Titania, Gaea Incarnate");
    expect(s).toBeDefined();
    if (!s) return;
    expect(s.games).toBe(2);
    expect(s.wins).toBe(1);
    expect(s.losses).toBe(1);
    expect(s.draws).toBe(0);
    expect(s.winRate).toBeCloseTo(0.5, 6);
    expect(s.ci).toEqual(wilson(1, 2));
    expect(s.avgTurns).toBeCloseTo(10.5, 6);
    expect(s.avgDurationMs).toBeCloseTo(1846000, 6);
    expect(s.mulliganRate).toBeCloseTo(0.5, 6);
    expect(s.avgMulligans).toBeCloseTo(0.5, 6);
    // Zug 3: m1 landsByTurn[3]=3, m2 (nur 3 Eintraege, Sitz schied Zug 2 aus) geklemmt auf landsByTurn[2]=2
    expect(s.avgLandsTurn3).toBeCloseTo(2.5, 6);
    // Zug 5: m1 landsByTurn[5]=5, m2 geklemmt auf landsByTurn[2]=2
    expect(s.avgLandsTurn5).toBeCloseTo(3.5, 6);
    expect(s.missedLandDropRate).toBeCloseTo(0.5, 6);
    expect(s.avgMissedLandDrops).toBeCloseTo(0.5, 6);
    expect(s.avgSpells).toBeCloseTo(7, 6);
    expect(s.avgSpellMana).toBeCloseTo(17, 6);
    expect(s.avgCommanderTurn).toBeCloseTo(4, 6);
    expect(s.avgCommanderTax).toBeCloseTo(0, 6);
    expect(s.avgDamageDealt).toBeCloseTo(14.5, 6);
    expect(s.avgDamageTaken).toBeCloseTo(17.5, 6);
    expect(s.lossReasons).toEqual({ LifeReachedZero: 1 });
    expect(s.opponents).toEqual([
      { deck: "Meren Aristocrats", games: 1, wins: 1 },
      { deck: "Muldrotha Reanimator", games: 1, wins: 0 },
    ]);
  });

  it("Krenko Goblins: eine gewertete Partie", () => {
    const s = summarize(records, "Krenko Goblins");
    expect(s).toBeDefined();
    if (!s) return;
    expect(s.games).toBe(1);
    expect(s.wins).toBe(1);
    expect(s.losses).toBe(0);
    expect(s.avgCommanderTurn).toBe(2);
    expect(s.opponents).toEqual([{ deck: "Meren Aristocrats", games: 1, wins: 1 }]);
    expect(s.lossReasons).toEqual({});
  });

  it("Deck ohne gewertete Partien -> undefined", () => {
    const onlyConceded: MatchRecord[] = [
      {
        id: "x", startedAt: "s", endedAt: "e", durationMs: 1000, source: "live", turns: 3,
        reason: "Conceded", draw: false, counted: false, excludeReason: "aufgegeben",
        seats: [
          {
            name: "Du", deck: "Sparring Deck", human: true, winner: false, lossReason: "Conceded",
            mulligans: 0, lands: 1, landsByTurn: [0, 1], missedLandDrops: 0, spells: 0, spellMana: 0,
            commanderCasts: 0, commanderTax: 0, damageDealt: 0, damageTaken: 0, combatDamageTaken: 0,
            lifeEnd: 40, poisonEnd: 0,
          },
        ],
      },
    ];
    expect(summarize(onlyConceded, "Sparring Deck")).toBeUndefined();
    expect(summarize([], "Sparring Deck")).toBeUndefined();
  });

  it("avgCommanderTurn fehlt, wenn der Commander in keiner gewerteten Partie gewirkt wurde", () => {
    const noCast: MatchRecord[] = [
      {
        id: "y", startedAt: "s", endedAt: "e", durationMs: 1000, source: "live", turns: 6,
        reason: "AllOpponentsLost", draw: false, counted: true,
        seats: [
          {
            name: "Du", deck: "Ohne Commander-Cast", human: true, winner: true,
            mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6], missedLandDrops: 0,
            spells: 5, spellMana: 12, commanderCasts: 0, commanderTax: 0,
            damageDealt: 10, damageTaken: 5, combatDamageTaken: 5, lifeEnd: 40, poisonEnd: 0,
          },
          {
            name: "KI 1", deck: "Gegner", human: false, winner: false, lossReason: "LifeReachedZero",
            mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6], missedLandDrops: 0,
            spells: 5, spellMana: 12, commanderCasts: 0, commanderTax: 0,
            damageDealt: 5, damageTaken: 10, combatDamageTaken: 10, lifeEnd: 0, poisonEnd: 0,
          },
        ],
      },
    ];
    const s = summarize(noCast, "Ohne Commander-Cast");
    expect(s).toBeDefined();
    expect(s?.avgCommanderTurn).toBeUndefined();
  });
});
