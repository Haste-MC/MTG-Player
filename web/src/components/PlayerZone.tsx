import type { PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import { useStore } from "../store";
import { send } from "../ws";

export default function PlayerZone({ p, state, compact }: { p: PlayerSnap; state: Snapshot; compact: boolean }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  const bf = cards(p.battlefield);
  const active = state.activePlayer === p.id;
  const mana = Object.entries(p.manaPool).filter(([, v]) => v > 0).map(([k, v]) => `${k}${v}`).join(" ");
  const cmdDmg = Object.entries(p.commanderDamage).map(([id, v]) => `${state.cards[id]?.name ?? id}: ${v}`).join(", ");
  return (
    <div className={"player" + (active ? " active" : "") + (compact ? " compact" : "")}>
      <div className={"header" + (p.highlighted ? " highlighted" : "")} onClick={() => send({ type: "selectPlayer", id: p.id, seq })}>
        <span className="pname">{p.name}{p.isAi && <span className="ai-tag">KI</span>}</span>
        <span className="life">{p.life}</span>
        <span className="badges">
          <span className="badge" title="Hand">Hand {p.hand.length}</span>
          <span className="badge" title="Bibliothek">Bib {p.librarySize}</span>
          <span className="badge" title="Friedhof">Grab {p.graveyard.length}</span>
          {p.exile.length > 0 && <span className="badge" title="Exil">Exil {p.exile.length}</span>}
          {!!p.counters?.POISON && <span className="badge cmd" title="Gift">Gift {p.counters.POISON}</span>}
          {mana && <span className="badge mana" title="Mana">Mana {mana}</span>}
          {cmdDmg && <span className="badge cmd" title="Commander-Schaden">CMD {cmdDmg}</span>}
          {p.hasPriority && <span className="badge prio" title="Hat Priorität"><span className="dot" /> Prio</span>}
        </span>
      </div>
      <div className="zone battlefield">
        {cards(p.command).map((c) => <CardBox key={c.id} card={c} />)}
        {bf.filter((c) => !c.typeLine?.includes("Land")).map((c) => <CardBox key={c.id} card={c} />)}
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
