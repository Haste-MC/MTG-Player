import { describe, expect, it } from "vitest";
import { cardLine, sortCards } from "./deckCards";
import type { CardStat } from "./protocol";

/** Minimalkarte mit allen Pflichtfeldern auf 0 - jeder Test ueberschreibt nur, was er braucht. */
function card(over: Partial<CardStat> & { name: string }): CardStat {
  return { handGames: 0, castGames: 0, stuckGames: 0, neverDrawnGames: 0, counteredGames: 0, lostGames: 0, ...over };
}

describe("sortCards", () => {
  // Dieselben drei Karten in allen drei Faellen, absichtlich NICHT in der erwarteten Reihenfolge
  // hereingereicht - sonst wuerde ein Test, der die Eingabe einfach zurueckgibt, ebenfalls bestehen.
  const chandra = card({ name: "Chandra, Torch of Defiance", stuckGames: 1, handGames: 3, castGames: 2 });
  const ana = card({ name: "Anafenza, the Foremost", stuckGames: 3, handGames: 2, castGames: 5 });
  const beta = card({ name: "Beast Within", stuckGames: 3, handGames: 5, castGames: 5 });
  const input = [chandra, beta, ana];

  it("Handlungsbedarf: liegen geblieben zuerst, dann am haeufigsten auf der Hand, Rest alphabetisch", () => {
    // Ana und Beta haben beide stuckGames 3 - der Tie-Break ist handGames (Beta 5 vor Ana 2).
    expect(sortCards(input, "action").map((c) => c.name)).toEqual([beta.name, ana.name, chandra.name]);
  });

  it("Name: alphabetisch", () => {
    expect(sortCards(input, "name").map((c) => c.name)).toEqual([ana.name, beta.name, chandra.name]);
  });

  it("am haeufigsten gewirkt: Ana und Beta haben beide 5, alphabetisch als Tie-Break", () => {
    expect(sortCards(input, "cast").map((c) => c.name)).toEqual([ana.name, beta.name, chandra.name]);
  });

  it("sortiert eine Kopie - die uebergebene Liste bleibt unveraendert", () => {
    const before = [...input];
    sortCards(input, "name");
    expect(input).toEqual(before);
  });
});

describe("cardLine", () => {
  it("nie gewirkt: auf der Hand, aber nie gespielt", () => {
    const c = card({ name: "Cyclonic Rift", handGames: 3, stuckGames: 3 });
    expect(cardLine(c, 5)).toBe("3 von 5 Partien auf der Hand, nie gewirkt, 3 von 3 liegen geblieben.");
  });

  it("mehrfach gewirkt mit Durchschnittszug", () => {
    const c = card({ name: "Sol Ring", handGames: 8, castGames: 8, avgCastTurn: 1.5 });
    expect(cardLine(c, 9)).toBe("8 von 9 Partien auf der Hand, 8-mal gewirkt (Ø Zug 1,5).");
  });

  it("nie gezogen: kein Satz ueber withCardData, sondern der eigene Hinweis", () => {
    const c = card({ name: "Blasphemous Act", neverDrawnGames: 6 });
    expect(cardLine(c, 6)).toBe("In keiner der 6 Partien mit Aufzeichnung gezogen.");
  });

  it("liegen geblieben (teilweise): trotz Wirkung blieb sie in einem Teil der Partien ungespielt", () => {
    const c = card({ name: "Wrath of God", handGames: 4, castGames: 1, stuckGames: 3, avgCastTurn: 6 });
    expect(cardLine(c, 4)).toBe("4 von 4 Partien auf der Hand, einmal gewirkt (Ø Zug 6,0), 3 von 4 liegen geblieben.");
  });

  it("gekontert und verloren stehen nur, wenn sie vorkamen", () => {
    const c = card({ name: "Craterhoof Behemoth", handGames: 2, castGames: 1, counteredGames: 1, lostGames: 1 });
    expect(cardLine(c, 2)).toBe("2 von 2 Partien auf der Hand, einmal gewirkt, 1-mal gekontert, 1-mal verloren.");
  });
});
