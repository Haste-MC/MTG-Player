import { useStore } from "../store";
import CardImage from "./CardImage";

export default function CardDetail() {
  const card = useStore((s) => (s.hover !== undefined ? s.state?.cards[String(s.hover)] : undefined));
  // Verdeckte Karte, die ich ansehen darf: Name, Typ und Text kommen aus dem Zusatz, die Zahlen am
  // Brett (Schaden, Marken) bleiben die des Brettzustands - eine verdeckte Kreatur ist eine 2/2.
  const unter = card?.faceDown ? card.verdeckt : undefined;
  if (!card || (card.faceDown && !unter)) return <div className="detail empty"><span>Karte anfahren für Details</span></div>;
  const bild = unter?.imageKey ?? card.imageKey;
  return (
    <div className="detail">
      {bild && <div className="art-frame"><CardImage key={bild} imageKey={bild} className="art-large" /></div>}
      <div className="detail-body">
        <div className="name">{unter?.name ?? card.name} <span className="cost">{(unter?.manaCost ?? card.manaCost) ?? ""}</span>
          {unter && <span className="verdeckt-marke" title="Liegt verdeckt - nur du siehst das">verdeckt</span>}</div>
        <div className="type">{(unter?.typeLine ?? card.typeLine) ?? ""}{card.power !== undefined && <span className="pt"> · {card.power}/{card.toughness}</span>}</div>
        {(unter?.text ?? card.text) && <div className="text">{unter?.text ?? card.text}</div>}
        {card.counters && <div className="counters">{Object.entries(card.counters).map(([k, v]) => `${k} ×${v}`).join(", ")}</div>}
      </div>
    </div>
  );
}
