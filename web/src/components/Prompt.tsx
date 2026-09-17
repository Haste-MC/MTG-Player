import { useEffect } from "react";
import type { Snapshot } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

export default function Prompt({ state }: { state: Snapshot }) {
  const p = state.prompt;
  const choiceOpen = useStore((s) => s.choice !== undefined);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (choiceOpen) return;
      if (e.target instanceof HTMLInputElement) return;
      if ((e.key === "Enter" || e.key === " ") && p.okEnabled) { e.preventDefault(); send({ type: "ok" }); }
      if (e.key === "Escape" && p.cancelEnabled) { e.preventDefault(); send({ type: "cancel" }); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [p.okEnabled, p.cancelEnabled, choiceOpen]);
  return (
    <div className="prompt">
      <span className="phase">Zug {state.turn} · {state.phase ?? ""}</span>
      <span className="message">{p.message}</span>
      <button disabled={!p.okEnabled} onClick={() => send({ type: "ok" })}>{p.okLabel}</button>
      <button disabled={!p.cancelEnabled} onClick={() => send({ type: "cancel" })}>{p.cancelLabel}</button>
      <button className="danger" onClick={() => send({ type: "concede" })}>Aufgeben</button>
    </div>
  );
}
