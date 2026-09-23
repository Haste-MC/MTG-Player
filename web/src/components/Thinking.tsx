import { useStore } from "../store";

/** Ab dieser Stille steht zusaetzlich, dass eine Simulationsrunde wirklich Minuten dauern kann - vorher
 *  waere der Satz nur Laerm, nachher beruhigt er (Spec §1). */
const LONG_SECONDS = 60;

/**
 * Ruhige Zeile, solange die Bridge laenger als 3 s ohne sichtbare Aktivitaet rechnet (store.thinking,
 * gespeist vom ThinkingTicker): „KI 2 denkt …" und rechts die bisherige Dauer. Ohne thinking rendert sie
 * nichts. Kennt der Snapshot den Sitz nicht (player fehlt/null), heisst es schlicht „KI denkt …".
 *
 * Sie sitzt bewusst IN der Prompt-Leiste (Table.tsx) und nicht als eigene Zeile darueber: eine Zeile mehr
 * in .mine aendert die Hoehe des Spielfelds, useBoardSize misst neu und das ganze Board wuerde bei jedem
 * Beginn und Ende des Rechnens umskalieren - genau das Zappeln, das diese Anzeige verhindern soll.
 * compact ist die kleinere Variante fuer die Zuschauer-Fusszeile.
 */
export default function Thinking({ compact = false }: { compact?: boolean }) {
  const thinking = useStore((s) => s.thinking);
  const players = useStore((s) => s.state?.players);
  const me = useStore((s) => s.state?.me);
  if (!thinking) return null;
  // Der Ticker meldet jede Stille, auch die, in der die Bridge auf *mich* wartet (er kennt nur den Sitz
  // mit Prioritaet, nicht ob der ein Mensch ist). „Du denkt ... 42 s", sobald man kurz ueberlegt, waere
  // falsch und stuende im Normalfall dauernd da - also nichts anzeigen, wenn der eigene Sitz dran ist.
  if (me !== undefined && thinking.player === me) return null;
  const name = players?.find((p) => p.id === thinking.player)?.name ?? "KI";
  return (
    // role="status" meldet den Beginn des Rechnens einmal; Punkte und Sekundenzahl sind aria-hidden,
    // sonst spraeche ein Screenreader jede Sekunde neu.
    <span className={"thinking" + (compact ? " compact" : "")} role="status">
      <span className="line">
        <span className="who">{name} denkt</span>
        <span className="dots" aria-hidden="true"><i /><i /><i /></span>
        <span className="secs" aria-hidden="true">{thinking.seconds} s</span>
      </span>
      {thinking.seconds >= LONG_SECONDS && <span className="hint">Simulation in einer 4er-Runde kann Minuten dauern</span>}
    </span>
  );
}
