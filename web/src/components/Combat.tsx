import type { MouseEvent } from "react";
import type { CardSnap, Snapshot } from "../protocol";
import { combatGroups } from "../combat";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

/** Eine Karte in der Kampfliste: Miniatur, Name, P/T. Hover zeigt sie im Detail-Panel, Klick waehlt sie
 *  aus - dieselbe Nachricht wie ein Klick auf dem Brett, damit man waehrend der Blockzuteilung auch aus
 *  der Liste heraus zuteilen kann. */
function CombatCard({ card, kind }: { card: CardSnap; kind: "atk" | "blk" }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={"combat-card " + kind} title={card.text ?? ""} onClick={click} onContextMenu={click}
      onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
      <div className="stack-thumb">
        {card.imageKey ? <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" />
          : <span className="pile-name">{card.name}</span>}
      </div>
      <div className="combat-body">
        <div className="combat-name">{card.name}</div>
        {card.power !== undefined && <div className="muted">{card.power}/{card.toughness}</div>}
      </div>
    </div>
  );
}

/** Panel "Kampf" in der Seitenspalte: wer greift wen an, und wer blockt. Nur sichtbar, solange Angreifer
 *  im Snapshot stehen; Forge raeumt die CombatView am Kampfende ab, dann verschwindet das Panel. */
export default function Combat({ state }: { state: Snapshot }) {
  const groups = combatGroups(state);
  if (groups.length === 0) return null;
  const attackers = groups.reduce((n, g) => n + g.attackers.length, 0);
  return (
    <div className="combat-panel">
      <div className="panel-title">Kampf<span className="count">{attackers}</span></div>
      {groups.map((g) => (
        <div key={g.key} className="combat-group">
          <div className="combat-target">
            {g.key === "none" ? g.label : <>Angriff auf <b>{g.label}</b></>}
            {g.sub && <span className="muted"> · {g.sub}</span>}
          </div>
          {g.attackers.map((a) => (
            <div key={a.card.id} className="combat-row">
              <CombatCard card={a.card} kind="atk" />
              {a.blockers.length === 0
                ? <div className="combat-unblocked">ungeblockt</div>
                : <div className="combat-blockers">
                    {a.blockers.map((b) => <CombatCard key={b.id} card={b} kind="blk" />)}
                  </div>}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
