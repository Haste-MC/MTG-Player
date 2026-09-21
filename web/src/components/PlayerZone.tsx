import { useMemo, useRef, useState } from "react";
import type { CSSProperties, SyntheticEvent } from "react";
import type { CardSnap, PlayerSnap, Snapshot } from "../protocol";
import CardBox from "./CardBox";
import CardImage from "./CardImage";
import { attachedBy, groupCards } from "../groups";
import type { Group } from "../groups";
import { useStore } from "../store";
import { send } from "../ws";
import { slotHeadroom, slotUnits, useBoardSize } from "../boardSize";
import type { RowSpec } from "../boardSize";

const isLand = (c: CardSnap) => !!c.typeLine?.includes("Land");
const isCreature = (c: CardSnap) => !!c.typeLine?.includes("Creature");
// Drei Reihen: Kreaturen (auch Kreatur-Laender/Artefakt-Kreaturen), uebrige bleibende Karten, Laender unten.
function splitRows(bf: CardSnap[]) {
  const creatures = groupCards(bf.filter(isCreature));
  const other = groupCards(bf.filter((c) => !isCreature(c) && !isLand(c)));
  const lands = groupCards(bf.filter((c) => !isCreature(c) && isLand(c)));
  return { creatures, other, lands };
}

/** Wo der Commander steckt, wenn er nicht in der Kommandozone liegt (Forge liefert ZoneType-Namen, Fixtures Kleinschreibung). */
const ZONE_TEXT: Record<string, string> = {
  battlefield: "im Spiel", stack: "auf dem Stack", graveyard: "im Friedhof", exile: "im Exil",
  hand: "auf der Hand", library: "in der Bibliothek",
};

/** Friedhof/Exil als kleiner Stapel: Bild der obersten Karte + Zähler; Klick klappt die Liste auf. */
function Pile({ label, cards }: { label: string; cards: CardSnap[] }) {
  const top = cards[cards.length - 1];
  const [openRight, setOpenRight] = useState(false);
  // Oeffnungsseite beim Aufklappen: liegt die Vorschau in der linken Viewport-Haelfte (Zuschauer-Panels
  // der linken Spalte), oeffnet die Liste nach rechts - sonst wie bisher nach links.
  const onToggle = (e: SyntheticEvent<HTMLDetailsElement>) => {
    if (!e.currentTarget.open) return;
    const b = e.currentTarget.getBoundingClientRect();
    setOpenRight(b.left + b.width / 2 < window.innerWidth / 2);
  };
  return (
    <details className="pile" onToggle={onToggle}>
      <summary className={"pile-thumb" + (top ? "" : " empty")} title={`${label} (${cards.length})`}>
        {top && <CardImage key={top.imageKey} imageKey={top.imageKey} className="art" />}
        {top && !top.imageKey && <span className="pile-name">{top.name}</span>}
        <span className="pile-count">{cards.length}</span>
        <span className="pile-label">{label}</span>
      </summary>
      <div className={"pile-list" + (openRight ? " open-right" : "")}>
        <div className="pile-list-title">{label} · {cards.length}</div>
        {cards.length === 0 && <div className="muted">leer</div>}
        <div className="zone">{cards.map((c) => <CardBox key={c.id} card={c} />)}</div>
      </div>
    </details>
  );
}

/** Stapel aus groups.ts rendern: eine Karte je Gruppe, Zaehler nur bei mehr als einer Karte; Anhaenge
 *  (hosted, groups.ts attachedBy) liegen als Ebenen hinter dem Wirt.
 *  Key = erste Karte der Gruppe, damit ein Wechsel der gezeigten Karte (z. B. nach dem Tappen) nichts neu montiert. */
function Stacks({ groups, hosted }: { groups: Group[]; hosted: Map<number, CardSnap[]> }) {
  return <>{groups.map((g) => (
    <CardBox key={g.cards[0].id} card={g.card} stack={g.cards.length > 1 ? { count: g.cards.length, tapped: g.tapped } : undefined}
      attached={hosted.get(g.card.id)} />
  ))}</>;
}

