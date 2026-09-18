import type { CSSProperties } from "react";
import type { CardSnap, PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import CardImage from "./CardImage";
import { useStore } from "../store";
import { send } from "../ws";

const isLand = (c: CardSnap) => !!c.typeLine?.includes("Land");

/** Wo der Commander steckt, wenn er nicht in der Kommandozone liegt (Forge liefert ZoneType-Namen, Fixtures Kleinschreibung). */
const ZONE_TEXT: Record<string, string> = {
  battlefield: "im Spiel", stack: "auf dem Stack", graveyard: "im Friedhof", exile: "im Exil",
  hand: "auf der Hand", library: "in der Bibliothek",
};

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

export default function PlayerZone({ p, state, compact, spectator }: { p: PlayerSnap; state: Snapshot; compact: boolean; spectator?: boolean }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  const bf = cards(p.battlefield);
  const others = bf.filter((c) => !isLand(c));
  const lands = bf.filter(isLand);
  const command = cards(p.command);
  const graveyard = cards(p.graveyard);
  const exile = cards(p.exile);
  const hand = cards(p.hand);
  const active = state.activePlayer === p.id;
  const mana = Object.entries(p.manaPool).filter(([, v]) => v > 0).map(([k, v]) => `${k}${v}`).join(" ");
  const cmdEntries = Object.entries(p.commanderDamage);
  const cmdTitle = cmdEntries.map(([id, v]) => `${state.cards[id]?.name ?? id}: ${v}`).join(", ");
  // Kompakt (Gegner) nur die Zahlen, der Name steckt im Tooltip; in der eigenen Zone ist Platz für den Namen.
  const cmdDmg = compact ? cmdEntries.map(([, v]) => v).join(" / ") : cmdTitle;
  // Leere Kommandozone: sagen, wo der eigene Commander gerade ist (gecastet = "im Spiel"), sonst "leer".
  const awayCommander = command.length === 0
    ? Object.values(state.cards).find((c) => c.commander && c.owner === p.id && c.zone)
    : undefined;
  const placeholder = awayCommander ? ZONE_TEXT[awayCommander.zone!.toLowerCase()] ?? "leer" : "leer";
  // Kompakte Panels: Friedhof/Exil nur, wenn etwas drin liegt – leere Platzhalter sind nur Rauschen
  // (die Zahlen stehen ohnehin im Kopf). In der eigenen Zone bleiben beide Stapel als Ziel sichtbar.
  const showGrave = !compact || graveyard.length > 0;
  const showExile = !compact || exile.length > 0;
  // Kompakte Panels: Laender-Ueberlappung haengt (per CSS) an der Landzahl; im Zuschauer-Panel ausserdem die
  // Kartengroesse an der Zahl der Nicht-Land-Permanents und die Hand-Ueberlappung an der Handgroesse.
  const sizing = compact
    ? ({ "--n-lands": lands.length, "--n-bf": Math.max(1, others.length), "--n": hand.length } as CSSProperties)
    : undefined;
  return (
    <div className={"player" + (active ? " active" : "") + (compact ? " compact" : " own") + (spectator ? " spectator" : "")} style={sizing}>
      <div className={"header" + (p.highlighted ? " highlighted" : "") + (p.targetable ? " targetable" : "")} onClick={() => send({ type: "selectPlayer", id: p.id, seq })}>
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
        <div className="command" title="Kommandozone">
          <div className="zone-label">Kommando&shy;zone</div>
          <div className="row">
            {command.map((c) => <CardBox key={c.id} card={c} />)}
            {command.length === 0 && <div className="cmd-placeholder"><span>{placeholder}</span></div>}
          </div>
        </div>
        <div className="rows">
          <div className="row bf-main">
            {others.map((c) => <CardBox key={c.id} card={c} />)}
          </div>
          <div className="row bf-lands">
            {lands.map((c) => <CardBox key={c.id} card={c} />)}
          </div>
        </div>
        {(showGrave || showExile) && (
          <div className="piles">
            {showGrave && <Pile label="Grab" cards={graveyard} />}
            {showExile && <Pile label="Exil" cards={exile} />}
          </div>
        )}
        {spectator && (
          <div className="spectator-hand">
            <div className="zone-label">Hand</div>
            <div className="row">{hand.map((c) => <CardBox key={c.id} card={c} />)}</div>
          </div>
        )}
      </div>
    </div>
  );
}
