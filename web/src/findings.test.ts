import { describe, expect, it } from "vitest";
import {
  COUNTERED_MAX, ELIMINATION_SHARE_MIN, FLOOD_MAX, FLYER_DEFENSE_MAX, FLYING_SHARE_MAX, MANA_SCREW_MAX,
  MIN_GAMES, MULLIGAN_MAX, SWEEP_GAMES_MAX, TURN_CAPPED_MAX, findings,
} from "./findings";
import type { DeckSummary } from "./matchStats";
import type { DeckAnalysis } from "./protocol";

// Jede Regel wird zweimal geprueft: einmal knapp ueber der Schwelle (sie meldet sich) und einmal knapp
// darunter (sie schweigt). Die Zahlen stehen absichtlich dicht an der Konstante - ruecken wir die
// Schwelle, muss der Test mitwandern und nicht stillschweigend weiter gruen bleiben.

/** Unauffaellige Zusammenfassung mit genug Partien; die Tests ueberschreiben je eine Kennzahl. */
function summary(over: Partial<DeckSummary> = {}): DeckSummary {
  return {
    games: 10, wins: 5, losses: 5, draws: 0, winRate: 0.5, ci: [0.24, 0.76], avgTurns: 12,
    avgDurationMs: 1_800_000, mulliganRate: 0.2, avgMulligans: 0.2, avgLandsTurn3: 3, avgLandsTurn5: 5,
    missedLandDropRate: 0.1, avgMissedLandDrops: 0.1, avgSpells: 12, avgSpellMana: 24, avgCommanderTurn: 4,
    avgCommanderTax: 1, avgDamageDealt: 30, avgDamageTaken: 30, lossReasons: { LifeReachedZero: 5 },
    opponents: [], turnCappedGames: 0, turnCappedRate: 0,
    v2Games: 10, manaScrewRate: 0.1, floodRate: 0.1, avgOpeningLands: 3, spellsPerTurn: 1.2,
    counteredRate: 0.05, sweepGames: 1, avgBiggestSweep: 1, flyingShare: 0.2, tramplingShare: 0.1,
    avgAttacks: 6, avgAttackersFaced: 5, avgHandEnd: 3, avgRemovalCast: 3, avgCounterspellsCast: 1,
    avgEliminationShare: 0.9,
    ...over,
  };
}

/** Deckanalyse mit unauffaelligen Kategorien; die Tests ueberschreiben je eine. */
function analysis(categories: Partial<DeckAnalysis["categories"]> = {}): DeckAnalysis {
  return {
    cards: 100, lands: 36, basics: 20, avgCmc: 3.4,
    curve: { "0": 2, "1": 7, "2": 13, "3": 15, "4": 11, "5": 7, "6": 5, "7+": 4 },
    sources: { W: 0, U: 12, B: 15, R: 0, G: 0, any: 6 },
    identity: ["U", "B"],
    categories: {
      ramp: 8, draw: 9, removal: 6, wipes: 1, counters: 3, flyerDefense: 5, wipeProtection: 3,
      recursion: 4, tutors: 2, ...categories,
    },
    unclassified: 31,
  };
}

/** Titel der gemeldeten Befunde - die Tests pruefen, WELCHE Regel angeschlagen hat, nicht den Wortlaut. */
function titles(...args: Parameters<typeof findings>): string[] {
  return findings(...args).map((f) => f.title);
}

describe("Mindeststichprobe", () => {
  it(`unter ${MIN_GAMES} gewerteten Partien genau ein info-Befund, egal wie auffaellig die Zahlen sind`, () => {
    const fs = findings(summary({ games: 4, v2Games: 4, manaScrewRate: 0.9, mulliganRate: 0.9 }), analysis(), "all");
    expect(fs).toHaveLength(1);
    expect(fs[0].level).toBe("info");
    // Der Satz nennt die eigene Stichprobe und die geforderte.
    expect(fs[0].text).toContain("4");
    expect(fs[0].text).toContain(String(MIN_GAMES));
  });

  it("ohne Zusammenfassung (kein Deck gewaehlt, keine Partie) derselbe eine Befund", () => {
    const fs = findings(undefined, analysis(), "duel");
    expect(fs).toHaveLength(1);
    expect(fs[0].level).toBe("info");
  });

  it(`genau ${MIN_GAMES} Partien reichen`, () => {
    const fs = findings(summary({ games: MIN_GAMES, v2Games: MIN_GAMES, mulliganRate: 0.9 }), analysis(), "all");
    expect(fs.map((f) => f.title)).toContain("Viele Mulligans");
  });

  it("v2-Regeln schweigen, solange zu wenige v2-Partien dahinterstehen", () => {
    // 10 gewertete Partien, aber nur 2 davon mit Vorfall-Daten: 100 % Screw aus 2 Partien ist Rauschen.
    const s = summary({ games: 10, v2Games: 2, manaScrewRate: 1, mulliganRate: 0.9 });
    const t = titles(s, analysis(), "all");
    expect(t).not.toContain("Mana-Screw");
    // Die Regeln ohne v2-Grundlage melden sich trotzdem.
    expect(t).toContain("Viele Mulligans");
  });

  it("ohne v2-Partien melden sich die Vorfall-Regeln gar nicht", () => {
    const s = summary({
      v2Games: 0, manaScrewRate: undefined, counteredRate: undefined,
      sweepGames: undefined, flyingShare: undefined, avgEliminationShare: undefined,
      // floodRate steht auch ohne v2-Partien (siehe unten) - hier unauffaellig.
      floodRate: 0.1,
    });
    expect(titles(s, analysis({ wipeProtection: 0, counters: 0, flyerDefense: 0 }), "all"))
      .toEqual(["Nichts Auffälliges"]);
  });
});

