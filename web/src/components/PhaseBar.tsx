import { PHASES, type Snapshot } from "../protocol";
import { formatSeries } from "../series";
import { useStore } from "../store";
import { send } from "../ws";

export default function PhaseBar({ state }: { state: Snapshot }) {
  // Ohne bekannten aktiven Spieler (Bridge kennt ihn noch nicht) wird der eigene Zug angenommen.
  const ownTurn = state.activePlayer === undefined || state.activePlayer === state.me;
  const side: "own" | "opp" = ownTurn ? "own" : "opp";
  const stops = new Set(state.stops?.[side] ?? []);
  const series = useStore((s) => s.series);
  const bestOf = useStore((s) => s.bestOf);
  const toggle = (id: string) => {
    const next = new Set(stops);
    if (next.has(id)) next.delete(id); else next.add(id);
    send({ type: "setStops", [side]: [...next] } as { type: "setStops"; own?: string[]; opp?: string[] });
  };
  return (
    <div className="phasebar">
      <span className="side">{ownTurn ? "Eigener Zug" : "Gegnerzug"}</span>
      {series && (series.games > 1 || bestOf > 0) && (
        // Serienstand waehrend der Partie (Spec §1) - dieselbe Anzeige wie im Spielende-Dialog/Zuschauer-
        // Fusszeile (Table.tsx), damit "Serie: …" ueberall gleich aussieht.
        <span className="series-status">Serie: {formatSeries(series, state.players.map((p) => p.name))}{bestOf > 0 && ` · Best of ${bestOf}`}</span>
      )}
      <span className="segments">
        {PHASES.map((ph) => (
          <button
            key={ph.id}
            title={ph.id + (stops.has(ph.id) ? " – Stop" : " – wird übersprungen")}
            className={"phase" + (state.phase === ph.id ? " current" : "") + (stops.has(ph.id) ? " stop" : "")}
            onClick={() => toggle(ph.id)}
          >{ph.short}</button>
        ))}
      </span>
      <label className="fullcontrol">
        <input type="checkbox" checked={state.fullControl} onChange={(e) => send({ type: "fullControl", value: e.target.checked })} />
        Volle Kontrolle
      </label>
    </div>
  );
}
