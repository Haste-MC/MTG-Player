import { useStore } from "../store";
import CardImage from "./CardImage";

export default function CardDetail() {
  const card = useStore((s) => (s.hover !== undefined ? s.state?.cards[String(s.hover)] : undefined));
  if (!card || card.faceDown) return <div className="detail empty">Karte anfahren für Details</div>;
  return (
    <div className="detail">
      <CardImage imageKey={card.imageKey} className="art-large" />
      <div className="name">{card.name} <span className="cost">{card.manaCost ?? ""}</span></div>
      <div className="type">{card.typeLine ?? ""}</div>
      {card.power !== undefined && <div className="pt">{card.power}/{card.toughness}</div>}
      <div className="text">{card.text ?? ""}</div>
      {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k} ×${v}`).join(", ")}</div>}
    </div>
  );
}