describe("Regel: Mana-Screw", () => {
  it("ueber der Schwelle -> warn, mit Laenderzahl und Ø CMC aus der Deckanalyse", () => {
    const fs = findings(summary({ manaScrewRate: MANA_SCREW_MAX + 0.01 }), analysis(), "all");
    const f = fs.find((x) => x.title === "Mana-Screw");
    expect(f?.level).toBe("warn");
    expect(f?.needs).toContain("36");
    expect(f?.needs).toContain("3,4");
  });

  it("genau auf der Schwelle -> kein Befund (die Regel greift erst darueber)", () => {
    expect(titles(summary({ manaScrewRate: MANA_SCREW_MAX }), analysis(), "all")).not.toContain("Mana-Screw");
  });

  it("ohne Deckanalyse meldet die Regel trotzdem, nur ohne Deckbezug", () => {
    const f = findings(summary({ manaScrewRate: 0.5 }), undefined, "all").find((x) => x.title === "Mana-Screw");
    expect(f?.level).toBe("warn");
    expect(f?.needs).toBeUndefined();
  });
});

describe("Regel: Landflut", () => {
  it("ueber der Schwelle -> warn, mit Laenderzahl und Ø CMC aus der Deckanalyse", () => {
    const f = findings(summary({ floodRate: FLOOD_MAX + 0.01 }), analysis(), "all").find((x) => x.title === "Landflut");
    expect(f?.level).toBe("warn");
    expect(f?.needs).toContain("36");
  });

  it("unter der Schwelle -> kein Befund", () => {
    expect(titles(summary({ floodRate: FLOOD_MAX }), analysis(), "all")).not.toContain("Landflut");
  });

  it("meldet auch ohne eine einzige v2-Partie (lands/spells gibt es seit v1)", () => {
    // Die Flut-Quote haengt als einzige Zahl dieses Blocks NICHT an der v2-Stichprobe.
    const s = summary({ floodRate: 0.6, v2Games: 0, manaScrewRate: undefined, avgOpeningLands: undefined });
    expect(titles(s, analysis(), "all")).toContain("Landflut");
  });
});

describe("Regel: Mulligans", () => {
  it("ueber der Schwelle -> warn, mit Ø Laendern der Starthand", () => {
    const f = findings(summary({ mulliganRate: MULLIGAN_MAX + 0.01, avgOpeningLands: 1.8 }), analysis(), "all")
      .find((x) => x.title === "Viele Mulligans");
    expect(f?.level).toBe("warn");
    expect(f?.text).toContain("1,8");
  });

  it("unter der Schwelle -> kein Befund", () => {
    expect(titles(summary({ mulliganRate: MULLIGAN_MAX }), analysis(), "all")).not.toContain("Viele Mulligans");
  });

  it("zaehlt auch ohne v2-Partien (die Mulligan-Quote stand schon in v1)", () => {
    const s = summary({ mulliganRate: 0.8, v2Games: 0, avgOpeningLands: undefined });
    expect(titles(s, analysis(), "all")).toContain("Viele Mulligans");
  });
});

describe("Regel: Massenentfernung", () => {
  it("ueber der Schwelle UND kein Schutz im Deck -> warn", () => {
    const s = summary({ v2Games: 10, sweepGames: 4 }); // 40 %
    const f = findings(s, analysis({ wipeProtection: 0 }), "all").find((x) => x.title === "Massenentfernung");
    expect(f?.level).toBe("warn");
    expect(f?.needs).toContain("0");
  });

  it("unter der Schwelle -> kein Befund, auch ohne Schutz", () => {
    const s = summary({ v2Games: 10, sweepGames: 3 }); // genau 30 %
    expect(titles(s, analysis({ wipeProtection: 0 }), "all")).not.toContain("Massenentfernung");
  });

  it("mit Schutz im Deck schweigt die Regel, auch bei hoher Quote", () => {
    const s = summary({ v2Games: 10, sweepGames: 6 });
    expect(titles(s, analysis({ wipeProtection: 2 }), "all")).not.toContain("Massenentfernung");
  });

  it("ohne Deckanalyse schweigt die Regel (die Aussage haengt am Deckinhalt)", () => {
    const s = summary({ v2Games: 10, sweepGames: 6 });
    expect(titles(s, undefined, "all")).not.toContain("Massenentfernung");
  });
});

