import { PHASES, type Snapshot } from "../protocol";
import { send } from "../ws";

export default function PhaseBar({ state }: { state: Snapshot }) {
  const ownTurn = state.activePlayer === state.me;
  const side: "own" | "opp" = ownTurn ? "own" : "opp";
  const stops = new Set(state.stops?.[side] ?? []);
  const toggle = (id: string) => {
    const next = new Set(stops);
    if (next.has(id)) next.delete(id); else next.add(id);
    send({ type: "setStops", [side]: [...next] } as { type: "setStops"; own?: string[]; opp?: string[] });
  };
  return (
    <div className="phasebar">
      <span className="side">{ownTurn ? "Eigener Zug" : "Gegnerzug"}</span>
      {PHASES.map((ph) => (
        <button
          key={ph.id}
          title={ph.id + (stops.has(ph.id) ? " – Stop" : " – wird übersprungen")}
          className={"phase" + (state.phase === ph.id ? " current" : "") + (stops.has(ph.id) ? " stop" : "")}
          onClick={() => toggle(ph.id)}
        >{ph.short}</button>
      ))}
      <label className="fullcontrol">
        <input type="checkbox" checked={state.fullControl} onChange={(e) => send({ type: "fullControl", value: e.target.checked })} />
        Volle Kontrolle
      </label>
    </div>
  );
}
