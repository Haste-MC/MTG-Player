import { describe, expect, it } from "vitest";
import { deckGames, formatOf, summarize, wilson } from "./matchStats";
import type { MatchRecord, MatchSeat } from "./protocol";

// Gleicher Inhalt wie fixtures/matches.json (Statistik-Screen/Screenshots, siehe scripts/shot.mjs) - hier als
// getypte Konstante, damit kein resolveJsonModule noetig ist (kein anderer Test im Repo importiert JSON).
// ACHTUNG: beide Dateien von Hand synchron halten - eine Aenderung hier ohne die dortige (oder umgekehrt)
// faellt bei keinem Build/Test automatisch auf. Die Daten sind bewusst plausibel gehalten: landsByTurn hat
// genau ownTurns+1 Eintraege (eigene Zuege, nicht Partiezuege), eliminatedTurn steht ueberall dort, wo ein
// lossReason steht, und die Schadenssummen gehen auf.
//
// Die ersten neun Partien sind v1 (Vorfall-Kennzahlen und Zeitachse gab es da noch nicht - weglassen
// statt Nullen hinschreiben, siehe MatchSeat), die letzten beiden v2: eine Duell- und eine Pod-Partie mit
// gefuellten Vorfall-Feldern. Dort gehen auch die v2-Summen auf: damageTakenFlying + ...Trample +
// ...Other == combatDamageTaken, plus damageTakenNonCombat == damageTaken, damageDealtCombat +
// damageDealtNonCombat == damageDealt, 40 + lifeGained - damageTaken == lifeEnd, die Zeitachse hat genau
// einen Punkt je eigenem Zug, ihre spells summieren sich auf seat.spells und ihr letztes hand ist handEnd.
// (Die echte matches-Nachricht schickt die Zeitachse NICHT mit - die Fixture traegt sie, damit die
// Screenshots die Kurve ohne matchDetail-Runde zeigen koennen.)
const records: MatchRecord[] = [
  {
    v: 1, id: "2026-09-10T18:44:33.118Z-4b1d07", startedAt: "2026-09-10T18:02:11.000Z", endedAt: "2026-09-10T18:44:33.000Z",
    durationMs: 2542000, source: "live", turns: 12, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true,
        ai: null, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 10, spellMana: 25, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 4, damageDealt: 40, damageTaken: 12,
        combatDamageTaken: 10, lifeEnd: 28, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 12,
        mulligans: 1, lands: 5, landsByTurn: [0, 1, 2, 3, 4, 5, 5],
        missedLandDrops: 1, firstMissedLandDrop: 6,
        spells: 8, spellMana: 19, commanderCasts: 2, commanderTax: 2,
        firstCommanderTurn: 3, damageDealt: 12, damageTaken: 40,
        combatDamageTaken: 34, lifeEnd: 0, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-12T20:48:44.902Z-4b1d08", startedAt: "2026-09-12T20:15:44.000Z", endedAt: "2026-09-12T20:48:44.000Z",
    durationMs: 1980000, source: "live", turns: 11, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true,
        ai: null, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 5,
        mulligans: 1, lands: 1, landsByTurn: [0, 1, 1],
        missedLandDrops: 1, firstMissedLandDrop: 2,
        spells: 3, spellMana: 5, commanderCasts: 0, commanderTax: 0,
        firstCommanderTurn: null, damageDealt: 6, damageTaken: 40,
        combatDamageTaken: 33, lifeEnd: 0, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Muldrotha Reanimator", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 9, spellMana: 22, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 4, damageDealt: 74, damageTaken: 14,
        combatDamageTaken: 11, lifeEnd: 26, poisonEnd: 0,
      },
      {
        name: "KI 2", deck: "Atraxa Superfriends", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 11,
        mulligans: 0, lands: 3, landsByTurn: [0, 1, 2, 3],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 7, spellMana: 18, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 3, damageDealt: 14, damageTaken: 40,
        combatDamageTaken: 30, lifeEnd: 0, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-14T09:11:40.517Z-4b1d09", startedAt: "2026-09-14T09:05:00.000Z", endedAt: "2026-09-14T09:11:40.000Z",
    durationMs: 400000, source: "live", turns: 5, reason: "Conceded",
    draw: false, counted: false, excludeReason: "aufgegeben",
    seats: [
      {
        name: "Du", deck: "Titania, Gaea Incarnate", human: true,
        ai: null, winner: false, lossReason: "Conceded", eliminatedTurn: 5,
        mulligans: 0, lands: 3, landsByTurn: [0, 1, 2, 3],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 3, spellMana: 6, commanderCasts: 0, commanderTax: 0,
        firstCommanderTurn: null, damageDealt: 2, damageTaken: 5,
        combatDamageTaken: 5, lifeEnd: 35, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 2, landsByTurn: [0, 1, 2],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 4, spellMana: 10, commanderCasts: 0, commanderTax: 0,
        firstCommanderTurn: null, damageDealt: 5, damageTaken: 2,
        combatDamageTaken: 2, lifeEnd: 38, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-18T14:45:00.264Z-4b1d0a", startedAt: "2026-09-18T14:30:00.000Z", endedAt: "2026-09-18T14:45:00.000Z",
    durationMs: 900000, source: "live", turns: 8, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Krenko Goblins", human: true,
        ai: null, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 14, spellMana: 20, commanderCasts: 3, commanderTax: 4,
        firstCommanderTurn: 2, damageDealt: 40, damageTaken: 11,
        combatDamageTaken: 11, lifeEnd: 29, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Meren Aristocrats", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 8,
        mulligans: 1, lands: 3, landsByTurn: [0, 1, 2, 3, 3],
        missedLandDrops: 1, firstMissedLandDrop: 4,
        spells: 9, spellMana: 18, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 4, damageDealt: 11, damageTaken: 40,
        combatDamageTaken: 34, lifeEnd: 0, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-20T10:11:40.883Z-4b1d0b", startedAt: "2026-09-20T10:00:00.000Z", endedAt: "2026-09-20T10:11:40.000Z",
    durationMs: 700000, source: "sparring", turns: 7, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Sparring-KI", deck: "Krenko Goblins", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 7,
        mulligans: 1, lands: 3, landsByTurn: [0, 1, 2, 3, 3],
        missedLandDrops: 1, firstMissedLandDrop: 4,
        spells: 9, spellMana: 16, commanderCasts: 2, commanderTax: 2,
        firstCommanderTurn: 3, damageDealt: 22, damageTaken: 40,
        combatDamageTaken: 34, lifeEnd: 0, poisonEnd: 0,
      },
      {
        name: "Sparring-Gegner", deck: "Grix Control", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 3, landsByTurn: [0, 1, 2, 3],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 12, spellMana: 29, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 3, damageDealt: 40, damageTaken: 22,
        combatDamageTaken: 18, lifeEnd: 18, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-21T16:40:00.031Z-4b1d0c", startedAt: "2026-09-21T16:00:00.000Z", endedAt: "2026-09-21T16:40:00.000Z",
    durationMs: 2400000, source: "live", turns: 20, reason: "IntentionalDraw",
    draw: true, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Ojutai Flyers", human: true,
        ai: null, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 20,
        mulligans: 0, lands: 10, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 14, spellMana: 27, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 5, damageDealt: 9, damageTaken: 9,
        combatDamageTaken: 7, lifeEnd: 31, poisonEnd: 0,
      },
      {
        name: "KI 1", deck: "Golgari Midrange", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 20,
        mulligans: 1, lands: 9, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9],
        missedLandDrops: 1, firstMissedLandDrop: 10,
        spells: 13, spellMana: 24, commanderCasts: 0, commanderTax: 0,
        firstCommanderTurn: null, damageDealt: 9, damageTaken: 9,
        combatDamageTaken: 7, lifeEnd: 31, poisonEnd: 0,
      },
    ],
  },
  // Zugdeckel: die Bridge hat die Sparring-Partie nach der maximalen Zugzahl abgeschnitten (AiMatch setzt
  // dafuer alle Sitze auf intentionalDraw). Der Ausgang bleibt ein echtes Remis ohne Sieger, gewertet wird
  // die Partie aber nicht - sie zaehlt nur in turnCappedGames/turnCappedRate.
  {
    v: 1, id: "2026-09-22T11:20:00.402Z-4b1d0d", startedAt: "2026-09-22T10:40:00.000Z", endedAt: "2026-09-22T11:20:00.000Z",
    durationMs: 2400000, source: "sparring", turns: 40, reason: "Draw",
    draw: true, counted: false, excludeReason: "Zugdeckel",
    seats: [
      {
        name: "Sparring-KI", deck: "Krenko Goblins", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 0, lands: 11, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11],
        missedLandDrops: 9, firstMissedLandDrop: 12,
        spells: 22, spellMana: 41, commanderCasts: 2, commanderTax: 2,
        firstCommanderTurn: 4, damageDealt: 14, damageTaken: 9,
        combatDamageTaken: 7, lifeEnd: 31, poisonEnd: 0,
      },
      {
        name: "Sparring-Gegner", deck: "Grix Control", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 1, lands: 10, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10],
        missedLandDrops: 10, firstMissedLandDrop: 11,
        spells: 20, spellMana: 45, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 5, damageDealt: 9, damageTaken: 14,
        combatDamageTaken: 12, lifeEnd: 26, poisonEnd: 0,
      },
    ],
  },
  // "Lantern Stax" endet AUSSCHLIESSLICH am Zugdeckel - genau der Fall, den die Statistik zeigen soll:
  // ohne gewertete Partie gibt es keine Bilanz, das Deck muss trotzdem in der Deckliste auftauchen.
  {
    v: 1, id: "2026-09-22T18:30:00.110Z-4b1d0e", startedAt: "2026-09-22T17:50:00.000Z", endedAt: "2026-09-22T18:30:00.000Z",
    durationMs: 2400000, source: "sparring", turns: 40, reason: "Draw",
    draw: true, counted: false, excludeReason: "Zugdeckel",
    seats: [
      {
        name: "Sparring-KI", deck: "Lantern Stax", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 0, lands: 12, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12],
        missedLandDrops: 8, firstMissedLandDrop: 13,
        spells: 18, spellMana: 33, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 6, damageDealt: 4, damageTaken: 6,
        combatDamageTaken: 4, lifeEnd: 36, poisonEnd: 0,
      },
      {
        name: "Sparring-Gegner", deck: "Atraxa Superfriends", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 1, lands: 11, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11],
        missedLandDrops: 9, firstMissedLandDrop: 12,
        spells: 17, spellMana: 38, commanderCasts: 2, commanderTax: 2,
        firstCommanderTurn: 5, damageDealt: 6, damageTaken: 4,
        combatDamageTaken: 2, lifeEnd: 34, poisonEnd: 0,
      },
    ],
  },
  {
    v: 1, id: "2026-09-23T09:15:00.550Z-4b1d0f", startedAt: "2026-09-23T08:35:00.000Z", endedAt: "2026-09-23T09:15:00.000Z",
    durationMs: 2400000, source: "sparring", turns: 40, reason: "Draw",
    draw: true, counted: false, excludeReason: "Zugdeckel",
    seats: [
      {
        name: "Sparring-KI", deck: "Lantern Stax", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 1, lands: 13, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 13, 13, 13, 13, 13, 13, 13],
        missedLandDrops: 7, firstMissedLandDrop: 14,
        spells: 21, spellMana: 39, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 7, damageDealt: 2, damageTaken: 5,
        combatDamageTaken: 3, lifeEnd: 35, poisonEnd: 0,
      },
      {
        name: "Sparring-Gegner", deck: "Ojutai Flyers", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "IntentionalDraw", eliminatedTurn: 40,
        mulligans: 0, lands: 12, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 12, 12, 12, 12, 12, 12, 12, 12],
        missedLandDrops: 8, firstMissedLandDrop: 13,
        spells: 19, spellMana: 36, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 6, damageDealt: 5, damageTaken: 2,
        combatDamageTaken: 0, lifeEnd: 38, poisonEnd: 0,
      },
    ],
  },
  {
    v: 2, id: "2026-09-23T21:05:00.700Z-4b1d10", startedAt: "2026-09-23T20:25:00.000Z", endedAt: "2026-09-23T21:05:00.000Z",
    durationMs: 2400000, source: "live", aiTimeout: 5, turns: 14, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Ojutai Flyers", human: true,
        ai: null, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 14,
        mulligans: 1, lands: 7, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 11, spellMana: 24, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 4, damageDealt: 25, damageTaken: 43,
        combatDamageTaken: 36, lifeEnd: 0, poisonEnd: 0,
        spellsCountered: 2, spellsFizzled: 1, counterspellsCast: 1, removalCast: 3,
        cardsDrawn: 12, cardsDiscarded: 3, cardsMilled: 0, openingLands: 3, handEnd: 4,
        permanentsLost: 6, creaturesLostInCombat: 3, creaturesLostOther: 2,
        biggestSweep: 3, sweepsSuffered: 1, tokensCreated: 2,
        attacksDeclared: 5, attackedTurns: 3, attackersFaced: 12, blocksDeclared: 4,
        damageTakenFlying: 18, damageTakenTrample: 6, damageTakenOther: 12, damageTakenNonCombat: 7,
        damageDealtCombat: 20, damageDealtNonCombat: 5, commanderDamageTaken: 9, lifeGained: 3,
        timeline: [
          { turn: 1, lands: 1, creatures: 0, life: 40, hand: 4, spells: 1 },
          { turn: 3, lands: 2, creatures: 1, life: 40, hand: 4, spells: 1 },
          { turn: 5, lands: 3, creatures: 1, life: 38, hand: 3, spells: 2 },
          { turn: 7, lands: 4, creatures: 2, life: 33, hand: 3, spells: 2 },
          { turn: 9, lands: 5, creatures: 3, life: 25, hand: 4, spells: 2 },
          { turn: 11, lands: 6, creatures: 1, life: 16, hand: 3, spells: 2 },
          { turn: 13, lands: 7, creatures: 1, life: 7, hand: 4, spells: 1 },
        ],
      },
      {
        name: "KI 1", deck: "Grix Control", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 7, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 13, spellMana: 30, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 5, damageDealt: 43, damageTaken: 25,
        combatDamageTaken: 20, lifeEnd: 15, poisonEnd: 0,
        spellsCountered: 0, spellsFizzled: 0, counterspellsCast: 3, removalCast: 5,
        cardsDrawn: 16, cardsDiscarded: 2, cardsMilled: 0, openingLands: 4, handEnd: 5,
        permanentsLost: 2, creaturesLostInCombat: 1, creaturesLostOther: 1,
        biggestSweep: 0, sweepsSuffered: 0, tokensCreated: 0,
        attacksDeclared: 12, attackedTurns: 5, attackersFaced: 5, blocksDeclared: 2,
        damageTakenFlying: 9, damageTakenTrample: 0, damageTakenOther: 11, damageTakenNonCombat: 5,
        damageDealtCombat: 36, damageDealtNonCombat: 7, commanderDamageTaken: 0, lifeGained: 0,
        timeline: [
          { turn: 2, lands: 1, creatures: 0, life: 40, hand: 6, spells: 1 },
          { turn: 4, lands: 2, creatures: 0, life: 40, hand: 6, spells: 2 },
          { turn: 6, lands: 3, creatures: 1, life: 38, hand: 6, spells: 2 },
          { turn: 8, lands: 4, creatures: 2, life: 33, hand: 5, spells: 2 },
          { turn: 10, lands: 5, creatures: 3, life: 28, hand: 5, spells: 2 },
          { turn: 12, lands: 6, creatures: 3, life: 20, hand: 5, spells: 2 },
          { turn: 14, lands: 7, creatures: 4, life: 15, hand: 5, spells: 2 },
        ],
      },
    ],
  },
  {
    v: 2, id: "2026-09-24T12:40:00.910Z-4b1d11", startedAt: "2026-09-24T11:50:00.000Z", endedAt: "2026-09-24T12:40:00.000Z",
    durationMs: 3000000, source: "live", aiTimeout: 5, turns: 18, reason: "AllOpponentsLost",
    draw: false, counted: true, excludeReason: null,
    seats: [
      {
        name: "Du", deck: "Atraxa Superfriends", human: true,
        ai: null, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 12,
        mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 7, spellMana: 16, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 4, damageDealt: 10, damageTaken: 40,
        combatDamageTaken: 32, lifeEnd: 0, poisonEnd: 0,
        spellsCountered: 1, spellsFizzled: 0, counterspellsCast: 0, removalCast: 2,
        cardsDrawn: 8, cardsDiscarded: 2, cardsMilled: 0, openingLands: 2, handEnd: 5,
        permanentsLost: 7, creaturesLostInCombat: 2, creaturesLostOther: 4,
        biggestSweep: 4, sweepsSuffered: 2, tokensCreated: 3,
        attacksDeclared: 2, attackedTurns: 1, attackersFaced: 7, blocksDeclared: 3,
        damageTakenFlying: 6, damageTakenTrample: 14, damageTakenOther: 12, damageTakenNonCombat: 8,
        damageDealtCombat: 6, damageDealtNonCombat: 4, commanderDamageTaken: 14, lifeGained: 0,
        timeline: [
          { turn: 1, lands: 1, creatures: 0, life: 40, hand: 6, spells: 1 },
          { turn: 4, lands: 2, creatures: 1, life: 40, hand: 5, spells: 2 },
          { turn: 7, lands: 3, creatures: 2, life: 31, hand: 5, spells: 2 },
          { turn: 10, lands: 4, creatures: 2, life: 14, hand: 5, spells: 2 },
        ],
      },
      {
        name: "KI 1", deck: "Muldrotha Reanimator", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: true, lossReason: null, eliminatedTurn: null,
        mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6],
        missedLandDrops: 0, firstMissedLandDrop: null,
        spells: 15, spellMana: 34, commanderCasts: 2, commanderTax: 2,
        firstCommanderTurn: 5, damageDealt: 64, damageTaken: 19,
        combatDamageTaken: 13, lifeEnd: 26, poisonEnd: 0,
        spellsCountered: 0, spellsFizzled: 1, counterspellsCast: 0, removalCast: 4,
        cardsDrawn: 19, cardsDiscarded: 4, cardsMilled: 6, openingLands: 3, handEnd: 3,
        permanentsLost: 5, creaturesLostInCombat: 3, creaturesLostOther: 2,
        biggestSweep: 2, sweepsSuffered: 0, tokensCreated: 1,
        attacksDeclared: 14, attackedTurns: 5, attackersFaced: 4, blocksDeclared: 3,
        damageTakenFlying: 4, damageTakenTrample: 0, damageTakenOther: 9, damageTakenNonCombat: 6,
        damageDealtCombat: 55, damageDealtNonCombat: 9, commanderDamageTaken: 0, lifeGained: 5,
        timeline: [
          { turn: 2, lands: 1, creatures: 0, life: 40, hand: 6, spells: 1 },
          { turn: 5, lands: 2, creatures: 1, life: 40, hand: 6, spells: 2 },
          { turn: 8, lands: 3, creatures: 2, life: 36, hand: 5, spells: 3 },
          { turn: 11, lands: 4, creatures: 4, life: 32, hand: 4, spells: 3 },
          { turn: 14, lands: 5, creatures: 5, life: 28, hand: 3, spells: 3 },
          { turn: 17, lands: 6, creatures: 5, life: 26, hand: 3, spells: 3 },
        ],
      },
      {
        name: "KI 2", deck: "Golgari Midrange", human: false,
        ai: { mode: "sim", profile: "Default" }, winner: false, lossReason: "LifeReachedZero", eliminatedTurn: 18,
        mulligans: 1, lands: 5, landsByTurn: [0, 1, 2, 3, 4, 5, 5],
        missedLandDrops: 1, firstMissedLandDrop: 18,
        spells: 12, spellMana: 27, commanderCasts: 1, commanderTax: 0,
        firstCommanderTurn: 6, damageDealt: 17, damageTaken: 42,
        combatDamageTaken: 36, lifeEnd: 0, poisonEnd: 0,
        spellsCountered: 0, spellsFizzled: 0, counterspellsCast: 0, removalCast: 3,
        cardsDrawn: 14, cardsDiscarded: 3, cardsMilled: 0, openingLands: 3, handEnd: 2,
        permanentsLost: 6, creaturesLostInCombat: 4, creaturesLostOther: 2,
        biggestSweep: 3, sweepsSuffered: 1, tokensCreated: 0,
        attacksDeclared: 9, attackedTurns: 4, attackersFaced: 14, blocksDeclared: 5,
        damageTakenFlying: 10, damageTakenTrample: 8, damageTakenOther: 18, damageTakenNonCombat: 6,
        damageDealtCombat: 13, damageDealtNonCombat: 4, commanderDamageTaken: 16, lifeGained: 2,
        timeline: [
          { turn: 3, lands: 1, creatures: 0, life: 40, hand: 5, spells: 1 },
          { turn: 6, lands: 2, creatures: 1, life: 40, hand: 5, spells: 2 },
          { turn: 9, lands: 3, creatures: 2, life: 34, hand: 4, spells: 2 },
          { turn: 12, lands: 4, creatures: 3, life: 26, hand: 4, spells: 3 },
          { turn: 15, lands: 5, creatures: 3, life: 12, hand: 3, spells: 2 },
          { turn: 18, lands: 5, creatures: 2, life: 4, hand: 2, spells: 2 },
        ],
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
  it("zaehlt gewertete und Zugdeckel-Partien getrennt und sortiert nach beidem zusammen, dann Name", () => {
    // Sortierschluessel ist games + capped: "Lantern Stax" hat keine einzige gewertete Partie, steht aber
    // mit seinen zwei Deckel-Partien gleichauf mit einem Deck, das zweimal gewertet gespielt hat.
    expect(deckGames(records)).toEqual([
      { deck: "Atraxa Superfriends", games: 2, capped: 1 },
      { deck: "Grix Control", games: 2, capped: 1 },
      { deck: "Krenko Goblins", games: 2, capped: 1 },
      { deck: "Ojutai Flyers", games: 2, capped: 1 },
      { deck: "Golgari Midrange", games: 2, capped: 0 },
      { deck: "Lantern Stax", games: 0, capped: 2 },
      { deck: "Meren Aristocrats", games: 2, capped: 0 },
      { deck: "Muldrotha Reanimator", games: 2, capped: 0 },
      { deck: "Titania, Gaea Incarnate", games: 2, capped: 0 },
    ]);
  });

  it("ignoriert Partien, die aus einem anderen Grund nicht gewertet sind", () => {
    // Die aufgegebene Partie (Titania gegen Meren) taucht in keiner der beiden Zahlen auf.
    const titania = deckGames(records).find((d) => d.deck === "Titania, Gaea Incarnate");
    expect(titania).toEqual({ deck: "Titania, Gaea Incarnate", games: 2, capped: 0 });
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
    expect(s.avgTurns).toBeCloseTo(11.5, 6);
    expect(s.avgDurationMs).toBeCloseTo(2261000, 6);
    expect(s.mulliganRate).toBeCloseTo(0.5, 6);
    expect(s.avgMulligans).toBeCloseTo(0.5, 6);
    // Nur m1 zaehlt: der Titania-Sitz in m2 schied in seinem 2. eigenen Zug aus (landsByTurn hat 3
    // Eintraege) und hatte weder einen eigenen Zug 3 noch 5 - solche Partien werden ausgelassen.
    expect(s.avgLandsTurn3).toBeCloseTo(3, 6);
    expect(s.avgLandsTurn5).toBeCloseTo(5, 6);
    expect(s.missedLandDropRate).toBeCloseTo(0.5, 6);
    expect(s.avgMissedLandDrops).toBeCloseTo(0.5, 6);
    expect(s.avgSpells).toBeCloseTo(6.5, 6);
    expect(s.avgSpellMana).toBeCloseTo(15, 6);
    expect(s.avgCommanderTurn).toBeCloseTo(4, 6);
    expect(s.avgCommanderTax).toBeCloseTo(0, 6);
    expect(s.avgDamageDealt).toBeCloseTo(23, 6);
    expect(s.avgDamageTaken).toBeCloseTo(26, 6);
    expect(s.lossReasons).toEqual({ LifeReachedZero: 1 });
    expect(s.turnCappedGames).toBe(0);
    expect(s.turnCappedRate).toBe(0);
    // m2 ist eine Dreierpartie: beide Mitspieler stehen je einmal in der Gegner-Tabelle.
    expect(s.opponents).toEqual([
      { deck: "Atraxa Superfriends", games: 1, wins: 0 },
      { deck: "Meren Aristocrats", games: 1, wins: 1 },
      { deck: "Muldrotha Reanimator", games: 1, wins: 0 },
    ]);
  });

  it("Krenko Goblins: zwei gewertete Partien, eine davon Sparring (kein menschlicher Sitz)", () => {
    const s = summarize(records, "Krenko Goblins");
    expect(s).toBeDefined();
    if (!s) return;
    // m4 (Du, Sieg) + die Sparring-Partie (Sparring-KI, Niederlage) - source/human sind fuer die
    // Auswertung unerheblich, beide zaehlen normal.
    expect(s.games).toBe(2);
    expect(s.wins).toBe(1);
    expect(s.losses).toBe(1);
    expect(s.draws).toBe(0);
    expect(s.avgTurns).toBeCloseTo(7.5, 6);
    expect(s.avgCommanderTurn).toBeCloseTo(2.5, 6);
    expect(s.avgCommanderTax).toBeCloseTo(3, 6);
    // Beide Krenko-Sitze hatten 4 eigene Zuege: Zug 3 zaehlt, Zug 5 gab es nie -> Kennzahl fehlt ("–").
    expect(s.avgLandsTurn3).toBeCloseTo(3, 6);
    expect(s.avgLandsTurn5).toBeUndefined();
    expect(s.lossReasons).toEqual({ LifeReachedZero: 1 });
    // Die Zugdeckel-Partie zaehlt in keiner dieser Kennzahlen mit - nur in ihrer eigenen: eine von drei
    // gespielten Partien (2 gewertete + 1 Deckel) lief in den Deckel.
    expect(s.turnCappedGames).toBe(1);
    expect(s.turnCappedRate).toBeCloseTo(1 / 3, 6);
    expect(s.opponents).toEqual([
      { deck: "Grix Control", games: 1, wins: 0 },
      { deck: "Meren Aristocrats", games: 1, wins: 1 },
    ]);
  });

  it("Deck nur mit Zugdeckel-Partien -> undefined (eine nicht gewertete Partie ist keine Bilanz)", () => {
    const onlyCapped: MatchRecord[] = [
      {
        id: "cap-1", startedAt: "s", endedAt: "e", durationMs: 2400000, source: "sparring", turns: 40,
        reason: "Draw", draw: true, counted: false, excludeReason: "Zugdeckel",
        seats: [
          {
            name: "Sparring-KI", deck: "Deckel-Deck", human: false, winner: false, lossReason: "IntentionalDraw",
            mulligans: 0, lands: 8, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8], missedLandDrops: 0,
            spells: 9, spellMana: 18, commanderCasts: 0, commanderTax: 0,
            damageDealt: 0, damageTaken: 0, combatDamageTaken: 0, lifeEnd: 40, poisonEnd: 0,
          },
          {
            name: "Sparring-Gegner", deck: "Gegner", human: false, winner: false, lossReason: "IntentionalDraw",
            mulligans: 0, lands: 8, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8], missedLandDrops: 0,
            spells: 9, spellMana: 18, commanderCasts: 0, commanderTax: 0,
            damageDealt: 0, damageTaken: 0, combatDamageTaken: 0, lifeEnd: 40, poisonEnd: 0,
          },
        ],
      },
    ];
    expect(summarize(onlyCapped, "Deckel-Deck")).toBeUndefined();
    // …steht aber sehr wohl in der Deckliste: ohne das waere genau der schlimmste Fall unsichtbar.
    expect(deckGames(onlyCapped)).toEqual([
      { deck: "Deckel-Deck", games: 0, capped: 1 },
      { deck: "Gegner", games: 0, capped: 1 },
    ]);
  });

  it("Spiegel am Zugdeckel zaehlt einmal, und ein anderer Ausschlussgrund zaehlt gar nicht", () => {
    const base = (id: string, excludeReason: string, deckB: string): MatchRecord => ({
      id, startedAt: "s", endedAt: "e", durationMs: 1000, source: "sparring", turns: 40,
      reason: "Draw", draw: true, counted: false, excludeReason,
      seats: [
        {
          name: "A", deck: "Deckel-Deck", human: false, winner: false, lossReason: "IntentionalDraw",
          mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4], missedLandDrops: 0, spells: 3, spellMana: 6,
          commanderCasts: 0, commanderTax: 0, damageDealt: 0, damageTaken: 0, combatDamageTaken: 0,
          lifeEnd: 40, poisonEnd: 0,
        },
        {
          name: "B", deck: deckB, human: false, winner: false, lossReason: "IntentionalDraw",
          mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4], missedLandDrops: 0, spells: 3, spellMana: 6,
          commanderCasts: 0, commanderTax: 0, damageDealt: 0, damageTaken: 0, combatDamageTaken: 0,
          lifeEnd: 40, poisonEnd: 0,
        },
      ],
    });
    const gewertet: MatchRecord = {
      id: "ok-1", startedAt: "s", endedAt: "e", durationMs: 1000, source: "sparring", turns: 6,
      reason: "AllOpponentsLost", draw: false, counted: true,
      seats: [
        {
          name: "A", deck: "Deckel-Deck", human: false, winner: true, mulligans: 0, lands: 4,
          landsByTurn: [0, 1, 2, 3, 4], missedLandDrops: 0, spells: 3, spellMana: 6, commanderCasts: 0,
          commanderTax: 0, damageDealt: 40, damageTaken: 0, combatDamageTaken: 0, lifeEnd: 40, poisonEnd: 0,
        },
        {
          name: "B", deck: "Gegner", human: false, winner: false, lossReason: "LifeReachedZero",
          mulligans: 0, lands: 4, landsByTurn: [0, 1, 2, 3, 4], missedLandDrops: 0, spells: 3, spellMana: 6,
          commanderCasts: 0, commanderTax: 0, damageDealt: 0, damageTaken: 40, combatDamageTaken: 40,
          lifeEnd: 0, poisonEnd: 0,
        },
      ],
    };
    // Spiegel am Deckel: dasselbe Deck auf beiden Sitzen ist eine Partie, kein Doppelzaehler.
    const alle = [gewertet, base("cap-mirror", "Zugdeckel", "Deckel-Deck"),
      base("cap-abort", "abgebrochen", "Gegner")];
    const s = summarize(alle, "Deckel-Deck");
    expect(s?.games).toBe(1);
    expect(s?.turnCappedGames).toBe(1);
    expect(s?.turnCappedRate).toBeCloseTo(0.5, 6);
    // dieselbe Regel in deckGames: der Spiegel am Deckel ist eine Partie, die abgebrochene keine.
    expect(deckGames(alle).find((d) => d.deck === "Deckel-Deck")).toEqual({ deck: "Deckel-Deck", games: 1, capped: 1 });
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

  it("Spiegelspiel (gleiches Deck auf beiden Sitzen) zaehlt als eine Partie fuer dieses Deck, bevorzugt den menschlichen Sitz", () => {
    const mirror: MatchRecord[] = [
      {
        id: "mirror-1", startedAt: "s", endedAt: "e", durationMs: 1000, source: "live", turns: 6,
        reason: "AllOpponentsLost", draw: false, counted: true,
        seats: [
          // KI-Sitz steht zuerst und verliert - waere die Wahl "immer der erste Sitz", zaehlte das als
          // Niederlage. Bevorzugt wird aber der menschliche Sitz (Sieg).
          {
            name: "KI 1", deck: "Spiegeldeck", human: false, winner: false, lossReason: "LifeReachedZero",
            mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6], missedLandDrops: 0,
            spells: 7, spellMana: 14, commanderCasts: 1, commanderTax: 0,
            damageDealt: 5, damageTaken: 20, combatDamageTaken: 20, lifeEnd: 0, poisonEnd: 0,
          },
          {
            name: "Du", deck: "Spiegeldeck", human: true, winner: true,
            mulligans: 0, lands: 6, landsByTurn: [0, 1, 2, 3, 4, 5, 6], missedLandDrops: 0,
            spells: 8, spellMana: 15, commanderCasts: 1, commanderTax: 0,
            damageDealt: 20, damageTaken: 5, combatDamageTaken: 5, lifeEnd: 35, poisonEnd: 0,
          },
        ],
      },
    ];
    expect(deckGames(mirror)).toEqual([{ deck: "Spiegeldeck", games: 1, capped: 0 }]);
    const s = summarize(mirror, "Spiegeldeck");
    expect(s?.games).toBe(1);
    expect(s?.wins).toBe(1);
    expect(s?.losses).toBe(0);
  });

  it("Sparring (source: sparring, kein menschlicher Sitz) zaehlt wie jede andere gewertete Partie", () => {
    const sparring: MatchRecord[] = [
      {
        id: "sparring-1", startedAt: "s", endedAt: "e", durationMs: 500000, source: "sparring", turns: 5,
        reason: "AllOpponentsLost", draw: false, counted: true,
        seats: [
          {
            name: "Sparring-KI", deck: "Krenko Goblins", human: false, ai: { mode: "sim", profile: "Default" },
            winner: true, mulligans: 0, lands: 5, landsByTurn: [0, 1, 2, 3, 4, 5], missedLandDrops: 0,
            spells: 6, spellMana: 11, commanderCasts: 1, commanderTax: 0, firstCommanderTurn: 2,
            damageDealt: 15, damageTaken: 3, combatDamageTaken: 3, lifeEnd: 37, poisonEnd: 0,
          },
          {
            name: "Gegner-KI", deck: "Grix Control", human: false, ai: { mode: "sim", profile: "Default" },
            winner: false, lossReason: "LifeReachedZero", mulligans: 0, lands: 5,
            landsByTurn: [0, 1, 2, 3, 4, 5], missedLandDrops: 0, spells: 5, spellMana: 12,
            commanderCasts: 0, commanderTax: 0, damageDealt: 3, damageTaken: 15, combatDamageTaken: 12,
            lifeEnd: 0, poisonEnd: 0,
          },
        ],
      },
    ];
    const s = summarize(sparring, "Krenko Goblins");
    expect(s?.games).toBe(1);
    expect(s?.wins).toBe(1);
  });

  it("Unentschieden zaehlt als draw, weder als sieg noch als niederlage", () => {
    const drawRecord: MatchRecord[] = [
      {
        id: "draw-1", startedAt: "s", endedAt: "e", durationMs: 800000, source: "live", turns: 20,
        reason: "IntentionalDraw", draw: true, counted: true,
        seats: [
          {
            name: "Du", deck: "Remis-Deck", human: true, winner: false, lossReason: "IntentionalDraw",
            mulligans: 0, lands: 10, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
            missedLandDrops: 0, spells: 12, spellMana: 22, commanderCasts: 1, commanderTax: 0,
            damageDealt: 8, damageTaken: 8, combatDamageTaken: 6, lifeEnd: 32, poisonEnd: 0,
          },
          {
            name: "KI 1", deck: "Gegner-Deck", human: false, winner: false, lossReason: "IntentionalDraw",
            mulligans: 0, lands: 9, landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
            missedLandDrops: 0, spells: 10, spellMana: 19, commanderCasts: 0, commanderTax: 0,
            damageDealt: 8, damageTaken: 8, combatDamageTaken: 6, lifeEnd: 32, poisonEnd: 0,
          },
        ],
      },
    ];
    const s = summarize(drawRecord, "Remis-Deck");
    expect(s).toBeDefined();
    if (!s) return;
    expect(s.draws).toBe(1);
    expect(s.wins + s.losses + s.draws).toBe(s.games);
    expect(s.winRate).toBeCloseTo(s.wins / s.games, 6);
  });

  // ---------------------------------------------------------------- Laender je eigenem Zug

  /** Sitz mit `landsByTurn`, sonst neutrale Zahlen - fuer die Zug-3/5-Faelle. */
  function landsRecord(id: string, turns: number, landsByTurn: number[]): MatchRecord {
    return {
      id, startedAt: "s", endedAt: "e", durationMs: 600000, source: "live", turns,
      reason: "AllOpponentsLost", draw: false, counted: true,
      seats: [
        {
          name: "Du", deck: "Testdeck", human: true, winner: true,
          mulligans: 0, lands: landsByTurn[landsByTurn.length - 1], landsByTurn, missedLandDrops: 0,
          spells: 5, spellMana: 10, commanderCasts: 0, commanderTax: 0,
          damageDealt: 40, damageTaken: 5, combatDamageTaken: 5, lifeEnd: 35, poisonEnd: 0,
        },
        {
          name: "KI 1", deck: "Gegner", human: false, winner: false, lossReason: "LifeReachedZero",
          eliminatedTurn: turns, mulligans: 0, lands: 3, landsByTurn: [0, 1, 2, 3], missedLandDrops: 0,
          spells: 4, spellMana: 9, commanderCasts: 0, commanderTax: 0,
          damageDealt: 5, damageTaken: 40, combatDamageTaken: 40, lifeEnd: 0, poisonEnd: 0,
        },
      ],
    };
  }

  it("Ø Länder bis eigenem Zug 3/5 mittelt nur ueber Partien mit so vielen eigenen Zuegen", () => {
    // 6 eigene Zuege (7 Eintraege) und 2 eigene Zuege (3 Eintraege, Sitz schied aus). Geklemmt statt
    // ausgelassen waere Zug 3 = (3 + 1) / 2 = 2 - eine Kurve, die so nie gespielt wurde.
    const s = summarize([landsRecord("lang", 12, [0, 1, 2, 3, 4, 5, 6]), landsRecord("kurz", 4, [0, 1, 1])], "Testdeck");
    expect(s?.games).toBe(2);
    expect(s?.avgLandsTurn3).toBeCloseTo(3, 6);
    expect(s?.avgLandsTurn5).toBeCloseTo(5, 6);
  });

  it("Ø Länder fehlt, wenn keine gewertete Partie so viele eigene Zuege hatte", () => {
    const s = summarize([landsRecord("kurz-1", 4, [0, 1, 1]), landsRecord("kurz-2", 5, [0, 1, 2])], "Testdeck");
    expect(s?.games).toBe(2);
    expect(s?.avgLandsTurn3).toBeUndefined();
    expect(s?.avgLandsTurn5).toBeUndefined();
  });

  it("Gegner-Tabelle zaehlt ein Deck je Partie einmal, auch wenn zwei Sitze es spielen", () => {
    const zweiGleiche: MatchRecord[] = [
      {
        id: "drei-1", startedAt: "s", endedAt: "e", durationMs: 900000, source: "live", turns: 9,
        reason: "AllOpponentsLost", draw: false, counted: true,
        seats: [
          {
            name: "Du", deck: "Eigenes Deck", human: true, winner: true, mulligans: 0, lands: 3,
            landsByTurn: [0, 1, 2, 3], missedLandDrops: 0, spells: 6, spellMana: 12, commanderCasts: 0,
            commanderTax: 0, damageDealt: 80, damageTaken: 6, combatDamageTaken: 6, lifeEnd: 34, poisonEnd: 0,
          },
          {
            name: "KI 1", deck: "Gegner-Deck", human: false, winner: false, lossReason: "LifeReachedZero",
            eliminatedTurn: 8, mulligans: 0, lands: 2, landsByTurn: [0, 1, 2], missedLandDrops: 0,
            spells: 4, spellMana: 8, commanderCasts: 0, commanderTax: 0, damageDealt: 3, damageTaken: 40,
            combatDamageTaken: 40, lifeEnd: 0, poisonEnd: 0,
          },
          {
            name: "KI 2", deck: "Gegner-Deck", human: false, winner: false, lossReason: "LifeReachedZero",
            eliminatedTurn: 9, mulligans: 0, lands: 3, landsByTurn: [0, 1, 2, 3], missedLandDrops: 0,
            spells: 5, spellMana: 11, commanderCasts: 0, commanderTax: 0, damageDealt: 3, damageTaken: 40,
            combatDamageTaken: 40, lifeEnd: 0, poisonEnd: 0,
          },
        ],
      },
    ];
    const s = summarize(zweiGleiche, "Eigenes Deck");
    expect(s?.opponents).toEqual([{ deck: "Gegner-Deck", games: 1, wins: 1 }]);
  });
});

