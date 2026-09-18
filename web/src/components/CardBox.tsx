import type { MouseEvent } from "react";
import type { CardSnap } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

export default function CardBox({ card }: { card: CardSnap }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  if (card.faceDown) return <div className="card back" />;
  const cls = ["card", card.tapped ? "tapped" : "", card.selectable ? "selectable" : "",
    card.actionable ? "actionable" : "", card.attacking ? "attacking" : "", card.blocking ? "blocking" : ""]
    .filter(Boolean).join(" ");
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={cls} title={card.text ?? ""} onClick={click} onContextMenu={click}
      onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
      <div className="name">{card.name}</div>
      <div className="meta">{card.manaCost ?? ""}</div>
      <div className="type">{card.typeLine ?? ""}</div>
      {card.power !== undefined && (
        <div className="pt">{card.power}/{card.toughness}{card.damage ? ` (${card.damage} dmg)` : ""}</div>
      )}
      {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k}×${v}`).join(" ")}</div>}
    </div>
  );
}
