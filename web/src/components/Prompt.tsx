import { useEffect, type ReactNode } from "react";
import type { Snapshot } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";
import { partnerOf } from "../teams";

/** Satz der Leiste am ausgeschiedenen Sitz. "Your team plays on" stimmt nur mit Partner: in einer Partie ohne Teams
 *  (1v3, jeder gegen jeden) endet das Spiel nicht, wenn der Mensch stirbt - dort gibt es kein Team, das weiterspielt. */
function outText(state: Snapshot): string {
  return partnerOf(state) ? "You are out. Your team plays on." : "You are out. The game continues without you.";
}

/** children stehen zwischen Nachricht und Knoepfen in der Leiste (Table.tsx haengt dort die Denk-Anzeige
 *  ein - in der Leiste statt darueber, damit nichts umbricht oder das Spielfeld umskaliert).
 *
 *  out: der eigene Sitz ist ausgeschieden, das Team spielt weiter (Aufgabe 11). Dann gibt es keinen Prompt
 *  mehr zu bedienen - die Leiste sagt das und behaelt nur den Ausweg ("End game"); Enter/Escape bleiben stumm. */
export default function Prompt({ state, dangerLabel = "Concede", out = false, children }: { state: Snapshot; dangerLabel?: string; out?: boolean; children?: ReactNode }) {
  const p = state.prompt;
  const choiceOpen = useStore((s) => s.choices.length > 0);
  const toast = useStore((s) => s.toast);
  /** Zuschauer duerfen nicht klicken (siehe RoleBanner) - gesperrt statt wirkungslos. */
  const control = useStore((s) => s.control);
  const clearToast = useStore((s) => s.clearToast);
  // Toast (z. B. "Das geht gerade nicht.") nach 2 s ausblenden; toast.n startet den Timer bei Wiederholung neu.
  useEffect(() => {
    if (!toast) return;
    const t = window.setTimeout(clearToast, 2000);
    return () => window.clearTimeout(t);
  }, [toast, clearToast]);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (choiceOpen || out) return;
      if (e.target instanceof HTMLInputElement) return;
      if (e.target instanceof HTMLButtonElement) return;
      if ((e.key === "Enter" || e.key === " ") && p.okEnabled) { e.preventDefault(); send({ type: "ok", seq: p.seq }); }
      if (e.key === "Escape" && p.cancelEnabled) { e.preventDefault(); send({ type: "cancel", seq: p.seq }); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [p.okEnabled, p.cancelEnabled, choiceOpen, p.seq, out]);
  return (
    <div className="prompt">
      <span className="phase-chip"><span className="turn">Turn {state.turn}</span><span className="sep">·</span>{state.phase ?? ""}</span>
      <span className="message">{out ? outText(state) : p.message}</span>
      {toast && <span className="toast" role="status" key={toast.n}>{toast.text}</span>}
      {children}
      <span className="actions">
        {!out && <button className="primary" disabled={!p.okEnabled || !control} onClick={() => send({ type: "ok", seq: p.seq })}>{p.okLabel}</button>}
        {!out && <button disabled={!p.cancelEnabled || !control} onClick={() => send({ type: "cancel", seq: p.seq })}>{p.cancelLabel}</button>}
        <button className="quiet danger" onClick={() => send({ type: "concede" })}>{dangerLabel}</button>
      </span>
    </div>
  );
}