// ---------------------------------------------------------------- Format: Duell und Pod getrennt

describe("formatOf", () => {
  it("zwei Sitze sind ein Duell, drei und mehr ein Pod", () => {
    expect(formatOf(records[0])).toBe("duel");
    expect(formatOf(records[1])).toBe("pod");
  });

  it("ein einzelner Sitz ist kein Duell (Duell heisst genau zwei Sitze)", () => {
    const solo: MatchRecord = { ...records[0], seats: [records[0].seats[0]] };
    expect(formatOf(solo)).toBe("pod");
  });
});

describe("Formatfilter", () => {
  it("deckGames zaehlt im Pod nur die Pod-Partien", () => {
    // Nur m2 (v1, drei Sitze) und die neue v2-Pod-Partie.
    expect(deckGames(records, "pod")).toEqual([
      { deck: "Atraxa Superfriends", games: 2, capped: 0 },
      { deck: "Muldrotha Reanimator", games: 2, capped: 0 },
      { deck: "Golgari Midrange", games: 1, capped: 0 },
      { deck: "Titania, Gaea Incarnate", games: 1, capped: 0 },
    ]);
  });

  it("deckGames zaehlt im Duell nur die Duell-Partien, Zugdeckel wie gehabt getrennt", () => {
    expect(deckGames(records, "duel")).toEqual([
      { deck: "Grix Control", games: 2, capped: 1 },
      { deck: "Krenko Goblins", games: 2, capped: 1 },
      { deck: "Ojutai Flyers", games: 2, capped: 1 },
      { deck: "Lantern Stax", games: 0, capped: 2 },
      { deck: "Meren Aristocrats", games: 2, capped: 0 },
      { deck: "Atraxa Superfriends", games: 0, capped: 1 },
      { deck: "Golgari Midrange", games: 1, capped: 0 },
      { deck: "Titania, Gaea Incarnate", games: 1, capped: 0 },
    ]);
  });

  it("summarize trennt dieselbe Deck-Bilanz nach Format", () => {
    // Titania hat genau eine Duell- (Sieg) und eine Pod-Partie (Niederlage) - ohne Filter 1:1.
    const alle = summarize(records, "Titania, Gaea Incarnate");
    expect(alle?.games).toBe(2);
    const duell = summarize(records, "Titania, Gaea Incarnate", "duel");
    expect(duell?.games).toBe(1);
    expect(duell?.wins).toBe(1);
    expect(duell?.opponents).toEqual([{ deck: "Meren Aristocrats", games: 1, wins: 1 }]);
    const pod = summarize(records, "Titania, Gaea Incarnate", "pod");
    expect(pod?.games).toBe(1);
    expect(pod?.wins).toBe(0);
    expect(pod?.opponents).toEqual([
      { deck: "Atraxa Superfriends", games: 1, wins: 0 },
      { deck: "Muldrotha Reanimator", games: 1, wins: 0 },
    ]);
  });

  it("summarize: Format ohne gewertete Partie -> undefined, auch wenn es dort Zugdeckel-Partien gibt", () => {
    // Atraxa hat im Duell nur die Zugdeckel-Partie m8 - das ist keine Bilanz.
    expect(summarize(records, "Atraxa Superfriends", "duel")).toBeUndefined();
    expect(summarize(records, "Atraxa Superfriends", "pod")?.games).toBe(2);
  });

  it("der Zugdeckel-Anteil rechnet nur mit Partien des gewaehlten Formats", () => {
    // Krenko: zwei gewertete Duelle und ein Duell am Deckel - im Duell wie ohne Filter 1/3.
    expect(summarize(records, "Krenko Goblins", "duel")?.turnCappedRate).toBeCloseTo(1 / 3, 6);
    // Ojutai: im Duell zwei gewertete (m6, v2-Partie) und die Deckel-Partie m9.
    expect(summarize(records, "Ojutai Flyers", "duel")?.turnCappedGames).toBe(1);
  });
});

