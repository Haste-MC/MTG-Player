import { useState } from "react";
import type { MouseEvent } from "react";
import type { CardSnap } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

export default function CardBox({ card }: { card: CardSnap }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  const [noImg, setNoImg] = useState(!card.imageKey);
  if (card.faceDown) return <div className="card-slot"><div className="card back" /></div>;
  const cls = ["card", card.tapped ? "tapped" : "", card.selectable ? "selectable" : "",
    card.actionable ? "actionable" : "", card.attacking ? "attacking" : "", card.blocking ? "blocking" : "",
    !noImg ? "has-img" : ""]
    .filter(Boolean).join(" ");
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={"card-slot" + (card.tapped ? " tapped-slot" : "") + (noImg && !card.tapped ? " text-slot" : "")}>
      <div className={cls} title={card.text ?? ""} onClick={click} onContextMenu={click}
        onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
        <CardImage imageKey={card.imageKey} className="art" onFail={() => setNoImg(true)} />
        {noImg && (
          <div className="card-text">
            <div className="name">{card.name}</div>
            <div className="meta">{card.manaCost ?? ""}</div>
            <div className="type">{card.typeLine ?? ""}</div>
            {card.text && <div className="rules">{card.text}</div>}
          </div>
        )}
        {card.power !== undefined && (
          <div className="pt">{card.power}/{card.toughness}{card.damage ? ` (${card.damage} dmg)` : ""}</div>
        )}
        {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k}×${v}`).join(" ")}</div>}
      </div>
    </div>
  );
}
