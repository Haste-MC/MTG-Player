import { useStore } from "../store";
import PlayerZone from "./PlayerZone";
import Hand from "./Hand";
import Prompt from "./Prompt";
import Log from "./Log";
import PhaseBar from "./PhaseBar";
import CardDetail from "./CardDetail";
import CardImage from "./CardImage";

export default function Table() {
  const state = useStore((s) => s.state);
  const winner = useStore((s) => s.winner);
  const backToLobby = useStore((s) => s.backToLobby);
  if (!state) return <div className="lobby"><div className="lobby-card"><p className="muted">Warte auf Spielzustand …</p></div></div>;
  const spectator = !!state.spectator;
  const me = state.players.find((p) => p.id === state.me);
  const foes = state.players.filter((p) => p.id !== state.me);
  const stack = [...state.stack].reverse(); // oberstes Element (löst zuerst auf) zuerst
  return (
    <div className={"table" + (spectator ? " spectator" : "")}>
      {spectator ? (
        <div className={"spectator-grid players-" + state.players.length}>
          {state.players.map((p) => <PlayerZone key={p.id} p={p} state={state} compact={true} spectator={true} />)}
        </div>
      ) : (
        <div className="opponents">
          {foes.map((p) => <PlayerZone key={p.id} p={p} state={state} compact={true} />)}
        </div>
      )}
      <div className="side">
        <CardDetail />
        <div className="stack">
          <div className="panel-title">Stack{state.stack.length > 0 && <span className="count">{state.stack.length}</span>}
            {state.stack.length > 1 && <span className="muted">oben löst zuerst auf</span>}
          </div>
          {state.stack.length === 0 && <div className="empty">leer</div>}
          {stack.map((s, i) => {
            const src = s.sourceCard !== undefined ? state.cards[String(s.sourceCard)] : undefined;
            const owner = s.controller !== undefined ? state.players.find((p) => p.id === s.controller)?.name : undefined;
            return (
              <div key={s.index} className="stack-item">
                {i > 0 && <div className="stack-arrow" aria-hidden>↑</div>}
                <div className="stack-card">
                  <div className="stack-thumb">{src?.imageKey && <CardImage imageKey={src.imageKey} className="art" />}</div>
                  <div className="stack-body">
                    <div className="stack-text">{s.text}</div>
                    {owner && <div className="muted">{owner}</div>}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
        <Log />
      </div>
      {spectator ? (
        <Prompt state={state} dangerLabel="Beenden" />
      ) : (
        <div className="mine">
          {me && <PlayerZone p={me} state={state} compact={false} />}
          <PhaseBar state={state} />
          <Prompt state={state} />
          <Hand state={state} />
        </div>
      )}
      {(winner !== undefined || state.gameOver) && (
        <div className="overlay">
          <div className="dialog">
            <h3>{winner ? `${winner} gewinnt` : state.gameOver ? "Spiel beendet" : "Unentschieden"}</h3>
            <div className="buttons"><button className="primary" onClick={backToLobby}>Zur Lobby</button></div>
          </div>
        </div>
      )}
    </div>
  );
}