// ---------------------------------------------------------------- Kennzahlen aus Runde B (v >= 2)

/** Ein v2-Datensatz mit "Testdeck" auf Sitz 1 - alle Vorfall-Felder auf 0, ausser den ueberschriebenen.
 * pod: ein dritter Sitz, sonst ein Duell. */
function v2Record(id: string, seat: Partial<MatchSeat>, opts: { turns?: number; pod?: boolean; v?: number } = {}): MatchRecord {
  const filler: MatchSeat = {
    name: "Gegner", deck: "Gegner-Deck", human: false, winner: true, mulligans: 0, lands: 5,
    landsByTurn: [0, 1, 2, 3, 4, 5], missedLandDrops: 0, spells: 8, spellMana: 16, commanderCasts: 1,
    commanderTax: 0, damageDealt: 40, damageTaken: 10, combatDamageTaken: 10, lifeEnd: 30, poisonEnd: 0,
    spellsCountered: 0, spellsFizzled: 0, counterspellsCast: 0, removalCast: 0, cardsDrawn: 10,
    cardsDiscarded: 0, cardsMilled: 0, openingLands: 3, handEnd: 3, permanentsLost: 0,
    creaturesLostInCombat: 0, creaturesLostOther: 0, biggestSweep: 0, sweepsSuffered: 0, tokensCreated: 0,
    attacksDeclared: 6, attackedTurns: 3, attackersFaced: 2, blocksDeclared: 1, damageTakenFlying: 4,
    damageTakenTrample: 0, damageTakenOther: 6, damageTakenNonCombat: 0, damageDealtCombat: 36,
    damageDealtNonCombat: 4, commanderDamageTaken: 0, lifeGained: 0,
  };
  const own: MatchSeat = {
    ...filler, name: "Du", deck: "Testdeck", human: true, winner: false, lossReason: "LifeReachedZero",
    eliminatedTurn: opts.turns ?? 10, damageDealt: 10, damageTaken: 40, combatDamageTaken: 36, lifeEnd: 0,
    damageTakenFlying: 12, damageTakenTrample: 12, damageTakenOther: 12, damageTakenNonCombat: 4,
    damageDealtCombat: 8, damageDealtNonCombat: 2, attackersFaced: 6, ...seat,
  };
  return {
    v: opts.v ?? 2, id, startedAt: "s", endedAt: "e", durationMs: 600000, source: "live",
    turns: opts.turns ?? 10, reason: "AllOpponentsLost", draw: false, counted: true,
    seats: opts.pod ? [own, filler, { ...filler, name: "Gegner 2", deck: "Zweiter Gegner" }] : [own, filler],
  };
}

