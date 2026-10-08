import { useStore } from "../store";
import { send } from "../ws";

/**
 * Wer steuert, und wer sieht nur zu.
 *
 * <p>Mehrere Browser koennen am selben Tisch haengen (siehe WsServer); genau einer darf klicken.
 * Ein Zuschauer, dem niemand sagt warum, haelt die Oberflaeche fuer kaputt - genau das war der
 * Anlass: Kevin wollte einer anderen Sitzung beim Spielen zusehen.</p>
 *
 * <p>Der Steuernde bekommt nur dann etwas zu sehen, wenn wirklich jemand zusieht - ein Band, das
 * immer da ist, liest nach drei Minuten niemand mehr.</p>
 */
export default function RoleBanner() {
  const control = useStore((s) => s.control);
  const watchers = useStore((s) => s.watchers);
  if (control && watchers === 0) return null;
  if (control) {
    return (
      <div className="role-banner watching-count" role="status">
        {watchers === 1 ? "1 Browser sieht zu" : `${watchers} Browser sehen zu`}
      </div>
    );
  }
  return (
    <div className="role-banner" role="status">
      <span className="role-text"><b>Du siehst zu.</b> Ein anderer Browser steuert diesen Tisch.</span>
      <button type="button" className="primary small" onClick={() => send({ type: "takeControl" })}>
        Steuerung übernehmen
      </button>
    </div>
  );
}
