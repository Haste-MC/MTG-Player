import { useEffect, useRef } from "react";
import { useStore } from "../store";

export default function Log() {
  const log = useStore((s) => s.log);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollTo(0, ref.current.scrollHeight); }, [log.length]);
  return (
    <div className="log">
      <div className="panel-title">Log</div>
      <div className="log-lines" ref={ref}>
        {log.length === 0 && <div className="empty">noch nichts passiert</div>}
        {log.map((l, i) => <div key={i} className={l.startsWith("⚠") ? "warn" : undefined}>{l}</div>)}
      </div>
    </div>
  );
}
