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
  it("nie gewirkt: 'liegen geblieben' entfaellt, weil es dieselbe Aussage waere (stuckGames === handGames)", () => {
    const c = card({ name: "Cyclonic Rift", handGames: 3, stuckGames: 3 });
    expect(cardLine(c, 5)).toBe("3 von 5 Partien auf der Hand, nie gewirkt.");
  });

  it("nie gewirkt, aber NICHT in jeder Hand-Partie liegen geblieben: der dritte Teil bleibt, weil er sich "
    + "nicht aus den ersten beiden ergibt (Grenzfall, siehe Kommentar an cardLine)", () => {
    const c = card({ name: "Grenzfall-Karte", handGames: 5, stuckGames: 3 });
    expect(cardLine(c, 6)).toBe("5 von 6 Partien auf der Hand, nie gewirkt, 3 von 5 liegen geblieben.");
  });

  it("mehrfach gewirkt mit einem echten Bruch als Durchschnittszug (Zuege waren nicht immer gleich)", () => {
    const c = card({ name: "Sol Ring", handGames: 8, castGames: 8, avgCastTurn: 1.5 });
    expect(cardLine(c, 9)).toBe("8 von 9 Partien auf der Hand, in 8 von 9 Partien gewirkt (Ø ab Zug 1,5).");
  });

  it("mehrfach gewirkt mit glattem Durchschnittszug: keine Nachkommastelle - ',0' waere unechte Genauigkeit", () => {
    const c = card({ name: "Krenko, Mob Boss", handGames: 9, castGames: 7, stuckGames: 2, avgCastTurn: 3 });
    expect(cardLine(c, 9))
      .toBe("9 von 9 Partien auf der Hand, in 7 von 9 Partien gewirkt (Ø ab Zug 3), 2 von 9 liegen geblieben.");
  });

  it("nie gezogen UND nie gewirkt: kein Satz ueber withCardData, sondern der eigene Hinweis", () => {
    const c = card({ name: "Blasphemous Act", neverDrawnGames: 6 });
    expect(cardLine(c, 6)).toBe("In keiner der 6 Partien mit Aufzeichnung gezogen.");
  });

  it("Befund 1: aus dem Friedhof/Exil gewirkt, nie auf der Hand - castGames, Zug, gekontert und "
    + "verloren bleiben stehen, statt (wie vor der Behebung) komplett zu verschwinden", () => {
    const c = card({
      name: "Reanimated Giant", handGames: 0, castGames: 8, avgCastTurn: 2, counteredGames: 1, lostGames: 3,
    });
    expect(cardLine(c, 9)).toBe(
      "nie gezogen, aber in 8 von 9 Partien gewirkt (Ø ab Zug 2), in 1 Partie gekontert, in 3 Partien verloren.");
  });

  it("liegen geblieben (teilweise): trotz Wirkung blieb sie in einem Teil der Partien ungespielt - "
    + "diese Zahl bleibt IMMER stehen, wenn castGames > 0", () => {
    const c = card({ name: "Wrath of God", handGames: 4, castGames: 1, stuckGames: 3, avgCastTurn: 6 });
    expect(cardLine(c, 4))
      .toBe("4 von 4 Partien auf der Hand, in 1 von 4 Partien gewirkt (Ø ab Zug 6), 3 von 4 liegen geblieben.");
  });

  it("gekontert und verloren stehen nur, wenn sie vorkamen, als Partien- nicht als Ereigniszaehlung (Befund 2)", () => {
    const c = card({ name: "Craterhoof Behemoth", handGames: 2, castGames: 1, counteredGames: 1, lostGames: 1 });
    expect(cardLine(c, 2))
      .toBe("2 von 2 Partien auf der Hand, in 1 von 2 Partien gewirkt, in 1 Partie gekontert, in 1 Partie verloren.");
  });

  it("gekontert und verloren in der Mehrzahl (Befund 2): 'in N Partien', nicht 'N-mal'", () => {
    const c = card({ name: "Cyclonic Rift", handGames: 5, castGames: 3, counteredGames: 2, lostGames: 2 });
    expect(cardLine(c, 5))
      .toBe("5 von 5 Partien auf der Hand, in 3 von 5 Partien gewirkt, in 2 Partien gekontert, in 2 Partien verloren.");
  });
});
