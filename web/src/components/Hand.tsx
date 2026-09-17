import type { Snapshot } from "../protocol";
import CardBox from "./CardBox";

export default function Hand({ state }: { state: Snapshot }) {
  const me = state.players.find((p) => p.id === state.me);
  if (!me) return null;
  return (
    <div className="hand">
      {me.hand.map((id) => state.cards[String(id)]).filter(Boolean).map((c) => <CardBox key={c.id} card={c} />)}
    </div>
  );
}
