import type { CardStat } from "./protocol";

// Kartentabelle je Deck (Stueck 6, Bridge: mtgplayer.stats.CardStats): reine Sortier- und Satzbau-Logik,
// getrennt von der Anzeige (components/DeckCards.tsx) - dieselbe Aufteilung wie bei suggestFreshness.ts,
// damit sich beides ohne Browser testen laesst.
//
// WICHTIG: JEDE Zahl an einer CardStat bezieht sich auf CardStatsMsg.withCardData, NICHT auf games - eine
// gewertete Partie ohne lesbare Kartendatei (alte Partien vor dieser Aufzeichnung) zaehlt in games, aber
// nicht in withCardData. Ein Satz wie "6 von 12 Partien auf der Hand" waere darum falsch, wenn nur 9 der
// 12 Partien ueberhaupt Aufzeichnung haben - die 6 beziehen sich auf die 9, nicht auf die 12.
//
// Befund 10 (Ausnahme von "JEDE Zahl"): die "liegen geblieben"-Zahl haelt NICHT gegen withCardData,
// sondern gegen handGames - "2 von 8 liegen geblieben" heisst "2 der 8 Hand-Partien", nicht "2 der 8
// Partien mit Aufzeichnung" (withCardData kann groesser sein, wenn die Karte manchmal ungezogen blieb).

/** Die drei Sortierungen der Tabelle (Task-6-Brief: "Handlungsbedarf", "Name", "am haeufigsten
 *  gewirkt"). "action" baut dieselbe Regel wie CardStats.of auf der Bridge nach (dort schon die
 *  Ankunftsreihenfolge) - hier noch einmal, damit ein Zurueckschalten unabhaengig von einer zwischenzeitig
 *  anders sortierten Anzeige wieder denselben Stand zeigt. */
export type SortMode = "action" | "name" | "cast";

/** Sortiert eine KOPIE von `cards` - das Original (u. a. die Bridge-Reihenfolge im Store) bleibt unangetastet. */
export function sortCards(cards: CardStat[], mode: SortMode): CardStat[] {
  const sorted = [...cards];
  switch (mode) {
    case "name":
      return sorted.sort((a, b) => a.name.localeCompare(b.name, "de"));
    case "cast":
      return sorted.sort((a, b) => b.castGames - a.castGames || a.name.localeCompare(b.name, "de"));
    case "action":
    default:
      // Liegen gebliebene Karten zuerst (groesster Handlungsbedarf), dann die haeufigsten Handkarten,
      // Rest alphabetisch - identisch mit dem Comparator in CardStats.of.
      return sorted.sort((a, b) =>
        b.stuckGames - a.stuckGames || b.handGames - a.handGames || a.name.localeCompare(b.name, "de"));
  }
}

/** Zahl mit deutschem Dezimalkomma - dieselbe Formatierung wie StatTiles.one (toFixed(1) + Komma statt
 *  Punkt), aber eine eigene, winzige Kopie statt eines Imports aus components/StatTiles: kein Modul
 *  unter web/src/*.ts importiert bisher aus components/*.tsx (reine Module bleiben unterhalb der
 *  Komponenten, nie umgekehrt) - dasselbe Prinzip wie das lokale `pct` in Suggestions.tsx, das dieselbe
 *  Formel wie StatTiles auch nicht importiert. */
const one = (x: number) => x.toFixed(1).replace(".", ",");

/** Ø-Zug ohne unechte Genauigkeit: eine Nachkommastelle bleibt nur stehen, wenn sie etwas aussagt -
 *  naemlich dass die Karte NICHT immer im selben Zug gewirkt wurde (Zuege 3 und 4 ergeben z. B. 3,5).
 *  Ist der Schnitt eine glatte Zahl, behauptet ",0" eine Genauigkeit, die castGames (oft nur eine
 *  Handvoll Partien, siehe Datei-Kopf) nicht hergibt - "Zug 3" statt "Zug 3,0" ist die ehrlichere
 *  Angabe UND kuerzer. Bei einer echten Bruchzahl bleibt die eine Nachkommastelle (wie bei StatTiles.one)
 *  stehen, denn eine zweite waere fuer eine Zug-Zaehlung ohnehin nie zu rechtfertigen. */
function turnLabel(avg: number): string {
  return Number.isInteger(avg) ? String(avg) : one(avg);
}

