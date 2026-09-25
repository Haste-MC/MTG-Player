import { FLOOD_LANDS, FLOOD_MAX_SPELLS, type DeckSummary, type Format } from "./matchStats";
import type { DeckAnalysis } from "./protocol";

// Auffaelligkeiten: aus den Kennzahlen eines Decks (in einem Format) und - wo vorhanden - dem Deckinhalt
// werden Saetze, die man lesen kann, ohne die Kacheln zu deuten. Reine Funktion, keine Anzeige.
//
// Drei Grundsaetze, die jede Regel einhaelt:
//  1. Jeder Satz nennt ZAHL UND STICHPROBE. "Mana-Screw" allein ist keine Aussage, "in 43 % von 7
//     Partien" ist eine. Gemeint ist die Stichprobe, ueber die WIRKLICH gerechnet wurde: drei Kennzahlen
//     haben eine kleinere als v2Games (manaScrewGames, sweepGames, eliminationGames - siehe DeckSummary).
//  2. Eine Regel, die an einer Kennzahl aus Runde B haengt, braucht dafuer genug v2-Partien
//     (MIN_GAMES) - nicht bloss genug Partien insgesamt. Sonst meldet eine einzelne neue Partie mit
//     einem Massenentfernungs-Zauber "100 %".
//  3. Schwellen stehen als Konstanten hier oben und werden STRIKT ueberschritten ("> Schwelle").
//     Genau auf der Schwelle schweigt die Regel.

/** Mindeststichprobe: so viele gewertete Partien im gewaehlten Format braucht es ueberhaupt fuer eine
 * Aussage - und ebenso viele Partien mit Vorfall-Daten (v >= 2) fuer die Regeln, die daran haengen. */
export const MIN_GAMES = 5;

/** Mana-Screw-Quote (hoechstens 2 Laender im 3. eigenen Zug), ab der es der Rede wert ist. */
export const MANA_SCREW_MAX = 0.3;
/** Flut-Quote (mindestens FLOOD_LANDS Laender, hoechstens FLOOD_MAX_SPELLS Zauber - die Definition
 * steht in matchStats.ts), ab der es der Rede wert ist. */
export const FLOOD_MAX = 0.25;
/** Mulligan-Quote, ab der die Starthaende das Problem sind und nicht der Zufall. */
export const MULLIGAN_MAX = 0.4;
/** Anteil der Partien mit erlittener Massenentfernung, ab dem sie kein Einzelfall mehr ist. */
export const SWEEP_GAMES_MAX = 0.3;
/** Anteil der Flieger am erlittenen Kampfschaden, ab dem der Himmel das Problem ist ... */
export const FLYING_SHARE_MAX = 0.4;
/** ... aber nur, wenn das Deck hoechstens so viele Karten hat, die Flieger aufhalten koennen. */
export const FLYER_DEFENSE_MAX = 2;
/** Anteil der eigenen Zauber, die gekontert wurden, ab dem es auffaellt. */
export const COUNTERED_MAX = 0.15;
/** Anteil der Partien, die am Zugdeckel endeten, ab dem dem Deck der Abschluss fehlt. */
export const TURN_CAPPED_MAX = 0.2;
/** Ø eliminatedTurn / turns: darunter ist der Sitz deutlich vor Partieende ausgeschieden. */
export const ELIMINATION_SHARE_MIN = 0.7;
/** Eigene Mindeststichprobe von "Frueh raus": so viele Partien muss der Sitz ueberhaupt ausgeschieden
 * sein. Der Mittelwert rechnet NUR ueber diese (siehe DeckSummary.eliminationGames) - genug v2-Partien
 * heisst also noch lange nicht genug Grundlage: in 20 Partien kann man einmal ausgeschieden sein. */
export const MIN_ELIMINATIONS = 5;

/** Titel der beiden Sonderfaelle (siehe findings()): Zeilen, die KEIN Befund sind. Die Anzeige haengt
 * an ihnen weder den Verweis auf die Kartenvorschlaege noch den Hinweis auf die fehlende Deckanalyse. */
export const TOO_FEW_TITLE = "Zu wenige Partien";
export const NOTHING_TITLE = "Nichts Auffälliges";

/** Hinweis, den die Anzeige an jede Zeile haengt - die Kartenvorschlaege selbst sind Stueck 2 und
 * gehoeren nicht in die Regeln. */
export const SUGGESTIONS_HINT = "Kartenvorschläge folgen.";

