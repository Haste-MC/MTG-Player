import type { PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import { send } from "../ws";

export default function PlayerZone({ p, state, compact }: { p: PlayerSnap; state: Snapshot; compact: boolean }) {
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  const bf = cards(p.battlefield);
  const active = state.activePlayer === p.id;
  const mana = Object.entries(p.manaPool).filter(([, v]) => v > 0).map(([k, v]) => `${k}${v}`).join(" ");
  const cmdDmg = Object.entries(p.commanderDamage).map(([id, v]) => `${state.cards[id]?.name ?? id}: ${v}`).join(", ");
  return (
    <div className={"player" + (active ? " active" : "") + (compact ? " compact" : "")}>
      <div className="header" onClick={() => send({ type: "selectPlayer", id: p.id })}>
        <b>{p.name}</b> {p.isAi ? "(KI)" : ""} · Leben {p.life}
        {p.counters?.POISON ? ` · Gift ${p.counters.POISON}` : ""}
        {" · Hand "}{p.hand.length}{" · Bib "}{p.librarySize}{" · Grab "}{p.graveyard.length}{" · Exil "}{p.exile.length}
        {mana ? ` · Mana ${mana}` : ""}
        {cmdDmg ? ` · CMD-Schaden ${cmdDmg}` : ""}
        {p.hasPriority ? " · ⏵ Prio" : ""}
      </div>
      <div className="zone command">{cards(p.command).map((c) => <CardBox key={c.id} card={c} />)}</div>
      <div className="zone battlefield">
        {bf.filter((c) => !c.typeLine?.includes("Land")).map((c) => <CardBox key={c.id} card={c} />)}
      </div>
      <div className="zone lands">
        {bf.filter((c) => c.typeLine?.includes("Land")).map((c) => <CardBox key={c.id} card={c} />)}
      </div>
      {!compact && (
        <details><summary>Friedhof ({p.graveyard.length}) / Exil ({p.exile.length})</summary>
          <div className="zone">{cards(p.graveyard).map((c) => <CardBox key={c.id} card={c} />)}</div>
          <div className="zone">{cards(p.exile).map((c) => <CardBox key={c.id} card={c} />)}</div>
        </details>
      )}
    </div>
  );
}
