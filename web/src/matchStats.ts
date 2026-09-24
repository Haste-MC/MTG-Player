import type { MatchRecord, MatchSeat } from "./protocol";

// Die Auswertung ist deckbezogen, nicht spielerbezogen: wer das Deck gespielt hat (Mensch oder KI - im
// Sparring, Stueck 3, spielt eine KI Kevins Deck), ist unerheblich; entscheidend ist nur seat.deck. Je
// Partie zaehlt aber hoechstens ein Sitz je Deck (siehe pickSeat) - ein Spiegel (dasselbe Deck auf zwei
// Sitzen) ist eine Partie, kein Doppelzaehler.

/** Ausschlussgrund der Bridge fuer eine am Zugdeckel abgeschnittene Partie (MatchRecorder.markTurnCapped). */
const TURN_CAPPED = "Zugdeckel";

/** Die Auswertung trennt 1 vs 1 und Gruppe: dieselben Zahlen bedeuten in beiden Formaten Verschiedenes
 * (im Pod verliert man ueberwiegend, Schaden verteilt sich auf drei Gegner). "all" rechnet ueber beide -
 * fuer die Bilanz brauchbar, fuer alles Formatabhaengige mit Vorsicht zu geniessen. */
export type Format = "all" | "duel" | "pod";

/** Das Format einer Partie steckt allein in der Sitzzahl: genau zwei Sitze sind ein Duell, alles andere
 * ein Pod. Ein einziger Sitz (theoretisch moeglich) ist damit ausdruecklich KEIN Duell. */
export function formatOf(record: MatchRecord): "duel" | "pod" {
  return record.seats.length === 2 ? "duel" : "pod";
}

function inFormat(record: MatchRecord, format: Format): boolean {
  return format === "all" || formatOf(record) === format;
}

/** Ab dieser Formatversion traegt ein Datensatz die Vorfall-Kennzahlen aus Runde B. */
const V2 = 2;

/** Traegt dieser Datensatz die Vorfall-Kennzahlen? Ein v1-Datensatz kommt mit denselben Feldern auf 0 an
 * (Jackson serialisiert die int-Felder), dort wurde aber NIE gezaehlt - eine Quote daraus waere erfunden.
 * Ein fehlendes v gilt wie in der Bridge als 1. */
function isV2(record: MatchRecord): boolean {
  return (record.v ?? 1) >= V2;
}

// --- Definitionen der beiden Startkennzahlen. Sie stehen hier und nicht in findings.ts: das sind keine
// Regel-Schwellen, sondern die Messvorschrift selbst (was heisst "Screw", was heisst "Flut").
/** Gemessen wird im 3. EIGENEN Zug des Sitzes (Index in landsByTurn). */
export const MANA_SCREW_TURN = 3;
/** So viele Laender (oder weniger) im 3. eigenen Zug gelten als Mana-Screw. */
export const MANA_SCREW_LANDS = 2;
/** Flut, Teil 1: so viele Laender (oder mehr) hat der Sitz gespielt ... */
export const FLOOD_LANDS = 6;
/** ... Teil 2: und dabei hoechstens so viele Zauber gewirkt. Reichlich Land lag, gewirkt wurde fast
 * nichts - Farb- oder Kurvenmangel.
 *
 * Dieselbe Regel benutzt die Bench seit Stufe 2 fuer "Nichtstun"
 * (mtgplayer.bench.ActivityCounter, MIN_LANDS = 5 / MAX_SPELLS = 2, siehe
 * docs/bench/2026-09-20-stufe-2-nichtstun.md); hier stehen die Zahlen der Spec (6/2). Wer die Kennzahl
 * anfasst: KEINE dritte Definition erfinden, sondern beide Stellen zusammen bewegen.
 *
 * lands und spells gibt es seit v1 - die Flut-Quote haengt deshalb ausdruecklich NICHT an der
 * v2-Stichprobe, sondern rechnet ueber alle gewerteten Partien der Auswahl. */
export const FLOOD_MAX_SPELLS = 2;

