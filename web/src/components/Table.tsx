import { useStore } from "../store";
import PlayerZone from "./PlayerZone";
import Hand from "./Hand";
import Prompt from "./Prompt";
import Log from "./Log";
import PhaseBar from "./PhaseBar";
import CardDetail from "./CardDetail";

export default function Table() {
  const state = useStore((s) => s.state);
  const winner = useStore((s) => s.winner);
  const backToLobby = useStore((s) => s.backToLobby);
  if (!state) return <div className="lobby"><p>Warte auf Spielzustand …</p></div>;
  const me = state.players.find((p) => p.id === state.me);
  const foes = state.players.filter((p) => p.id !== state.me);
  return (
    <div className="table">
      <div className="opponents">
        {foes.map((p) => <PlayerZone key={p.id} p={p} state={state} compact={foes.length > 1} />)}
      </div>
      <div className="side">
        <CardDetail />
        <div className="stack">
          <b>Stack</b>
          {state.stack.length === 0 && <div className="empty">leer</div>}
          {[...state.stack].reverse().map((s) => (
            <div key={s.index} className="stack-item">{s.text}</div>
          ))}
        </div>
        <Log />
      </div>
      <div className="mine">
        {me && <PlayerZone p={me} state={state} compact={false} />}
        <PhaseBar state={state} />
        <Prompt state={state} />
        <Hand state={state} />
      </div>
      {(winner !== undefined || state.gameOver) && (
        <div className="overlay">
          <div className="dialog">
            <h3>{winner ? `${winner} gewinnt` : state.gameOver ? "Spiel beendet" : "Unentschieden"}</h3>
            <button className="primary" onClick={backToLobby}>Zur Lobby</button>
          </div>
        </div>
      )}
    </div>
  );
}
