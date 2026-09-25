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

/** Zahl mit deutschem Dezimalkomma - eine eigene, winzige Kopie statt eines Imports aus
 *  components/StatTiles: deckCards.ts bleibt so browserfrei testbar (wie schon matchStats.ts). */
const one = (x: number) => x.toFixed(1).replace(".", ",");

/**
 * Ein Satz je Kartenzeile, gebaut aus bis zu fuenf Teilaussagen. `withCardData` ist die Grundlage JEDER
 * Zahl darin (siehe Datei-Kopf), nicht `games`.
 *
 *  - nie gezogen (handGames === 0): die Karte tauchte in keiner Partie mit Aufzeichnung auf - eigener,
 *    kurzer Satz statt "0 von X auf der Hand", der nur zum Nachrechnen einlaeden wuerde.
 *  - sonst: "H von withCardData Partien auf der Hand", dahinter entweder "nie gewirkt" (castGames === 0)
 *    oder "N-mal gewirkt" mit dem Schnitt ihres fruehesten Wirk-Zugs, wenn die Bridge einen mitschickt.
 *  - liegen geblieben (stuckGames > 0): ein Teil der Hand-Partien blieb trotzdem ungespielt - das kann
 *    neben "nie gewirkt" (dann stuckGames === handGames) genauso stehen wie neben "N-mal gewirkt"
 *    (nur ein Teil der Partien blieb liegen).
 *  - gekontert/verloren nur, wenn sie tatsaechlich vorkamen (0 ist keine Aussage wert).
 */
export function cardLine(card: CardStat, withCardData: number): string {
  if (card.handGames === 0) {
    return `In keiner der ${withCardData} Partien mit Aufzeichnung gezogen.`;
  }
  const parts = [`${card.handGames} von ${withCardData} Partien auf der Hand`];
  if (card.castGames === 0) {
    parts.push("nie gewirkt");
  } else {
    const turn = card.avgCastTurn !== undefined ? ` (Ø Zug ${one(card.avgCastTurn)})` : "";
    parts.push(`${card.castGames === 1 ? "einmal" : card.castGames + "-mal"} gewirkt${turn}`);
  }
  if (card.stuckGames > 0) {
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
