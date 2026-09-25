import { useState } from "react";
import { cardLine, sortCards, type SortMode } from "../deckCards";
import type { CardStat } from "../protocol";
import { useStore } from "../store";
import CardImage from "./CardImage";

// Kartentabelle (Stueck 6, Spec §8 Folgestueck): steht unter den Kartenvorschlaegen desselben Decks und
// folgt demselben Muster wie Suggestions.tsx - Laden erst auf Klick (kein Nachladen beim Oeffnen), ein
// "neu laden"-Knopf, und ein error der Bridge raeumt die offene Anfrage im Store weg (sonst haengt der
// Knopf fuer immer auf "lädt …", siehe store.ts case "error" und requestDeckCards).
//
// WICHTIG (gilt fuer jede Zahl hier): sie bezieht sich auf msg.withCardData, NICHT auf msg.games - siehe
// den Kommentar am Kopf von deckCards.ts. games steht nur in der Kopfzeile, um den Unterschied selbst
// sichtbar zu machen (9 von 12 - nicht 9 von 9).

/** Unter dieser Zahl an Partien MIT Kartendaten gilt eine Zeile als statistisch zu duenn (Bridge:
 *  CardStats.MIN_GAMES) - der Wert steht hier noch einmal, weil enough allein den Schwellenwert selbst
 *  nicht mitliefert und der Hinweistext ihn nennen soll. */
const MIN_GAMES = 5;

const SORT_MODES: SortMode[] = ["action", "name", "cast"];
const SORT_LABEL: Record<SortMode, string> = { action: "Handlungsbedarf", name: "Name", cast: "am häufigsten gewirkt" };

export default function DeckCards({ deck }: { deck: string }) {
  const msg = useStore((s) => s.cardStats[deck]);
  // Dasselbe Muster wie bei den Kartenvorschlaegen (store.ts case "error"): pendingCards liegt im Store,
  // keyed by Deckname, damit ein Fehler der Bridge den Knopf wieder freigibt.
  const pending = useStore((s) => s.pendingCards.includes(deck));
  const requestDeckCards = useStore((s) => s.requestDeckCards);
  const [sort, setSort] = useState<SortMode>("action");

  const click = () => requestDeckCards(deck);

  return (
    <section className="sb-block deck-cards">
      <div className="sb-block-head">
        <h2>Karten</h2>
      </div>
      {msg ? (
        <>
          <p className="dc-source">
            {msg.withCardData} von {msg.games} gewerteten Partien mit Aufzeichnung
            <button className="quiet small suggest-reload" disabled={pending} onClick={click}>
              {pending ? "lädt …" : "neu laden"}
            </button>
          </p>
          {!msg.enough ? (
            <p className="muted">
              {msg.withCardData === 0
                // Der Normalfall beim ersten Oeffnen (die Aufzeichnung beginnt erst mit der naechsten
                // Partie) - bewusst KEIN "0 von 5 nötig", das laese sich wie ein Fehler.
                ? "Noch keine Partie mit Aufzeichnung – sie beginnt mit der nächsten Partie, ältere Partien zählen nicht mit."
                : `Noch zu wenige Partien mit Aufzeichnung (${msg.withCardData} von ${MIN_GAMES} nötig).`}
            </p>
          ) : msg.cards.length === 0 ? (
            <p className="muted">Keine Nicht-Land-Karte in den aufgezeichneten Partien gesehen.</p>
          ) : (
            <>
              <div className="sb-chips dc-sort" role="group" aria-label="Sortierung">
                {SORT_MODES.map((m) => (
                  <button key={m} className={"sb-chip" + (m === sort ? " on" : "")} aria-pressed={m === sort}
                    onClick={() => setSort(m)}>
                    {SORT_LABEL[m]}
                  </button>
                ))}
              </div>
              <div className="dc-rows">
                {sortCards(msg.cards, sort).map((c) => <CardRow key={c.name} card={c} withCardData={msg.withCardData} />)}
              </div>
            </>
          )}
        </>
      ) : (
        <button className="primary" disabled={pending} onClick={click}>{pending ? "lädt …" : "Karten laden"}</button>
      )}
    </section>
  );
}

/** Eine Zeile der Tabelle: Miniatur, Name mit Manakosten, darunter der Satz aus cardLine. `share`/`cut`
 *  wie bei den Kartenvorschlaegen gibt es hier nicht - das ist eine reine Auszaehlung, kein Vorschlag. */
function CardRow({ card, withCardData }: { card: CardStat; withCardData: number }) {
  return (
    <div className="dc-row">
      <div className="dc-thumb">
        {card.imageKey
          ? <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" />
          : <span className="pile-name">{card.name}</span>}
      </div>
      <div className="dc-body">
        <span className="dc-name">
          {card.name}
          {card.manaCost && <span className="cost">{card.manaCost}</span>}
        </span>
        <span className="dc-line">{cardLine(card, withCardData)}</span>
      </div>
    </div>
  );
}
