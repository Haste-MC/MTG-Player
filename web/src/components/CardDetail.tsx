import { useStore } from "../store";
import CardImage from "./CardImage";

export default function CardDetail() {
  const card = useStore((s) => (s.hover !== undefined ? s.state?.cards[String(s.hover)] : undefined));
  if (!card || card.faceDown) return <div className="detail empty"><span>Karte anfahren für Details</span></div>;
  return (
    <div className="detail">
      {card.imageKey && <div className="art-frame"><CardImage key={card.imageKey} imageKey={card.imageKey} className="art-large" /></div>}
      <div className="detail-body">
        <div className="name">{card.name} <span className="cost">{card.manaCost ?? ""}</span></div>
        <div className="type">{card.typeLine ?? ""}{card.power !== undefined && <span className="pt"> · {card.power}/{card.toughness}</span>}</div>
        {card.text && <div className="text">{card.text}</div>}
        {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k} ×${v}`).join(", ")}</div>}
      </div>
    </div>
  );
}
