import type { CardSnap, PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import CardImage from "./CardImage";
import { useStore } from "../store";
import { send } from "../ws";

const isLand = (c: CardSnap) => !!c.typeLine?.includes("Land");

/** Friedhof/Exil als kleiner Stapel: Bild der obersten Karte + Zähler; Klick klappt die Liste auf. */
function Pile({ label, cards }: { label: string; cards: CardSnap[] }) {
  const top = cards[cards.length - 1];
  return (
    <details className="pile">
      <summary className={"pile-thumb" + (top ? "" : " empty")} title={`${label} (${cards.length})`}>
        {top && <CardImage key={top.imageKey} imageKey={top.imageKey} className="art" />}
        {top && !top.imageKey && <span className="pile-name">{top.name}</span>}
        <span className="pile-count">{cards.length}</span>
        <span className="pile-label">{label}</span>
      </summary>
      <div className="pile-list">
        <div className="pile-list-title">{label} · {cards.length}</div>
        {cards.length === 0 && <div className="muted">leer</div>}
        <div className="zone">{cards.map((c) => <CardBox key={c.id} card={c} />)}</div>
      </div>
    </details>
  );
}

export default function PlayerZone({ p, state, compact }: { p: PlayerSnap; state: Snapshot; compact: boolean }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  const bf = cards(p.battlefield);
  const others = bf.filter((c) => !isLand(c));
  const lands = bf.filter(isLand);
  const command = cards(p.command);
  const active = state.activePlayer === p.id;
  const mana = Object.entries(p.manaPool).filter(([, v]) => v > 0).map(([k, v]) => `${k}${v}`).join(" ");
  const cmdEntries = Object.entries(p.commanderDamage);
  const cmdTitle = cmdEntries.map(([id, v]) => `${state.cards[id]?.name ?? id}: ${v}`).join(", ");
  // Kompakt (Gegner) nur die Zahlen, der Name steckt im Tooltip; in der eigenen Zone ist Platz für den Namen.
  const cmdDmg = compact ? cmdEntries.map(([, v]) => v).join(" / ") : cmdTitle;
  return (
    <div className={"player" + (active ? " active" : "") + (compact ? " compact" : " own")}>
      <div className={"header" + (p.highlighted ? " highlighted" : "")} onClick={() => send({ type: "selectPlayer", id: p.id, seq })}>
        <span className="pname">{p.name}{p.isAi && <span className="ai-tag">KI</span>}</span>
        <span className="life" title="Lebenspunkte">{p.life}</span>
        {p.hasPriority && <span className="prio" title="Hat Priorität"><span className="dot" />Prio</span>}
        <span className="badges">
          <span className="badge" title="Hand">Hand <b>{p.hand.length}</b></span>
          <span className="badge" title="Bibliothek">Bib <b>{p.librarySize}</b></span>
          <span className="badge" title="Friedhof">Grab <b>{p.graveyard.length}</b></span>
          {p.exile.length > 0 && <span className="badge" title="Exil">Exil <b>{p.exile.length}</b></span>}
          {!!p.counters?.POISON && <span className="badge cmd" title="Gift">Gift <b>{p.counters.POISON}</b></span>}
          {mana && <span className="badge mana" title="Mana im Pool">Mana <b>{mana}</b></span>}
          {cmdDmg && <span className="badge cmd" title={"Commander-Schaden: " + cmdTitle}>CMD <b>{cmdDmg}</b></span>}
        </span>
      </div>
      <div className="zone battlefield">
        {!compact && (
          <div className="command">
            <div className="zone-label">Kommandozone</div>
            <div className="row">{command.map((c) => <CardBox key={c.id} card={c} />)}</div>
          </div>
        )}
        <div className="rows">
          <div className="row bf-main">
            {compact && command.map((c) => <CardBox key={c.id} card={c} />)}
            {others.map((c) => <CardBox key={c.id} card={c} />)}
          </div>
          <div className="row bf-lands">
            {lands.map((c) => <CardBox key={c.id} card={c} />)}
          </div>
        </div>
        <div className="piles">
          <Pile label="Grab" cards={cards(p.graveyard)} />
          <Pile label="Exil" cards={cards(p.exile)} />
        </div>
      </div>
    </div>
  );
}