describe("v2-Kennzahlen", () => {
  it("ohne eine einzige v2-Partie fehlt jede Vorfall-Kennzahl (v1 zaehlte sie nie - 0 waere erfunden)", () => {
    // Titania hat nur v1-Partien; die Bridge schickt die Felder dort als 0 mit.
    const s = summarize(records, "Titania, Gaea Incarnate");
    expect(s).toBeDefined();
    if (!s) return;
    expect(s.games).toBe(2);
    expect(s.v2Games).toBe(0);
    expect(s.manaScrewRate).toBeUndefined();
    expect(s.avgOpeningLands).toBeUndefined();
    expect(s.spellsPerTurn).toBeUndefined();
    expect(s.counteredRate).toBeUndefined();
    expect(s.sweepGames).toBeUndefined();
    expect(s.avgBiggestSweep).toBeUndefined();
    expect(s.flyingShare).toBeUndefined();
    expect(s.tramplingShare).toBeUndefined();
    expect(s.avgAttacks).toBeUndefined();
    expect(s.avgAttackersFaced).toBeUndefined();
    expect(s.avgHandEnd).toBeUndefined();
    expect(s.avgRemovalCast).toBeUndefined();
    expect(s.avgCounterspellsCast).toBeUndefined();
    expect(s.avgEliminationShare).toBeUndefined();
    // Die Flut-Quote steht trotzdem: lands/spells zaehlt die Bridge seit v1 (Titania war nie geflutet).
    expect(s.floodRate).toBe(0);
  });

  it("eine v1-Partie mit ausgefuellten Feldern bleibt trotzdem 'keine Daten'", () => {
    // Genau der Fall, gegen den die Versionspruefung schuetzt: der Datensatz traegt die Felder, aber v: 1.
    const s = summarize([v2Record("alt", { sweepsSuffered: 3, openingLands: 1 }, { v: 1 })], "Testdeck");
    expect(s?.games).toBe(1);
    expect(s?.v2Games).toBe(0);
    expect(s?.sweepGames).toBeUndefined();
    expect(s?.avgOpeningLands).toBeUndefined();
  });

  it("Mischung: die Vorfall-Kennzahlen rechnen nur ueber die v2-Partien", () => {
    // Ojutai Flyers: m6 (v1, Unentschieden) und die v2-Duell-Partie. Bilanz ueber beide, Vorfaelle ueber eine.
    const s = summarize(records, "Ojutai Flyers");
    expect(s).toBeDefined();
    if (!s) return;
    expect(s.games).toBe(2);
    expect(s.draws).toBe(1);
    expect(s.v2Games).toBe(1);
    expect(s.avgOpeningLands).toBeCloseTo(3, 6);
    expect(s.avgHandEnd).toBeCloseTo(4, 6);
    expect(s.avgRemovalCast).toBeCloseTo(3, 6);
    expect(s.avgCounterspellsCast).toBeCloseTo(1, 6);
    expect(s.avgAttacks).toBeCloseTo(5, 6);
    expect(s.avgAttackersFaced).toBeCloseTo(12, 6);
    expect(s.sweepGames).toBe(1);
    expect(s.avgBiggestSweep).toBeCloseTo(3, 6);
    // 2 von 11 eigenen Zaubern gekontert, 11 Zauber in 7 eigenen Zuegen.
    expect(s.counteredRate).toBeCloseTo(2 / 11, 6);
    expect(s.spellsPerTurn).toBeCloseTo(11 / 7, 6);
    // 18 von 36 Punkten Kampfschaden kamen von Fliegern, 6 von Trampelschaden.
    expect(s.flyingShare).toBeCloseTo(0.5, 6);
    expect(s.tramplingShare).toBeCloseTo(6 / 36, 6);
    // Der Sitz hielt bis zum letzten Zug durch: 14/14.
    expect(s.avgEliminationShare).toBeCloseTo(1, 6);
    // Kein Screw (3 Laender im 3. eigenen Zug), keine Flut (7 Laender, aber 11 Zauber statt hoechstens 2).
    expect(s.manaScrewRate).toBe(0);
    expect(s.floodRate).toBe(0);
  });

  it("Pod: der Ausscheide-Anteil rechnet nur die v2-Partie, nicht die v1-Partie mit", () => {
    // Atraxa: m2 (v1, schied im letzten Zug aus -> 1,0) und die v2-Pod-Partie (Zug 12 von 18).
    const s = summarize(records, "Atraxa Superfriends", "pod");
    expect(s?.games).toBe(2);
    expect(s?.v2Games).toBe(1);
    expect(s?.avgEliminationShare).toBeCloseTo(12 / 18, 6);
    expect(s?.tramplingShare).toBeCloseTo(14 / 32, 6);
  });

  it("Mana-Screw: <= 2 Laender im 3. eigenen Zug, ueber die Partien mit so vielen eigenen Zuegen", () => {
    const s = summarize([
      v2Record("screw", { landsByTurn: [0, 1, 2, 2, 3], lands: 3 }),
      v2Record("ok", { landsByTurn: [0, 1, 2, 3, 4], lands: 4 }),
    ], "Testdeck");
    expect(s?.v2Games).toBe(2);
    expect(s?.manaScrewRate).toBeCloseTo(0.5, 6);
  });

  it("Mana-Screw fehlt, wenn keine v2-Partie ueberhaupt drei eigene Zuege hatte", () => {
    const s = summarize([v2Record("kurz", { landsByTurn: [0, 1, 2], lands: 2 })], "Testdeck");
    expect(s?.v2Games).toBe(1);
    expect(s?.manaScrewRate).toBeUndefined();
    // Die uebrigen Kennzahlen stehen trotzdem - sie brauchen keine drei eigenen Zuege.
    expect(s?.avgOpeningLands).toBeCloseTo(3, 6);
  });

  it("Flut: mindestens 6 Laender UND hoechstens 2 Zauber (Definition wie im Bench-ActivityCounter)", () => {
    const s = summarize([
      v2Record("flut", { landsByTurn: [0, 1, 2, 3, 4, 5, 6], lands: 6, spells: 2 }),
      // 3 Zauber: einer zu viel fuer eine Flut.
      v2Record("gezaubert", { landsByTurn: [0, 1, 2, 3, 4, 5, 6], lands: 6, spells: 3 }),
      // 5 Laender: zu wenig fuer eine Flut, egal wie wenig gezaubert wurde.
      v2Record("wenig-land", { landsByTurn: [0, 1, 2, 3, 4, 5], lands: 5, spells: 1 }),
    ], "Testdeck");
    expect(s?.v2Games).toBe(3);
    expect(s?.floodRate).toBeCloseTo(1 / 3, 6);
  });

  it("die Flut-Quote rechnet auch v1-Partien mit - lands und spells gab es dort schon", () => {
    const s = summarize([
      v2Record("alt-flut", { landsByTurn: [0, 1, 2, 3, 4, 5, 6, 7], lands: 7, spells: 1 }, { v: 1 }),
      v2Record("alt-normal", { landsByTurn: [0, 1, 2, 3, 4, 5, 6], lands: 6, spells: 9 }, { v: 1 }),
    ], "Testdeck");
    expect(s?.games).toBe(2);
    // Keine einzige Partie mit Vorfall-Daten - die Flut-Quote steht trotzdem.
    expect(s?.v2Games).toBe(0);
    expect(s?.floodRate).toBeCloseTo(0.5, 6);
  });

  it("Quoten ohne Grundlage fehlen, statt 0 zu behaupten", () => {
    // Kein Kampfschaden erlitten -> Flieger-/Trampel-Anteil haben keinen Nenner; kein Zauber gewirkt ->
    // keine Konter-Quote; ueberlebt -> kein Ausscheide-Anteil.
    const s = summarize([v2Record("nichts", {
      winner: true, lossReason: undefined, eliminatedTurn: null, spells: 0,
      damageTakenFlying: 0, damageTakenTrample: 0, damageTakenOther: 0, damageTakenNonCombat: 3,
      damageTaken: 3, combatDamageTaken: 0, lifeEnd: 37,
    })], "Testdeck");
    expect(s?.v2Games).toBe(1);
    expect(s?.flyingShare).toBeUndefined();
    expect(s?.tramplingShare).toBeUndefined();
    expect(s?.counteredRate).toBeUndefined();
    expect(s?.avgEliminationShare).toBeUndefined();
    // Mittelwerte ueber gezaehlte Felder bleiben: eine gemessene 0 ist eine Aussage.
    expect(s?.sweepGames).toBe(0);
    expect(s?.avgBiggestSweep).toBe(0);
  });

  it("gepoolte Quoten: gekonterte Zauber ueber alle v2-Partien zusammen, nicht als Mittel der Quoten", () => {
    const s = summarize([
      v2Record("viel", { spells: 20, spellsCountered: 2 }),
      v2Record("wenig", { spells: 2, spellsCountered: 1 }),
    ], "Testdeck");
    // 3 von 22 Zaubern (13,6 %) - das Mittel der Einzelquoten waere 30 % und liesse die 2-Zauber-Partie
    // so schwer wiegen wie die 20-Zauber-Partie.
    expect(s?.counteredRate).toBeCloseTo(3 / 22, 6);
  });
});