describe("Regel: Flieger", () => {
  it("ueber der Schwelle UND zu wenig Fliegerabwehr -> warn", () => {
    const f = findings(summary({ flyingShare: FLYING_SHARE_MAX + 0.01 }), analysis({ flyerDefense: FLYER_DEFENSE_MAX }), "all")
      .find((x) => x.title === "Flieger");
    expect(f?.level).toBe("warn");
  });

  it("unter der Schwelle -> kein Befund", () => {
    expect(titles(summary({ flyingShare: FLYING_SHARE_MAX }), analysis({ flyerDefense: 0 }), "all"))
      .not.toContain("Flieger");
  });

  it("genug Fliegerabwehr -> kein Befund", () => {
    expect(titles(summary({ flyingShare: 0.8 }), analysis({ flyerDefense: FLYER_DEFENSE_MAX + 1 }), "all"))
      .not.toContain("Flieger");
  });
});

describe("Regel: gekonterte Zauber", () => {
  it("ueber der Schwelle UND kein eigener Konter -> info (kein warn: Gegnersache)", () => {
    const f = findings(summary({ counteredRate: COUNTERED_MAX + 0.01 }), analysis({ counters: 0 }), "all")
      .find((x) => x.title === "Gekonterte Zauber");
    expect(f?.level).toBe("info");
  });

  it("unter der Schwelle -> kein Befund", () => {
    expect(titles(summary({ counteredRate: COUNTERED_MAX }), analysis({ counters: 0 }), "all"))
      .not.toContain("Gekonterte Zauber");
  });

  it("eigene Konter im Deck -> kein Befund", () => {
    expect(titles(summary({ counteredRate: 0.5 }), analysis({ counters: 1 }), "all"))
      .not.toContain("Gekonterte Zauber");
  });
});

describe("Regel: Zugdeckel", () => {
  it("ueber der Schwelle -> warn (kein verlaesslicher Abschluss)", () => {
    const f = findings(summary({ turnCappedRate: TURN_CAPPED_MAX + 0.01, turnCappedGames: 3 }), analysis(), "all")
      .find((x) => x.title === "Zugdeckel");
    expect(f?.level).toBe("warn");
  });

  it("unter der Schwelle -> kein Befund", () => {
    expect(titles(summary({ turnCappedRate: TURN_CAPPED_MAX, turnCappedGames: 2 }), analysis(), "all"))
      .not.toContain("Zugdeckel");
  });
});

describe("Regel: frueh raus (nur im Pod)", () => {
  it("unter der Schwelle -> info", () => {
    const f = findings(summary({ avgEliminationShare: ELIMINATION_SHARE_MIN - 0.01 }), analysis(), "pod")
      .find((x) => x.title === "Früh raus");
    expect(f?.level).toBe("info");
  });

  it("auf der Schwelle -> kein Befund", () => {
    expect(titles(summary({ avgEliminationShare: ELIMINATION_SHARE_MIN }), analysis(), "pod"))
      .not.toContain("Früh raus");
  });

  it("im Duell und in 'Alle' schweigt die Regel (dort mischen sich die Formate)", () => {
    const s = summary({ avgEliminationShare: 0.3 });
    expect(titles(s, analysis(), "duel")).not.toContain("Früh raus");
    expect(titles(s, analysis(), "all")).not.toContain("Früh raus");
  });
});

describe("nichts Auffaelliges", () => {
  it("unauffaellige Zahlen -> genau ein info-Befund mit der Partienzahl", () => {
    const fs = findings(summary(), analysis(), "all");
    expect(fs).toHaveLength(1);
    expect(fs[0].level).toBe("info");
    expect(fs[0].title).toBe("Nichts Auffälliges");
    expect(fs[0].text).toContain("10");
  });

  it("schlaegt eine Regel an, faellt der 'nichts Auffaelliges'-Befund weg", () => {
    const t = titles(summary({ mulliganRate: 0.9 }), analysis(), "all");
    expect(t).not.toContain("Nichts Auffälliges");
  });
});

describe("Reihenfolge und Text", () => {
  it("warn steht vor info", () => {
    const s = summary({ mulliganRate: 0.9, counteredRate: 0.5 });
    const fs = findings(s, analysis({ counters: 0 }), "all");
    expect(fs.map((f) => f.level)).toEqual(["warn", "info"]);
  });

  it("jeder Befund nennt Zahl und Stichprobe", () => {
    const s = summary({ manaScrewRate: 0.5, v2Games: 8, games: 12 });
    const f = findings(s, analysis(), "all").find((x) => x.title === "Mana-Screw");
    expect(f?.text).toContain("50 %");
    expect(f?.text).toContain("8"); // Stichprobe: die v2-Partien, nicht alle 12
  });
});