/** Die neun Kategorien aus DeckAnalysis.categories - dieselben Namen wie auf der Bridge-Seite
 * (mtgplayer.decks.DeckAnalysis), damit ein Befund und ein Deckcheck ohne Uebersetzung zusammenpassen. */
export type Role = "ramp" | "draw" | "removal" | "wipes" | "counters"
  | "flyerDefense" | "wipeProtection" | "recursion" | "tutors";

/** Richtwerte fuer den Deckcheck ohne Partien (Spec §1). counters/recursion/tutors haben bewusst keinen:
 *  sie sind Deckabsicht, kein Mangel - ein blaues Deck ohne Gegenzauber ist eine Entscheidung. */
export const ROLE_TARGETS: Partial<Record<Role, number>> = {
  ramp: 10, draw: 8, removal: 8, wipes: 2, flyerDefense: 4, wipeProtection: 2,
};

/** Hoechstens so viele Luecken liefert roleGaps() - mehr Kartenvorschlaege auf einmal waeren keine
 *  Prioritaet mehr, sondern eine zweite Deckliste. */
export const MAX_GAPS = 3;

/** Ein Befund: Stufe, kurzer Titel, ein Satz mit Zahl und Stichprobe und - wenn die Deckanalyse vorliegt
 * und etwas dazu sagt - der Bezug zum Deckinhalt in `needs`. `role` zeigt, wo im Deck angesetzt werden
 * koennte - nur bei Regeln, die tatsaechlich eine Kategorie meinen (nicht bei Mulligans/Zugdeckel). */
export interface Finding { level: "info" | "warn"; title: string; text: string; needs?: string; role?: Role }

/** Was eine Regel zu sehen bekommt. `incident` gibt eine Vorfall-Kennzahl nur heraus, wenn genug
 * v2-Partien dahinterstehen - der einzige Ort, an dem diese Bedingung steht. */
interface Ctx {
  s: DeckSummary;
  deck?: DeckAnalysis;
  format: Format;
  incident: (value: number | undefined) => number | undefined;
}

type Rule = (c: Ctx) => Finding | undefined;

/** Mana-Screw: zu oft ohne Land. Bezug: Laenderzahl und Ø Manabetrag des Decks. */
const manaScrew: Rule = ({ s, deck, incident }) => {
  const rate = incident(s.manaScrewRate);
  if (rate === undefined || rate <= MANA_SCREW_MAX) return undefined;
  // Gerechnet wird ueber manaScrewGames, nicht ueber v2Games: wer vor seinem 3. eigenen Zug ausschied,
  // steht in keinem der beiden Toepfe der Quote (siehe matchStats.v2Metrics).
  const games = s.manaScrewGames ?? s.v2Games;
  return {
    level: "warn", title: "Mana-Screw",
    text: `In ${pct(rate)} der ${games} Partien, die deinen 3. eigenen Zug überhaupt erreicht haben,`
      + " standen dort höchstens 2 Länder.",
    needs: deck && `Das Deck hat ${deck.lands} Länder bei Ø CMC ${num(deck.avgCmc)}.`,
    role: "ramp",
  };
};

/** Landflut: das Gegenstueck zum Screw - reichlich Land gespielt, fast nichts gewirkt. Braucht KEINE
 * Vorfall-Daten (lands/spells zaehlt die Bridge seit v1), haengt also nicht an `incident`. */
const flood: Rule = ({ s, deck }) => {
  if (s.floodRate <= FLOOD_MAX) return undefined;
  return {
    level: "warn", title: "Landflut",
    text: `In ${pct(s.floodRate)} der ${s.games} gewerteten Partien lagen ${FLOOD_LANDS} Länder oder mehr`
      + ` und es kamen höchstens ${FLOOD_MAX_SPELLS} Zauber dazu.`,
    needs: deck && `Das Deck hat ${deck.lands} Länder bei Ø CMC ${num(deck.avgCmc)}.`,
    role: "draw",
  };
};

/** Zu viele Mulligans - eine v1-Kennzahl, die Regel gilt also auch ohne Vorfall-Daten. Die Ø Laender
 * der Starthand kommen aus v2 und stehen nur dabei, wenn es sie gibt. */
const mulligans: Rule = ({ s }) => {
  if (s.mulliganRate <= MULLIGAN_MAX) return undefined;
  const opening = s.avgOpeningLands !== undefined
    ? ` Die behaltenen Starthände hatten Ø ${num(s.avgOpeningLands)} Länder.`
    : "";
  return {
    level: "warn", title: "Viele Mulligans",
    text: `${pct(s.mulliganRate)} der ${s.games} gewerteten Partien begannen mit mindestens einem Mulligan.${opening}`,
  };
};

