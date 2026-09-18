import type { CSSProperties } from "react";
import type { Snapshot } from "../protocol";
import CardBox from "./CardBox";

/** Überlappung im Fächer: 35 % bis acht Karten, danach enger, damit auch große Hände in den Viewport passen. */
function overlap(n: number): number {
  return Math.min(0.72, 0.35 + Math.max(0, n - 8) * 0.045);
}

export default function Hand({ state }: { state: Snapshot }) {
  const me = state.players.find((p) => p.id === state.me);
  if (!me) return null;
  const cards = me.hand.map((id) => state.cards[String(id)]).filter(Boolean);
  const n = cards.length;
  return (
    <div className="hand" style={{ "--n": n, "--overlap": overlap(n) } as CSSProperties}>
      {cards.map((c, i) => (
        <div key={c.id} className="fan-slot" style={{ "--i": i } as CSSProperties}>
          <CardBox card={c} />
        </div>
      ))}
    </div>
  );
}
