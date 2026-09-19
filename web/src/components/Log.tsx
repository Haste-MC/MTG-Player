import { useEffect, useRef } from "react";
import type { LogEntry } from "../store";
import { useStore } from "../store";

/** Zeilen-Klasse: warn wie bisher, TURN als Trennzeile, Rest über data-kind in styles.css eingefärbt. */
function lineClass(l: LogEntry): string {
  const classes = ["log-line"];
  if (l.warn) classes.push("warn");
  if (l.kind === "TURN") classes.push("turn");
  return classes.join(" ");
}

export default function Log() {
  const log = useStore((s) => s.log);
  const hiddenKinds = useStore((s) => s.hiddenKinds);
  const toggleKind = useStore((s) => s.toggleKind);
  const cards = useStore((s) => s.state?.cards);
  const setHover = useStore((s) => s.setHover);
  const ref = useRef<HTMLDivElement>(null);
  // Merkt sich zwischen Renders, ob der Nutzer am unteren Rand "klebt" – gesetzt vom onScroll-Handler,
  // also bevor der naechste Log-Eintrag den Scroll-Container veraendert.
  const stuckToBottom = useRef(true);

  // Kategorien, die überhaupt im Log vorkommen (auch ausgeblendete – die Chips bleiben klickbar).
  const kinds = Array.from(new Set(log.map((l) => l.kind).filter((k): k is string => !!k))).sort();
  const visible = log.filter((l) => !l.kind || !hiddenKinds.includes(l.kind));

  const onScroll = () => {
    const el = ref.current;
    if (!el) return;
    stuckToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  };

  // Haengt am letzten Eintrag des vollen logs (nicht visible.length): sobald der Puffer bei 500 Zeilen
  // deckelt, waechst visible.length nicht mehr fuer jede neue Zeile (alte fallen vorne raus, wenn eine
  // neue hinten dazukommt) - der Effekt liefe sonst nicht mehr an und das Autoscroll blieb stehen.
  const last = log[log.length - 1];
  useEffect(() => {
    if (stuckToBottom.current) ref.current?.scrollTo(0, ref.current.scrollHeight);
  }, [log.length, last?.id, last?.text]);

  return (
    <div className="log">
      <div className="panel-title">
        Log
        {kinds.length > 0 && (
          <div className="log-filter">
            {kinds.map((k) => (
              <button key={k} type="button" className={"chip" + (hiddenKinds.includes(k) ? " off" : "")}
                title={`${k} ein-/ausblenden`} onClick={() => toggleKind(k)}>
                {k}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="log-lines" ref={ref} onScroll={onScroll}>
        {visible.length === 0 && <div className="empty">noch nichts passiert</div>}
        {visible.map((l, i) => {
          const hoverable = l.card !== undefined && !!cards?.[String(l.card)];
          return (
            <div key={i} className={lineClass(l)} data-kind={l.kind} data-card={hoverable ? l.card : undefined}
              onMouseEnter={hoverable ? () => setHover(l.card) : undefined}
              onMouseLeave={hoverable ? () => setHover(undefined) : undefined}>
              {l.text}
            </div>
          );
        })}
      </div>
    </div>
  );
}
