import { useState } from "react";
import type { CSSProperties, MouseEvent } from "react";
import type { CardSnap } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

/** Stapel-Angaben, wenn diese Karte fuer mehrere gleichnamige steht (siehe groups.ts). */
export interface StackInfo { count: number; tapped: number }

/**
 * Aufbau: .card-slot (Platz in der Reihe) > .card-frame (sichtbare Box, bei getappt gedreht bemessen)
 * > .card (Bild/Text, wird bei getappt rotiert). P/T, Marken und Kampf-Tags liegen im Frame, nicht in
 * der Karte, damit sie bei getappten Karten aufrecht lesbar bleiben. Ecken: Kampf-Tag/Stapelzaehler/
 * EMBLEM oben links, CMD-Marke oben rechts, Marken/getappt-Hinweis unten links, P/T unten rechts
 * (links, weil in ueberlappenden Reihen nur die linke Kante jeder Karte frei bleibt).
 * Getappt: gedreht ueberall in .bf-rows (CSS) - eigene Zone und Zuschauer-Panels; nur die kompakte
 * Gegnerzeile der Tischansicht (kein .bf-rows) bleibt aufrecht, abgedunkelt + ⟳-Marke.
 */
export default function CardBox({ card, stack, attached }: { card: CardSnap; stack?: StackInfo; attached?: CardSnap[] }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  // failedKey statt eines simplen bool: eine fehlgeschlagene Karte in diesem Slot darf nicht fuer
  // IMMER auf Text-Fallback haengen bleiben, wenn hier spaeter eine andere Karte (anderer
  // imageKey) angezeigt wird - z. B. wenn React den Card-Slot fuer eine neue Karte wiederverwendet.
  const [failedKey, setFailedKey] = useState<string>();
  const noImg = !card.imageKey || failedKey === card.imageKey;
  if (card.faceDown) return <div className="card-slot"><div className="card-frame"><div className="card back" /></div></div>;
  const tokenFrame = noImg && !!card.token;
  const stacked = !!stack && stack.count > 1;
  // Tabletop-Stapel (groups.ts): bis zu 4 Ebenen hinter der obersten Karte, getappte Kopien liegen
  // als gedrehte Ebenen zuunterst. Die gezeigte Karte dreht sich nur, wenn ALLE Kopien getappt sind;
  // in der kompakten Gegnerzeile (ohne Drehung) bleibt der Stapel aufrecht und traegt "N getappt".
  const layers = stacked ? Math.min(stack.count - 1, 4) : 0;
  const tappedLayers = stacked ? Math.min(stack.tapped, layers) : 0;
  const allTapped = stacked && stack.tapped === stack.count;
  const rotated = card.tapped || allTapped;
  const stackTappedSlot = stacked && stack.tapped > 0 && !allTapped;
  // Anhaenge (Auren/Equipment, groups.ts attachedBy): bis zu 4 Ebenen hinter dem Wirt, jede um
  // --attach-dy weiter nach oben versetzt (kompakte Gegnerzeile: seitlich, siehe CSS). Jede Ebene ist
  // eine eigene Karte: hover-/klickbar, mit eigenem waehlbar/spielbar-Rahmen.
  const att = attached ?? [];
  const attLayers = att.slice(0, 4);
  const attMore = att.length - attLayers.length;
  const cls = ["card", rotated ? "tapped" : "", card.selectable ? "selectable" : "",
    card.actionable ? "actionable" : "", card.attacking ? "attacking" : "", card.blocking ? "blocking" : "",
    card.token ? "token" : "", tokenFrame ? "token-frame" : "", !noImg ? "has-img" : ""]
    .filter(Boolean).join(" ");
  const click = (e: MouseEvent) => {
    e.preventDefault();
    send({ type: "selectCard", id: card.id, alt: e.button === 2, seq });
  };
  return (
    <div className={"card-slot" + (rotated ? " tapped-slot" : "") + (stackTappedSlot ? " stack-tapped-slot" : "") + (noImg ? " text-slot" : "") + (att.length > 0 ? " has-attach" : "")}
      style={{ ...(layers > 0 ? { "--layers": layers } : {}), ...(attLayers.length > 0 ? { "--attach-n": attLayers.length } : {}) } as CSSProperties}>
      <div className={"card-frame" + (rotated ? " tapped" : "") + (stacked ? " stacked" : "") + (att.length > 0 ? " attached" : "")}>
        {attLayers.length > 0 && (
          <div className="attach-layers">
            {attLayers.map((a, i) => (
              // attLayers[0] liegt direkt hinter dem Wirt und schaut am wenigsten heraus: Versatz --k
              // steigt mit i, z-index faellt, damit der erste Anhang ueber den weiteren liegt.
              <div key={a.id}
                className={"attach-layer" + (a.selectable ? " selectable" : "") + (a.actionable ? " actionable" : "") + (a.highlighted ? " highlighted" : "")}
                style={{ "--k": i + 1, zIndex: attLayers.length - i } as CSSProperties}
                title={a.text ?? ""}
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: a.id, alt: e.button === 2, seq }); }}
                onContextMenu={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: a.id, alt: true, seq }); }}
                onMouseEnter={() => setHover(a.id)} onMouseLeave={() => setHover(undefined)}>
                {a.imageKey && <CardImage key={a.imageKey} imageKey={a.imageKey} className="art" />}
                <span className="attach-name">{a.name}</span>
                {i === attLayers.length - 1 && attMore > 0 && <span className="tag attach-more">+{attMore}</span>}
              </div>
            ))}
          </div>
        )}
        {layers > 0 && (
          <div className="stack-layers" aria-hidden>
            {Array.from({ length: layers }, (_, i) => (
              <div key={i} className={"stack-layer" + (i < tappedLayers ? " tapped" : "")}
                style={{ "--i": layers - i } as CSSProperties}>
                {!noImg && <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" />}
              </div>
            ))}
          </div>
        )}
        <div className={cls} title={card.text ?? ""} onClick={click} onContextMenu={click}
          onMouseEnter={() => setHover(card.id)} onMouseLeave={() => setHover(undefined)}>
          <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" onFail={() => setFailedKey(card.imageKey)} />
          {tokenFrame && (
            <div className="card-text token-text">
              <div className="name">{card.name}</div>
              {card.power !== undefined && <div className="big-pt">{card.power}/{card.toughness}</div>}
              <div className="type">{card.typeLine ?? ""}</div>
              {card.text && <div className="rules">{card.text}</div>}
            </div>
          )}
          {noImg && !tokenFrame && (
            <div className="card-text">
              <div className="name">{card.name}</div>
              {card.manaCost && <div className="meta">{card.manaCost}</div>}
              <div className="type">{card.typeLine ?? ""}</div>
              {card.text && <div className="rules">{card.text}</div>}
            </div>
          )}
        </div>
        {(card.attacking || card.blocking) && (
          <div className={"tag combat-tag " + (card.attacking ? "atk" : "blk")}>{card.attacking ? "Angriff" : "Block"}</div>
        )}
        {card.emblem && <div className="tag emblem-tag" title="Emblem">Emblem</div>}
        {stacked && <div className="tag stack-count" title={`${stack.count} × ${card.name}`}>×{stack.count}</div>}
        {card.commander && <div className="tag commander-tag" title="Commander">CMD</div>}
        {card.power !== undefined && (
          <div className={"tag pt" + (card.damage ? " hurt" : "")}>{card.power}/{card.toughness}{card.damage ? ` −${card.damage}` : ""}</div>
        )}
        {card.counters && <div className="tag counters">{Object.entries(card.counters).map(([k, v]) => `${k}×${v}`).join(" ")}</div>}
        {stacked && stack.tapped > 0 && <div className="tag stack-tapped">{stack.tapped}<span className="word"> getappt</span></div>}
        {card.tapped && !stacked && <div className="tag tapped-tag" title="getappt" aria-label="getappt" />}
      </div>
    </div>
  );
}
