import { useEffect, type ReactNode } from "react";
import type { Snapshot } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

/** children stehen zwischen Nachricht und Knoepfen in der Leiste (Table.tsx haengt dort die Denk-Anzeige
 *  ein - in der Leiste statt darueber, damit nichts umbricht oder das Spielfeld umskaliert). */
export default function Prompt({ state, dangerLabel = "Aufgeben", children }: { state: Snapshot; dangerLabel?: string; children?: ReactNode }) {
  const p = state.prompt;
  const choiceOpen = useStore((s) => s.choices.length > 0);
  const toast = useStore((s) => s.toast);
  const clearToast = useStore((s) => s.clearToast);
  // Toast (z. B. "Das geht gerade nicht.") nach 2 s ausblenden; toast.n startet den Timer bei Wiederholung neu.
  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(clearToast, 2000);
    return () => window.clearTimeout(t);
  }, [toast, clearToast]);
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
      {toast && <span className="toast" role="status" key={toast.n}>{toast.text}</span>}
      {children}
      <span className="actions">
        <button className="primary" disabled={!p.okEnabled} onClick={() => send({ type: "ok", seq: p.seq })}>{p.okLabel}</button>
        <button disabled={!p.cancelEnabled} onClick={() => send({ type: "cancel", seq: p.seq })}>{p.cancelLabel}</button>
        <button className="quiet danger" onClick={() => send({ type: "concede" })}>{dangerLabel}</button>
      </span>
    </div>
  );
}