/**
 * Schluessel, unter dem zwei Schreibweisen desselben Decks zusammenfinden: Forges
 * {@code DeckBase(String)} ersetzt in einem Decknamen jeden "/" durch "_" (Decks werden intern als
 * "verzeichnis/name" referenziert). Der DeckStore stellt den rohen Namen fuer die Lobby wieder her, ein
 * MatchRecord traegt dagegen den sanitisierten - Kevins Decks heissen darin
 * `"Titel" __ Commander` statt `"Titel" // Commander`. Seit die Bridge den Namen vom Aufrufer bekommt
 * (MatchRecorder), schreibt sie ihn roh; aeltere Datensaetze auf der Platte tragen aber weiter Forges
 * Schreibweise.
 *
 * Deshalb gruppiert JEDE Auswertung hier nach deckKey und nicht nach dem rohen Namen: sonst stuende
 * ein Deck, das vor und nach dieser Umstellung gespielt wurde, zweimal im Board - zwei Siegquoten,
 * zwei Kachel-Saetze, zwei Auffaelligkeiten - und dieselbe Spaltung noch einmal in der Gegner-Tabelle.
 * Angezeigt wird trotzdem ein lesbarer Name - der aus dem juengsten Datensatz (siehe bump); in
 * der Deckliste sticht ihn zusaetzlich der Speichername, wenn es ein eigenes Deck ist (BoardDeck).
 */