/** Massenentfernung reisst das Brett weg und das Deck hat nichts dagegen. Ohne Deckanalyse schweigt die
 * Regel: der zweite Teil der Aussage ("und du hast nichts dagegen") laesst sich sonst nicht pruefen. */
const sweeps: Rule = ({ s, deck, incident }) => {
  const games = incident(s.sweepGames);
  if (games === undefined || !deck || deck.categories.wipeProtection !== 0) return undefined;
  const rate = games / s.v2Games;
  if (rate <= SWEEP_GAMES_MAX) return undefined;
  // Die Groesse gehoert zum Vorfall, nicht zur Partie: avgBiggestSweep mittelt ueber ALLE v2-Partien
  // (Nullen inbegriffen) und waere hier als "auf einmal" gelesen schlicht falsch. Ohne avgSweepSize
  // (alte Auswertung) bleibt der Satz bei der Haeufigkeit stehen.
  const size = s.avgSweepSize;
  return {
    level: "warn", title: "Massenentfernung",
    text: `In ${games} von ${s.v2Games} Partien mit Vorfall-Daten (${pct(rate)}) hat dich eine Massenentfernung getroffen`
      + (size !== undefined ? `, dabei im Schnitt ${num(size)} eigene bleibende Karten auf einmal.` : "."),
    needs: `Das Deck hat ${cards(0)}, die eine Massenentfernung überstehen (Unzerstörbar, Fluchsicher, Rückholer).`,
    role: "wipeProtection",
  };
};

/** Der Schaden kommt aus der Luft und das Deck kann nicht hochgucken. */
const flyers: Rule = ({ s, deck, incident }) => {
  const share = incident(s.flyingShare);
  if (share === undefined || share <= FLYING_SHARE_MAX) return undefined;
  if (!deck || deck.categories.flyerDefense > FLYER_DEFENSE_MAX) return undefined;
  return {
    level: "warn", title: "Flieger",
    text: `${pct(share)} des erlittenen Kampfschadens kam in ${s.v2Games} Partien mit Vorfall-Daten von fliegenden Kreaturen.`,
    needs: `Das Deck hat ${cards(deck.categories.flyerDefense)}, die Flieger aufhalten`
      + `${deck.categories.flyerDefense === 1 ? " kann" : " können"} (Fliegen, Reichweite).`,
    role: "flyerDefense",
  };
};

/** Die eigenen Zauber werden gekontert und es gibt nichts zurueck. Nur info: das entscheidet in erster
 * Linie der Gegner, nicht das eigene Deck. */
const countered: Rule = ({ s, deck, incident }) => {
  const rate = incident(s.counteredRate);
  if (rate === undefined || rate <= COUNTERED_MAX) return undefined;
  if (!deck || deck.categories.counters !== 0) return undefined;
  return {
    level: "info", title: "Gekonterte Zauber",
    text: `${pct(rate)} deiner Zauber wurden in ${s.v2Games} Partien mit Vorfall-Daten gekontert.`,
    needs: "Das Deck hat selbst 0 Konterzauber.",
    role: "counters",
  };
};

/** Zu viele Partien laufen in den Zugdeckel: dem Deck fehlt ein verlaesslicher Abschluss. Zaehlt die
 * nicht gewerteten Deckel-Partien mit (siehe DeckSummary.turnCappedRate) und braucht kein v2. */
const turnCapped: Rule = ({ s }) => {
  if (s.turnCappedRate <= TURN_CAPPED_MAX) return undefined;
  const played = s.games + s.turnCappedGames;
  return {
    level: "warn", title: "Zugdeckel",
    text: `${s.turnCappedGames} von ${played} gespielten Partien (${pct(s.turnCappedRate)}) endeten am Zugdeckel`
      + " - kein verlässlicher Abschluss.",
  };
};

/** Im Pod deutlich vor Partieende ausgeschieden. Nur im Pod: im Duell endet die Partie mit dem
 * Ausscheiden (der Anteil waere immer 1), und in "Alle" mischten sich beide Formate zu einer Zahl,
 * die keines von beiden beschreibt. */
