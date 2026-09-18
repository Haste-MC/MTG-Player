import { useState } from "react";
import type { MouseEvent } from "react";
import type { CardSnap } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

/**
 * Aufbau: .card-slot (Platz in der Reihe) > .card-frame (sichtbare Box, bei getappt gedreht bemessen)
 * > .card (Bild/Text, wird bei getappt rotiert). P/T, Marken und Kampf-Tags liegen im Frame, nicht in
 * der Karte, damit sie bei getappten Karten aufrecht lesbar bleiben. Ecken: Kampf-Tag oben links,
 * CMD-Marke oben rechts, Marken unten links, P/T unten rechts.
 */
export default function CardBox({ card }: { card: CardSnap }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  // failedKey statt eines simplen bool: eine fehlgeschlagene Karte in diesem Slot darf nicht fuer
  // IMMER auf Text-Fallback haengen bleiben, wenn hier spaeter eine andere Karte (anderer
  // imageKey) angezeigt wird - z. B. wenn React den Card-Slot fuer eine neue Karte wiederverwendet.
  const [failedKey, setFailedKey] = useState<string>();
  const noImg = !card.imageKey || failedKey === card.imageKey;
  if (card.faceDown) return <div className="card-slot"><div className="card-frame"><div className="card back" /></div></div>;
  const cls = ["card", card.tapped ? "tapped" : "", card.selectable ? "selectable" : "",
    card.actionable ? "actionable" : "", card.attacking ? "attacking" : "", card.blocking ? "blocking" : "",
    card.token ? "token" : "", !noImg ? "has-img" : ""]
    .filter(Boolean).join(" ");
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={"card-slot" + (card.tapped ? " tapped-slot" : "") + (noImg ? " text-slot" : "")}>
      <div className={"card-frame" + (card.tapped ? " tapped" : "")}>
        <div className={cls} title={card.text ?? ""} onClick={click} onContextMenu={click}
          onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
          <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" onFail={() => setFailedKey(card.imageKey)} />
          {noImg && (
            <div className="card-text">
              <div className="name">{card.name}</div>
              {card.manaCost && <div className="meta">{card.manaCost}</div>}
              <div className="type">{card.typeLine ?? ""}</div>
              {card.text && <div className="rules">{card.text}</div>}
            </div>
          )}
        </div>
        {(card.attacking || card.blocking) && (
          <div className={"tag combat-tag " + (card.attacking ? "atk" : "blk")}>{card.attacking ? "Angriff" : "Block"}</div>
        )}
        {card.commander && <div className="tag commander-tag" title="Commander">CMD</div>}
        {card.power !== undefined && (
          <div className={"tag pt" + (card.damage ? " hurt" : "")}>{card.power}/{card.toughness}{card.damage ? ` −${card.damage}` : ""}</div>
        )}
        {card.counters && <div className="tag counters">{Object.entries(card.counters).map(([k, v]) => `${k}×${v}`).join(" ")}</div>}
      </div>
    </div>
  );
}