export const deckKey = (name: string) => name.replace(/\//g, "_");

/** Die Decks eines Datensatzes als deckKey -> anzuzeigender Name; ein Deck auf zwei Sitzen kommt nur
 * einmal vor. `skip` laesst einen Sitzindex aus (fuer die Gegner-Tabelle: der eigene Sitz). */
function seatDecks(record: MatchRecord, skip?: number): Map<string, string> {
  const decks = new Map<string, string>();
  record.seats.forEach((seat, index) => {
    if (index === skip) return;
    decks.set(deckKey(seat.deck), seat.deck);
  });
  return decks;
}

/** Sammelt Zaehler je deckKey und merkt sich dabei den Anzeigenamen des ZULETZT gesehenen Datensatzes.
 * Die Partienliste kommt von der Bridge aelteste zuerst (MatchStore haengt neue Datensaetze hinten an),
 * die neueste Schreibweise gewinnt also - und das ist die rohe aus der Lobby, nicht Forges alte. */
function bump<T extends object>(into: Map<string, { deck: string } & T>, key: string, deck: string,
  fresh: () => T): { deck: string } & T {
  const cur = into.get(key) ?? { deck, ...fresh() };
  cur.deck = deck;
  into.set(key, cur);
  return cur;
}

/** Ein Deck mit mindestens einer gewerteten oder einer Zugdeckel-Partie, fuer die Deckliste des
 * Statistik-Screens. `games` sind die gewerteten, `capped` die am Zugdeckel abgeschnittenen.
 *
 * `deck` ist ein ANZEIGENAME, kein Schluessel: zusammengefasst wurde nach {@link deckKey}, und zwei
 * Schreibweisen desselben Decks stehen hier als eine Zeile mit dem Namen des juengsten Datensatzes. */
export interface DeckGames { deck: string; games: number; capped: number }

/** Decks aus den gewerteten UND den Zugdeckel-Partien (ueber alle Sitze, nicht nur den eigenen), je mit
 * ihrer Partienzahl, absteigend sortiert nach `games + capped`, dann Name. Ein Deck auf zwei Sitzen
 * derselben Partie (Spiegel) zaehlt fuer diese Partie nur einmal. `format` beschraenkt auf Duelle bzw.
 * Pods (siehe formatOf); "all" zaehlt beide zusammen.
 *
 * Gruppiert wird nach {@link deckKey}: ein Deck, das mit beiden Schreibweisen in den Datensaetzen
 * steht, ist EIN Eintrag mit der Summe seiner Partien.
 *
 * Die Zugdeckel-Partien stehen hier mit drin, obwohl sie nicht gewertet sind: ein Deck, das AUSSCHLIESSLICH
 * am Deckel endet, hat keine Bilanz (summarize liefert undefined) und waere sonst im ganzen Screen nicht
 * auffindbar - ausgerechnet der Fall, den die Kennzahl zeigen soll. Alle anderen Ausschlussgruende
 * (Absturz, Abbruch, Aufgabe, zu kurz) bleiben aussen vor; sie sagen nichts ueber das Deck aus. */
export function deckGames(records: MatchRecord[], format: Format = "all"): DeckGames[] {
  const counts = new Map<string, { deck: string; games: number; capped: number }>();
  for (const r of records) {
    if (!inFormat(r, format)) continue;
    const counted = r.counted;
    if (!counted && r.excludeReason !== TURN_CAPPED) continue;
    for (const [key, deck] of seatDecks(r)) {
      const cur = bump(counts, key, deck, () => ({ games: 0, capped: 0 }));
      if (counted) cur.games += 1;
      else cur.capped += 1;
    }
  }
  return [...counts.values()]
    .map((v) => ({ deck: v.deck, games: v.games, capped: v.capped }))
    .sort((a, b) => b.games + b.capped - (a.games + a.capped) || a.deck.localeCompare(b.deck));
}

/** Eine Zeile der Deckliste im Statistik-Board: ein Deck mit seinen Partien und der Angabe, ob es uns
 * gehoert (`own`: es steht in der Lobby-Liste der gespeicherten Decks). Ein Deck, das wir nur als Gegner
 * gesehen haben, hat `own: false` - es bekommt keinen Sparring-Knopf, denn wir koennen es nicht spielen. */
export interface BoardDeck extends DeckGames {
  own: boolean;
  /** Name, unter dem die **Bridge** das Deck kennt (DeckStore), falls es eines von uns ist - nur damit
   *  darf man `sparringStart`/`analyzeDeck` schicken. `deck` ist dagegen der Name aus den Partien und
   *  bleibt der Schluessel fuer alles, was ueber `matches` rechnet (siehe deckKey). */
  savedName?: string;
}

/**
 * Die Deckliste des Boards: alle **gespeicherten** Decks plus alle Decks mit gewerteten oder
 * Zugdeckel-Partien (siehe {@link deckGames}). Die gespeicherten sind der Grund fuer diese zweite
 * Funktion: ein frisch importiertes Deck hat noch keine Partie, ist aber genau das Deck, mit dem man ein
 * Sparring starten will - ohne diese Zeile waere es im Board nicht auswaehlbar.
 *
 * Reihenfolge: erst die Decks mit Partien (wie in {@link deckGames}: nach Partienzahl absteigend, bei
 * Gleichstand nach Name), danach die uebrigen gespeicherten Decks alphabetisch.
 *
 * @param saved Namen der gespeicherten Decks (Store: `decks`), in beliebiger Reihenfolge
 */
export function boardDecks(records: MatchRecord[], saved: string[], format: Format = "all"): BoardDeck[] {
  const byKey = new Map(saved.map((name) => [deckKey(name), name]));
  const played = deckGames(records, format).map((d) => {
    const savedName = byKey.get(deckKey(d.deck));
    return savedName === undefined ? { ...d, own: false } : { ...d, own: true, savedName };
  });
  const seen = new Set(played.map((d) => deckKey(d.deck)));
  const rest = saved.filter((name) => !seen.has(deckKey(name))).sort((a, b) => a.localeCompare(b))
    .map((deck) => ({ deck, games: 0, capped: 0, own: true, savedName: deck }));
  return [...played, ...rest];
}

/** Waehlt fuer `deck` innerhalb einer Partie hoechstens einen Sitz: bevorzugt den menschlichen, sonst
 * den ersten passenden (Reihenfolge von `record.seats`). undefined, wenn kein Sitz dieses Deck spielt. */
function pickSeat(record: MatchRecord, deck: string): { seat: MatchSeat; index: number } | undefined {
  const key = deckKey(deck);
  const matching = record.seats
    .map((seat, index) => ({ seat, index }))
    .filter((e) => deckKey(e.seat.deck) === key);
  if (matching.length === 0) return undefined;
  return matching.find((e) => e.seat.human) ?? matching[0];
}

export interface DeckSummary {
  games: number; wins: number; losses: number; draws: number; winRate: number;
  ci: [number, number]; avgTurns: number; avgDurationMs: number; mulliganRate: number; avgMulligans: number;
  avgLandsTurn3?: number; avgLandsTurn5?: number; missedLandDropRate: number; avgMissedLandDrops: number;
  avgSpells: number; avgSpellMana: number; avgCommanderTurn?: number; avgCommanderTax: number;
  avgDamageDealt: number; avgDamageTaken: number; lossReasons: Record<string, number>;
  /** Ø Leben am Partieende (bzw. beim Ausscheiden - Forge haelt den Stand fest). Seit v1 gezaehlt. */
  avgLifeEnd: number;
  /** Ø Platz: 1 plus die Zahl der Mitspieler, die den Sitz ueberlebt haben (siehe placeOf). Im Duell
   * immer 1 oder 2 und damit nur eine andere Schreibweise der Siegquote - die Anzeige zeigt ihn nur im
   * Pod. Ueber ALLE gewerteten Partien der Auswahl, denn eliminatedTurn gibt es seit v1. */
  avgPlace: number;
  /** Ø Zug, in dem der Sitz ausgeschieden ist - nur ueber die Partien, in denen er ausschied; fehlt,
   * wenn er in keiner ueberhaupt ausgeschieden ist (dann gibt es nichts zu mitteln). */
  avgEliminatedTurn?: number;
  opponents: { deck: string; games: number; wins: number }[];
  /** Partien dieses Decks, die die Bridge am Zugdeckel abgeschnitten hat (excludeReason "Zugdeckel") -
   * nicht gewertet, aber selbst eine Kennzahl: ein Deck ohne verlaessliche Siegbedingung laeuft dort hinein. */
  turnCappedGames: number;
  /** Anteil der Zugdeckel-Partien an allen gespielten: turnCappedGames / (games + turnCappedGames). */
  turnCappedRate: number;
  /** Anteil der gewerteten Partien mit FLOOD_LANDS+ Laendern und hoechstens FLOOD_MAX_SPELLS Zaubern.
   * Steht bewusst HIER und nicht im Vorfall-Block: lands/spells zaehlt die Bridge seit v1, die Quote
   * gilt also auch fuer alte Partien. */
  floodRate: number;

  // --- Kennzahlen aus Runde B (die Flut-Quote oben gehoert NICHT dazu). Sie haben eine EIGENE
  // Stichprobe: v2Games, die Teilmenge der oben gezaehlten Partien mit v >= 2. Ist v2Games 0, fehlt
  // jede einzelne von ihnen (nicht 0 - in einem v1-Datensatz wurde nie gezaehlt). Sind beide Sorten dabei, rechnen sie NUR ueber die v2-Partien;
  // die Anzeige nennt darum v2Games neben games. Drei von ihnen (manaScrewRate, spellsPerTurn,
  // avgEliminationShare) koennte man technisch auch aus v1-Feldern rechnen - sie teilen sich hier
  // bewusst die eine Stichprobe des Vorfall-Blocks, damit alle Kacheln dieses Blocks dasselbe "n"
  // meinen und untereinander vergleichbar bleiben.
  /** Partien der Auswahl mit v >= 2 - die Grundlage aller folgenden Kennzahlen.
   *
   * ACHTUNG beim Beschriften: drei Kennzahlen rechnen ueber eine TEILMENGE davon und nennen ihre eigene
   * Stichprobe daneben - manaScrewRate ueber manaScrewGames, avgSweepSize ueber sweepGames,
   * avgEliminationShare ueber eliminationGames. Wer dort v2Games hinschreibt, behauptet einen groesseren
   * Nenner, als gerechnet wurde. */
  v2Games: number;
  /** Anteil der Partien mit hoechstens MANA_SCREW_LANDS Laendern im eigenen Zug MANA_SCREW_TURN; Nenner
   * sind nur die Partien, in denen der Sitz so viele eigene Zuege hatte (sonst ist nichts zu beurteilen). */
  manaScrewRate?: number;
  /** ANZAHL der v2-Partien, in denen der Sitz den Messzug ueberhaupt erlebt hat - der Nenner von
   * manaScrewRate. Kleiner als v2Games, sobald eine Partie vorher endete; wer die Quote in einem Satz
   * nennt, nennt DIESE Stichprobe und nicht v2Games. */
  manaScrewGames?: number;
  /** Ø Laender in der nach allen Mulligans behaltenen Starthand. */
  avgOpeningLands?: number;
  /** Zauber je eigenem Zug, gepoolt: alle Zauber geteilt durch alle eigenen Zuege. */
  spellsPerTurn?: number;
  /** Anteil der eigenen Zauber, die gekontert wurden (gepoolt ueber alle Partien). */
  counteredRate?: number;
  /** ANZAHL der Partien, in denen der Sitz mindestens eine Massenentfernung abbekommen hat (nicht Quote -
   * die Anzeige bildet sie bei Bedarf mit v2Games). */
  sweepGames?: number;
  /** Ø groesster Verlust in einem Aufloesungsfenster; eine Partie ohne Massenentfernung geht mit 0 ein
   * (eine gemessene 0, kein fehlender Wert). Ueber ALLE v2-Partien - "je Partie", nicht "je Vorfall". */
  avgBiggestSweep?: number;
  /** Ø groesster Verlust NUR ueber die sweepGames - also die Groesse einer Massenentfernung, wenn eine
   * kam. Fehlt, wenn keine kam (0 von 0 ist kein Mittelwert). Der Unterschied zu avgBiggestSweep ist
   * kein Detail: "im Schnitt 2,9 Karten" liest sich als "je Vorfall", gerechnet ist es aber je Partie. */
  avgSweepSize?: number;
  /** Ø verlorene eigene bleibende Karten je Partie (jede Quelle, nicht nur Massenentfernung). */
  avgPermanentsLost?: number;
  /** Anteil des erlittenen KAMPFschadens, der von Fliegern bzw. von Trampelschaden kam; fehlt, wenn der
   * Sitz in keiner v2-Partie Kampfschaden genommen hat (0 von 0 ist keine Quote). */
  flyingShare?: number;
  tramplingShare?: number;
  /** Ø eigene Angriffsdeklarationen je Partie bzw. Ø gegnerische Angreifer gegen diesen Sitz. */
  avgAttacks?: number;
  avgAttackersFaced?: number;
  /** Ø Handkarten beim Ausscheiden (bzw. am Partieende, wenn der Sitz ueberlebte). */
  avgHandEnd?: number;
  /** Ø eigene Entfernungs- bzw. Konterzauber je Partie. */
  avgRemovalCast?: number;
  avgCounterspellsCast?: number;
  /** Ø eliminatedTurn / turns - wie weit in die Partie hinein der Sitz durchgehalten hat (1 = bis zum
   * Schluss). Nur ueber die Partien, in denen der Sitz ausgeschieden ist; fehlt, wenn er immer ueberlebte. */
  avgEliminationShare?: number;
  /** ANZAHL der v2-Partien, in denen der Sitz ausgeschieden ist - die Stichprobe von
   * avgEliminationShare (hoechstens v2Games, im Duell oft deutlich weniger). */
  eliminationGames?: number;
}

/** Platz des Sitzes `index` in seiner Partie: 1 plus die Zahl der Mitspieler, die ihn ueberlebt haben.
 * Ueberlebt hat, wer gar nicht ausgeschieden ist (eliminatedTurn fehlt/null - der Sieger und bei einem
 * Remis alle Verbliebenen) oder spaeter ausschied. Gleichzeitig Ausgeschiedene teilen sich den Platz:
 * ein Doppel-K.-o. im selben Zug ist zweimal Platz 3, nicht Platz 3 und 4. */
function placeOf(record: MatchRecord, index: number): number {
  const own = record.seats[index].eliminatedTurn;
  const outlived = record.seats.filter((other, j) => {
    if (j === index) return false;
    if (other.eliminatedTurn == null) return own != null;      // ueberlebt - liegt vor jedem Ausgeschiedenen
    return own != null && other.eliminatedTurn > own;
  });
  return 1 + outlived.length;
}

/** Ein Sitz mit seiner Partie - die Arbeitseinheit von summarize (je Partie hoechstens einer, siehe pickSeat). */
interface Entry { record: MatchRecord; seat: MatchSeat; index: number }

/** Kennzahlen fuer `deck` ueber die gewerteten Partien, in denen ein Sitz dieses Deck traegt (verglichen
 * wird ueber {@link deckKey}, `deck` darf also in jeder der beiden Schreibweisen hereinkommen) -
 * je Partie hoechstens ein Sitz (siehe pickSeat; ein Spiegel zaehlt also als eine Partie). Metriken
 * stammen vom jeweils gewaehlten Sitz, die Gegner-Tabelle von den uebrigen Sitzen derselben Partie.
 * undefined, wenn keine gewertete Partie mit diesem Deck existiert - auch dann, wenn es Zugdeckel-Partien
 * gibt: die sind nicht gewertet und ergeben allein keine Bilanz.
 *
 * Auch die Gegner-Tabelle fasst nach {@link deckKey} zusammen und zeigt den Namen des juengsten
 * Datensatzes - sonst stuende derselbe Gegner dort zweimal mit halber Partienzahl.
 *
 * Einzige Ausnahme von der "nur gewertete Partien"-Regel sind turnCappedGames/turnCappedRate: sie zaehlen
 * genau die NICHT gewerteten Zugdeckel-Partien, nach derselben "ein Sitz je Partie"-Regel.
 *
 * `format` beschraenkt die Auswahl auf Duelle bzw. Pods (siehe formatOf) - und zwar fuer ALLE Zahlen
 * einschliesslich der Zugdeckel-Partien und der Gegner-Tabelle. Die Kennzahlen aus Runde B rechnen
 * innerhalb dieser Auswahl noch einmal nur ueber die Partien mit v >= 2 (siehe v2Metrics). */
export function summarize(records: MatchRecord[], deck: string, format: Format = "all"): DeckSummary | undefined {
  const entries: Entry[] = [];
  let turnCappedGames = 0;
  for (const r of records) {
    if (!inFormat(r, format)) continue;
    if (!r.counted) {
      if (r.excludeReason === TURN_CAPPED && pickSeat(r, deck)) turnCappedGames += 1;
      continue;
    }
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

  // Je Partie zaehlt auch auf der Gegenseite jedes Deck nur einmal (dieselbe Regel wie pickSeat und
  // deckGames): zwei Sitze mit demselben Gegner-Deck sind eine Partie gegen dieses Deck, kein Doppel.
  const opponents = new Map<string, { deck: string; games: number; wins: number }>();
  for (const e of entries) {
    for (const [key, other] of seatDecks(e.record, e.index)) {
      const cur = bump(opponents, key, other, () => ({ games: 0, wins: 0 }));
      cur.games += 1;
      if (e.seat.winner) cur.wins += 1;
    }
  }

  const commanderTurns = entries
    .map((e) => e.seat.firstCommanderTurn)
    .filter((t): t is number => t != null);
  const eliminatedTurns = entries
    .map((e) => e.seat.eliminatedTurn)
    .filter((t): t is number => t != null);

  return {
    games, wins, losses, draws, winRate: wins / games, ci: wilson(wins, games),
    avgTurns: avg(entries.map((e) => e.record.turns)),
    avgDurationMs: avg(entries.map((e) => e.record.durationMs)),
    mulliganRate: entries.filter((e) => e.seat.mulligans >= 1).length / games,
    // Flut: reichlich Land gespielt, fast nichts gewirkt - ueber ALLE gewerteten Partien der Auswahl,
    // denn lands/spells gibt es seit v1 (siehe FLOOD_MAX_SPELLS).
    floodRate: entries.filter((e) => e.seat.lands >= FLOOD_LANDS && e.seat.spells <= FLOOD_MAX_SPELLS).length / games,
    avgMulligans: avg(entries.map((e) => e.seat.mulligans)),
    ...landsTurn("avgLandsTurn3", entries, 3),
    ...landsTurn("avgLandsTurn5", entries, 5),
    missedLandDropRate: entries.filter((e) => e.seat.missedLandDrops >= 1).length / games,
    avgMissedLandDrops: avg(entries.map((e) => e.seat.missedLandDrops)),
    avgSpells: avg(entries.map((e) => e.seat.spells)),
    avgSpellMana: avg(entries.map((e) => e.seat.spellMana)),
    ...(commanderTurns.length > 0 ? { avgCommanderTurn: avg(commanderTurns) } : {}),
    avgCommanderTax: avg(entries.map((e) => e.seat.commanderTax)),
    avgDamageDealt: avg(entries.map((e) => e.seat.damageDealt)),
    avgDamageTaken: avg(entries.map((e) => e.seat.damageTaken)),
    avgLifeEnd: avg(entries.map((e) => e.seat.lifeEnd)),
    avgPlace: avg(entries.map((e) => placeOf(e.record, e.index))),
    ...opt("avgEliminatedTurn", eliminatedTurns.length > 0 ? avg(eliminatedTurns) : undefined),
    lossReasons,
    ...v2Metrics(entries.filter((e) => isV2(e.record))),
    turnCappedGames,
    // Nenner sind alle gespielten Partien (gewertete + Deckel); games ist hier immer >= 1, die 0 steht
    // nur da, damit die Formel fuer sich genommen nicht durch 0 teilt.
    turnCappedRate: games + turnCappedGames > 0 ? turnCappedGames / (games + turnCappedGames) : 0,
    opponents: [...opponents.values()]
      .map((v) => ({ deck: v.deck, games: v.games, wins: v.wins }))
      .sort((a, b) => b.games - a.games || a.deck.localeCompare(b.deck)),
  };
}

/** Die Vorfall-Kennzahlen aus Runde B ueber `v2` - die Partien der Auswahl mit v >= 2. Ist die Liste
 * leer, kommt nur v2Games: 0 zurueck und JEDER andere Schluessel fehlt: in einem v1-Datensatz wurde nie
 * gezaehlt, eine 0 waere eine Behauptung ueber Daten, die es nicht gibt (die Kachel zeigt dann "–").
 *
 * Quoten sind gepoolt (Summe durch Summe), nicht als Mittel der Einzelquoten: sonst wiegt eine Partie
 * mit zwei Zaubern so schwer wie eine mit zwanzig. Wo der Nenner 0 ist (kein Zauber gewirkt, keinen
 * Kampfschaden genommen, nie ausgeschieden), fehlt die Quote ebenfalls - 0 von 0 ist keine Quote. */
function v2Metrics(v2: Entry[]): Partial<DeckSummary> & { v2Games: number } {
  if (v2.length === 0) return { v2Games: 0 };
  const seats = v2.map((e) => e.seat);
  /** Summe eines Vorfall-Feldes; ein fehlendes Feld zaehlt als 0 (ein v2-Sitz traegt sie alle). */
  const sum = (pick: (seat: MatchSeat) => number | undefined) => seats.reduce((a, seat) => a + (pick(seat) ?? 0), 0);
  const mean = (pick: (seat: MatchSeat) => number | undefined) => sum(pick) / seats.length;
  /** Quote mit Nennerpruefung: ohne Grundlage lieber nichts sagen. */
  const share = (part: number, whole: number) => (whole > 0 ? part / whole : undefined);

  // Mana-Screw: nur Partien, in denen der Sitz den Messzug ueberhaupt erlebt hat (landsByTurn hat genau
  // ownTurns+1 Eintraege) - wer vorher ausschied, ist kein Beleg fuer "zu wenig Land".
  const judgeable = seats.filter((seat) => seat.landsByTurn.length > MANA_SCREW_TURN);
  const screwed = judgeable.filter((seat) => seat.landsByTurn[MANA_SCREW_TURN] <= MANA_SCREW_LANDS);
  // Eigene Zuege insgesamt - Nenner fuer "Zauber je Zug".
  const ownTurns = seats.reduce((a, seat) => a + Math.max(0, seat.landsByTurn.length - 1), 0);
  const combatTaken = sum((seat) =>
    (seat.damageTakenFlying ?? 0) + (seat.damageTakenTrample ?? 0) + (seat.damageTakenOther ?? 0));
  const eliminated = v2.filter((e) => e.seat.eliminatedTurn != null && e.record.turns > 0);
  // Partien MIT Massenentfernung - Stichprobe von avgSweepSize (avgBiggestSweep mittelt dagegen ueber alle).
  const swept = seats.filter((seat) => (seat.sweepsSuffered ?? 0) >= 1);

  return {
    v2Games: v2.length,
    ...opt("manaScrewRate", share(screwed.length, judgeable.length)),
    manaScrewGames: judgeable.length,
    avgOpeningLands: mean((seat) => seat.openingLands),
    ...opt("spellsPerTurn", share(sum((seat) => seat.spells), ownTurns)),
    ...opt("counteredRate", share(sum((seat) => seat.spellsCountered), sum((seat) => seat.spells))),
    sweepGames: swept.length,
    avgBiggestSweep: mean((seat) => seat.biggestSweep),
    ...opt("avgSweepSize", swept.length > 0 ? avg(swept.map((seat) => seat.biggestSweep ?? 0)) : undefined),
    avgPermanentsLost: mean((seat) => seat.permanentsLost),
    ...opt("flyingShare", share(sum((seat) => seat.damageTakenFlying), combatTaken)),
    ...opt("tramplingShare", share(sum((seat) => seat.damageTakenTrample), combatTaken)),
    avgAttacks: mean((seat) => seat.attacksDeclared),
    avgAttackersFaced: mean((seat) => seat.attackersFaced),
    avgHandEnd: mean((seat) => seat.handEnd),
    avgRemovalCast: mean((seat) => seat.removalCast),
    avgCounterspellsCast: mean((seat) => seat.counterspellsCast),
    ...opt("avgEliminationShare", eliminated.length > 0
      ? avg(eliminated.map((e) => (e.seat.eliminatedTurn as number) / e.record.turns))
      : undefined),
    eliminationGames: eliminated.length,
  };
}

/** { key: value } fuer einen vorhandenen Wert, sonst ein leeres Objekt - so FEHLT der Schluessel im
 * Ergebnis, statt auf undefined zu stehen (siehe landsTurn). */
function opt<K extends string>(key: K, value: number | undefined): Partial<Record<K, number>> {
  return value === undefined ? {} : ({ [key]: value } as Record<K, number>);
}

/** Mittelwert der Laender bis zum EIGENEN Zug `turn`, nur ueber die Partien, in denen der Sitz so viele
 * eigene Zuege ueberhaupt hatte (`landsByTurn.length > turn`; die Liste hat genau ownTurns+1 Eintraege).
 * Kuerzere Partien werden ausgelassen statt auf den letzten Eintrag geklemmt - sonst zoege jeder frueh
 * ausgeschiedene Sitz den Mittelwert nach unten und die Kachel behauptete eine Kurve, die es nie gab.
 * Ohne passende Partie fehlt der Schluessel (die Kachel zeigt dann "–"). */
function landsTurn<K extends string>(key: K, entries: { seat: MatchSeat }[], turn: number): Partial<Record<K, number>> {
  const values = entries.filter((e) => e.seat.landsByTurn.length > turn).map((e) => e.seat.landsByTurn[turn]);
  return values.length > 0 ? ({ [key]: avg(values) } as Record<K, number>) : {};
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
