import { useEffect, useRef } from "react";
import { useStore } from "../store";

export default function Log() {
  const log = useStore((s) => s.log);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { ref.current?.scrollTo(0, ref.current.scrollHeight); }, [log.length]);
  return (
    <div className="log" ref={ref}>
      {log.map((l, i) => <div key={i}>{l}</div>)}
    </div>
  );
}