/**
 * Ein Satz je Kartenzeile, gebaut aus bis zu sechs Teilaussagen - aber nur aus den Teilen, die dem Leser
 * tatsaechlich etwas Neues sagen (Kevin schneidet seine Karten nach genau diesen Saetzen, siehe
 * DeckCards.tsx). `withCardData` ist die Grundlage JEDER Zahl darin (siehe Datei-Kopf, Befund 10 fuer die
 * eine Ausnahme).
 *
 *  - nie gezogen UND nie gewirkt (handGames === 0 && castGames === 0): die Karte tauchte in keiner Partie
 *    mit Aufzeichnung auf - eigener, kurzer Satz statt "0 von X auf der Hand, nie gewirkt", der nur zum
 *    Nachrechnen einlaeden wuerde.
 *  - nie gezogen, ABER gewirkt (Befund 1: handGames === 0 UND castGames > 0): eine aus dem Friedhof oder
 *    Exil gewirkte Karte ist im Reanimator-Deck der NORMALFALL, nicht der Rand - "nie gezogen" bleibt
 *    stehen (wahr und relevant), verschluckt aber nicht mehr castGames/counteredGames/lostGames wie
 *    zuvor der sofortige Ausstieg oben.
 *  - sonst: "H von withCardData Partien auf der Hand", dahinter "nie gewirkt" (castGames === 0) oder
 *    "in C von withCardData Partien gewirkt" mit dem Schnitt ihrer fruehesten Wirk-Zuege, wenn die
 *    Bridge einen mitschickt (Befund 2: das ist eine PARTIEN-Zaehlung, keine Ereigniszaehlung - eine
 *    Karte, die in einer Partie dreimal gewirkt wurde und in einer zweiten einmal, ist "in 2 von N
 *    Partien gewirkt", nicht "3-mal").
 *  - liegen geblieben (stuckGames > 0): ein Teil der Hand-Partien blieb trotzdem ungespielt. War die
 *    Karte NIE gewirkt UND stuckGames === handGames, ist das exakt dieselbe Aussage wie "nie gewirkt"
 *    (wer nie gewirkt hat, ist in jeder Hand-Partie liegen geblieben) - die Teilaussage entfaellt dann.
 *    Sonst bleibt sie stehen: castGames und handGames sind UNABHAENGIG gezaehlte Groessen (eine aus dem
 *    Friedhof gewirkte Karte zaehlt z. B. NICHT als Hand-Partie, siehe Kartenaufzeichnung Task 3 im
 *    Ledger) - stuckGames laesst sich in diesem Fall NICHT einfach aus handGames und castGames herleiten,
 *    die Zahl bleibt also eine echte, neue Information.
 *  - gekontert/verloren (Befund 2, dieselbe Partien-Zaehlung wie beim Wirken): "in K Partie(n) gekontert"
 *    bzw. "in L Partie(n) verloren", nur wenn sie tatsaechlich vorkamen (0 ist keine Aussage wert).
 */
export function cardLine(card: CardStat, withCardData: number): string {
  const neverCast = card.castGames === 0;
  if (card.handGames === 0 && neverCast) {
    return `In keiner der ${withCardData} Partien mit Aufzeichnung gezogen.`;
  }

  // Befund 11: avgCastTurn ist der Schnitt der FRUEHESTEN Wirk-Zuege je Partie, nicht "der" Wirk-Zug -
  // "Ø ab Zug 3" sagt das, "Ø Zug 3" laese sich wie ein fester, einziger Zeitpunkt.
  const turn = card.avgCastTurn !== undefined ? ` (Ø ab Zug ${turnLabel(card.avgCastTurn)})` : "";
  const castClause = neverCast ? "nie gewirkt" : `in ${card.castGames} von ${withCardData} Partien gewirkt${turn}`;

  const parts = card.handGames === 0
    ? [`nie gezogen, aber ${castClause}`]
    : [`${card.handGames} von ${withCardData} Partien auf der Hand`, castClause];

  const stuckIsRedundant = neverCast && card.stuckGames === card.handGames;
  if (card.stuckGames > 0 && !stuckIsRedundant) {
    parts.push(`${card.stuckGames} von ${card.handGames} liegen geblieben`);
  }
  if (card.counteredGames > 0) {
    parts.push(`in ${card.counteredGames} ${card.counteredGames === 1 ? "Partie" : "Partien"} gekontert`);
  }
  if (card.lostGames > 0) {
    parts.push(`in ${card.lostGames} ${card.lostGames === 1 ? "Partie" : "Partien"} verloren`);
  }
  return parts.join(", ") + ".";
}
