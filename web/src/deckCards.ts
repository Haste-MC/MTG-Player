import type { CardStat } from "./protocol";

// Kartentabelle je Deck (Stueck 6, Bridge: mtgplayer.stats.CardStats): reine Sortier- und Satzbau-Logik,
// getrennt von der Anzeige (components/DeckCards.tsx) - dieselbe Aufteilung wie bei suggestFreshness.ts,
// damit sich beides ohne Browser testen laesst.
//
// WICHTIG: JEDE Zahl an einer CardStat bezieht sich auf CardStatsMsg.withCardData, NICHT auf games - eine
// gewertete Partie ohne lesbare Kartendatei (alte Partien vor dieser Aufzeichnung) zaehlt in games, aber
// nicht in withCardData. Ein Satz wie "6 von 12 Partien auf der Hand" waere darum falsch, wenn nur 9 der
// 12 Partien ueberhaupt Aufzeichnung haben - die 6 beziehen sich auf die 9, nicht auf die 12.

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
 * Ein Satz je Kartenzeile, gebaut aus bis zu fuenf Teilaussagen - aber nur aus den Teilen, die dem Leser
 * tatsaechlich etwas Neues sagen (Kevin schneidet seine Karten nach genau diesen Saetzen, siehe
 * DeckCards.tsx). `withCardData` ist die Grundlage JEDER Zahl darin (siehe Datei-Kopf), nicht `games`.
 *
 *  - nie gezogen (handGames === 0): die Karte tauchte in keiner Partie mit Aufzeichnung auf - eigener,
 *    kurzer Satz statt "0 von X auf der Hand", der nur zum Nachrechnen einlaeden wuerde.
 *  - sonst: "H von withCardData Partien auf der Hand", dahinter entweder "nie gewirkt" (castGames === 0)
 *    oder "N-mal gewirkt" mit dem Schnitt ihres fruehesten Wirk-Zugs, wenn die Bridge einen mitschickt.
 *  - liegen geblieben (stuckGames > 0): ein Teil der Hand-Partien blieb trotzdem ungespielt. War die
 *    Karte NIE gewirkt UND stuckGames === handGames, ist das exakt dieselbe Aussage wie "nie gewirkt"
 *    (wer nie gewirkt hat, ist in jeder Hand-Partie liegen geblieben) - die dritte Teilaussage entfaellt
 *    dann. Sonst bleibt sie stehen: castGames und handGames sind UNABHAENGIG gezaehlte Groessen (eine
 *    aus dem Friedhof gewirkte Karte zaehlt z. B. NICHT als Hand-Partie, siehe Kartenaufzeichnung Task 3
 *    im Ledger) - stuckGames laesst sich in diesem Fall NICHT einfach aus handGames und castGames
 *    herleiten, die Zahl bleibt also eine echte, neue Information.
 *  - gekontert/verloren nur, wenn sie tatsaechlich vorkamen (0 ist keine Aussage wert).
 */
export function cardLine(card: CardStat, withCardData: number): string {
  if (card.handGames === 0) {
    return `In keiner der ${withCardData} Partien mit Aufzeichnung gezogen.`;
  }
  const parts = [`${card.handGames} von ${withCardData} Partien auf der Hand`];
  const stuckIsRedundant = card.castGames === 0 && card.stuckGames === card.handGames;
  if (card.castGames === 0) {
    parts.push("nie gewirkt");
  } else {
    const turn = card.avgCastTurn !== undefined ? ` (Ø Zug ${turnLabel(card.avgCastTurn)})` : "";
    parts.push(`${card.castGames === 1 ? "einmal" : card.castGames + "-mal"} gewirkt${turn}`);
  }
  if (card.stuckGames > 0 && !stuckIsRedundant) {
    parts.push(`${card.stuckGames} von ${card.handGames} liegen geblieben`);
  }
  if (card.counteredGames > 0) {
    parts.push(`${card.counteredGames}-mal gekontert`);
  }
  if (card.lostGames > 0) {
    parts.push(`${card.lostGames}-mal verloren`);
  }
  return parts.join(", ") + ".";
}
