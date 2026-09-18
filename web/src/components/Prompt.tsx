import { useEffect } from "react";
import type { Snapshot } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

export default function Prompt({ state }: { state: Snapshot }) {
  const p = state.prompt;
  const choiceOpen = useStore((s) => s.choices.length > 0);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (choiceOpen) return;
      if (e.target instanceof HTMLInputElement) return;
      if (e.target instanceof HTMLButtonElement) return;
      if ((e.key === "Enter" || e.key === " ") && p.okEnabled) { e.preventDefault(); send({ type: "ok", seq: p.seq }); }
      if (e.key === "Escape" && p.cancelEnabled) { e.preventDefault(); send({ type: "cancel", seq: p.seq }); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [p.okEnabled, p.cancelEnabled, choiceOpen, p.seq]);
  return (
    <div className="prompt">
      <span className="phase-chip"><span className="turn">Zug {state.turn}</span><span className="sep">·</span>{state.phase ?? ""}</span>
      <span className="message">{p.message}</span>
      <span className="actions">
        <button className="primary" disabled={!p.okEnabled} onClick={() => send({ type: "ok", seq: p.seq })}>{p.okLabel}</button>
        <button disabled={!p.cancelEnabled} onClick={() => send({ type: "cancel", seq: p.seq })}>{p.cancelLabel}</button>
        <button className="quiet danger" onClick={() => send({ type: "concede" })}>Aufgeben</button>
      </span>
    </div>
  );
}