const earlyOut: Rule = ({ s, format, incident }) => {
  if (format !== "pod") return undefined;
  const share = incident(s.avgEliminationShare);
  // Eigene Mindeststichprobe: gemittelt wird nur ueber die Partien, in denen der Sitz ausschied - und
  // genau die nennt der Satz auch. v2Games waere hier der falsche Nenner (und meist der groessere).
  const games = s.eliminationGames ?? 0;
  if (share === undefined || games < MIN_ELIMINATIONS || share >= ELIMINATION_SHARE_MIN) return undefined;
  return {
    level: "info", title: "Früh raus",
    text: `Du bist im Schnitt nach ${pct(share)} der Partiedauer ausgeschieden`
      + ` (${games} Partien, in denen du überhaupt ausgeschieden bist).`,
    role: "removal",
  };
};

/** Die Regeln in der Reihenfolge der Spec (§5). */
const RULES: Rule[] = [manaScrew, flood, mulligans, sweeps, flyers, countered, turnCapped, earlyOut];

/** Alle zutreffenden Befunde, Warnungen zuerst (innerhalb einer Stufe in Regelreihenfolge).
 *
 * Sonderfaelle, die bewusst GENAU EINEN Befund liefern: zu wenige Partien (oder gar keine
 * Zusammenfassung, weil noch kein Deck gewaehlt ist) und "nichts Auffaelliges" - der Block bleibt damit
 * nie leer und erklaert sich selbst. */
export function findings(s: DeckSummary | undefined, deck: DeckAnalysis | undefined, format: Format): Finding[] {
  if (!s || s.games < MIN_GAMES) {
    return [{
      level: "info", title: TOO_FEW_TITLE,
      text: `Zu wenige Partien für Aussagen (${s?.games ?? 0} von ${MIN_GAMES}${formatSuffix(format)}).`,
    }];
  }
  const games = s.games;
  const ctx: Ctx = {
    s, deck, format,
    // Eine Vorfall-Kennzahl gilt nur, wenn genug Partien sie ueberhaupt gezaehlt haben.
    incident: (value) => (s.v2Games >= MIN_GAMES ? value : undefined),
  };
  const found = RULES.map((rule) => rule(ctx)).filter((f): f is Finding => f !== undefined);
  if (found.length === 0) {
    return [{
      level: "info", title: NOTHING_TITLE,
      text: `Nichts Auffälliges bei ${games} gewerteten Partien${formatSuffix(format)}.`,
    }];
  }
  return [...found.filter((f) => f.level === "warn"), ...found.filter((f) => f.level === "info")];
}

/** Welche Rollen fehlen dem Deck am ehesten - Grundlage fuer die Kartenvorschlaege (Stueck 2). Erst die
 *  Rollen der Befunde (sie sind bereits durch echte Partien belegt, also die staerkste Evidenz), in ihrer
 *  Reihenfolge; danach fuellen die Richtwerte auf, sortiert nach der groessten Unterschreitung - bei
 *  Gleichstand entscheidet die Reihenfolge von ROLE_TARGETS. Ohne Deckanalyse gibt es keinen Deckcheck,
 *  also bleiben nur die Rollen der Befunde. Keine Dopplungen, hoechstens MAX_GAPS. */
export function roleGaps(deck: DeckAnalysis | undefined, found: Finding[]): Role[] {
  const gaps: Role[] = [];
  for (const f of found) {
    if (f.role && !gaps.includes(f.role)) gaps.push(f.role);
  }
  if (deck) {
    const shortfalls = (Object.entries(ROLE_TARGETS) as [Role, number][])
      .map(([role, target]) => ({ role, deficit: target - deck.categories[role] }))
      .filter((x) => x.deficit > 0)
      .sort((a, b) => b.deficit - a.deficit);
    for (const { role } of shortfalls) {
      if (!gaps.includes(role)) gaps.push(role);
    }
  }
  return gaps.slice(0, MAX_GAPS);
}

/** " im Duell" / " im Pod" - in "Alle" bleibt der Satz ohne Zusatz. */
function formatSuffix(format: Format): string {
  return format === "duel" ? " im Duell" : format === "pod" ? " im Pod" : "";
}

/** Anteil als ganze Prozent ("43 %"). */
function pct(share: number): string {
  return `${Math.round(share * 100)} %`;
}

/** "1 Karte" / "2 Karten" - der Satz steht sonst mit "1 Karten" da. Achtung: das Verb im Relativsatz
 * dahinter muss mitgehen ("die Flieger aufhalten kann"), sonst ist nur die halbe Zeile richtig. */
function cards(n: number): string {
  return `${n} ${n === 1 ? "Karte" : "Karten"}`;
}

/** Zahl mit deutschem Dezimalkomma ("3,4"). */
function num(value: number, digits = 1): string {
  return value.toFixed(digits).replace(".", ",");
}