/** Effekt- und Emblem-Chips (Forges Hilfskarten CardSnap.effect bzw. CardSnap.emblem): Name, Hover zeigt den Text
 *  im Detail-Panel, Klick wie eine Karte. Embleme als Chip statt Karte, weil eine zweite Karte in der Kommandozone
 *  in keiner Panelhoehe (720-1060) neben dem Commander Platz hat – sie tragen eine "Emblem"-Marke. */
function Effects({ effects, inline }: { effects: CardSnap[]; inline?: boolean }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  if (effects.length === 0) return null;
  return (
    <div className={"effects" + (inline ? " inline" : "")}>
      <span className="zone-label">Effekte</span>
      {effects.map((c) => (
        <button key={c.id} type="button" className={"effect-chip" + (c.emblem ? " emblem" : "")} title={c.text ?? ""}
          onClick={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: c.id, alt: e.button === 2, seq }); }}
          onMouseEnter={() => setHover(c.id)} onMouseLeave={() => setHover(undefined)}>
          {c.emblem && <span className="mark">Emblem</span>}
          {c.emblem ? c.name?.replace(/^Emblem\s*[—–-]\s*/, "") : c.name}
        </button>
      ))}
    </div>
  );
}

export default function PlayerZone({ p, state, compact, spectator }: { p: PlayerSnap; state: Snapshot; compact: boolean; spectator?: boolean }) {
  const seq = useStore((s) => s.state?.prompt.seq);
  const setHover = useStore((s) => s.setHover);
  const cards = (ids: number[]) => ids.map((id) => state.cards[String(id)]).filter(Boolean);
  // Effekt-Hilfskarten und Embleme (Forge legt sie in die Kommandozone) werden nicht als Karte gezeigt,
  // sondern als Chips unter dem Spielfeld.
  const isChip = (c: CardSnap) => !!c.effect || !!c.emblem;
  const effects = [...cards(p.command), ...cards(p.battlefield)].filter(isChip);
  // Anhaenge (Auren/Equipment) liegen als Ebenen hinter ihrem Wirt (auch wenn der einem anderen Spieler
  // gehoert) und Flueche auf Spielern als Chip im Panelkopf - beide fallen aus den eigenen Reihen heraus.
  const hosted = useMemo(() => attachedBy(state.cards), [state.cards]);
  const isHosted = (c: CardSnap) => (c.attachedTo !== undefined && hosted.has(c.attachedTo)) || c.attachedToPlayer !== undefined;
  const bf = cards(p.battlefield).filter((c) => !isChip(c) && !isHosted(c));
  const auras = Object.values(state.cards).filter((c) => c.attachedToPlayer === p.id && !c.faceDown && c.zone?.toLowerCase() === "battlefield");
  const { creatures, other, lands } = splitRows(bf);
  const command = cards(p.command).filter((c) => !isChip(c));
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
  // Kompakte Panels: Laender-Ueberlappung haengt (per CSS) an der Zahl der Land-Stapel; im Zuschauer-Panel ausserdem
  // die Kartengroesse an der Zahl der Nicht-Land-Stapel und die Hand-Ueberlappung an der Handgroesse.
  // Gegnerzeile der Tischansicht: Chips im Kopf hinter den Badges statt einer Zeile unter dem Spielfeld
  // (die Zeile kostet immer Hoehe, die bei 1280x720 der eigenen Zone fehlt; im Kopf nur, wenn er umbricht).
  // Eigene Zone und Zuschauer-Panels: Zeile "Effekte" unter dem Spielfeld.
  const inlineEffects = compact && !spectator;
  // Messung nur fuer die eigene Zone und Zuschauer-Panels; die kompakte Gegnerzeile behaelt ihre CSS-Formel.
  const measured = !compact || !!spectator;
  const rowsRef = useRef<HTMLDivElement>(null);
  // headroom: hoechster Anhang-Ueberstand der Reihe (Ebenen schauen oben heraus, siehe slotHeadroom).
  const headroom = (groups: Group[]) => Math.max(0, ...groups.map((g) => slotHeadroom(hosted.get(g.card.id)?.length ?? 0, !!g.card.tapped)));
  const rowSpecs: RowSpec[] = [
    { units: creatures.map(slotUnits), scale: 1, headroom: headroom(creatures) },
    { units: other.map(slotUnits), scale: 1, headroom: headroom(other) },
    { units: lands.map(slotUnits), scale: 0.8, headroom: headroom(lands) },
  ];
  // min 40 statt der fitCardWidth-Vorgabe 50: Tabletop-Stapel (Task 3) lassen gemischt getappte
  // Stapel jetzt exakt 1,4x breit rendern (vorher war die CSS-Breite schmaler als slotUnits() sie
  // schon seit Task 1 fuer die Messung ansetzt) - bei einem dicht besetzten 6-Spieler-Panel (viele
  // Stapel je Reihe, spectator-rows.json KI 4 bei 1600x900) reicht 50px als unterste Kartenbreite
  // nicht mehr aus, obwohl eine kleinere Breite (~45px) die Reihen tatsaechlich unterbringen wuerde;
  // der alte Boden liess fitCardWidth dann direkt aufgeben und 50px zurueckgeben, was die Reihe ueber
  // den Panelrand hinaus wachsen liess (Layout-Check Regel 3).
  const bw = useBoardSize(rowsRef, rowSpecs, measured, { min: 40 });
  const sizing = {
    ...(bw !== undefined ? { "--bw": `${bw}px` } : {}),
    ...(compact && !spectator ? { "--n-lands": lands.length } : {}),
    ...(spectator ? { "--n": hand.length } : {}),
  } as CSSProperties;
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
          {auras.map((c) => {
            const by = state.players.find((q) => q.id === c.controller)?.name;
            const auraTitle = [c.text, by ? `Aura von ${by}` : undefined].filter(Boolean).join("\n");
            return (
              <button key={c.id} type="button" className="effect-chip aura" title={auraTitle}
                onClick={(e) => { e.preventDefault(); e.stopPropagation(); send({ type: "selectCard", id: c.id, alt: e.button === 2, seq }); }}
                onMouseEnter={() => setHover(c.id)} onMouseLeave={() => setHover(undefined)}>
                <span className="mark">Aura</span>{c.name}
              </button>
            );
          })}
        </span>
        {inlineEffects && <Effects effects={effects} inline />}
      </div>
      <div className="zone battlefield">
        <div className="command" title="Kommandozone">
          <div className="zone-label">Kommando&shy;zone</div>
          <div className="row">
            {command.map((c) => <CardBox key={c.id} card={c} />)}
            {command.length === 0 && <div className="cmd-placeholder"><span>{placeholder}</span></div>}
          </div>
        </div>
        {measured ? (
          <div className="rows bf-rows" ref={rowsRef}>
            {creatures.length > 0 && <div className="row bf-creatures"><Stacks groups={creatures} hosted={hosted} /></div>}
            {other.length > 0 && <div className="row bf-other"><Stacks groups={other} hosted={hosted} /></div>}
            {lands.length > 0 && <div className="row bf-lands"><Stacks groups={lands} hosted={hosted} /></div>}
            {creatures.length + other.length + lands.length === 0 && <div className="bf-empty">keine bleibenden Karten</div>}
          </div>
        ) : (
          <div className="rows">
            <div className="row bf-main"><Stacks groups={[...creatures, ...other]} hosted={hosted} /></div>
            <div className="row bf-lands"><Stacks groups={lands} hosted={hosted} /></div>
          </div>
        )}
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
      {!inlineEffects && <Effects effects={effects} />}
    </div>
  );
}
