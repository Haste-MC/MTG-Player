import { useStore } from "../store";
import { send } from "../ws";
import { formatSeries, seriesWinner } from "../series";
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
  const lastStart = useStore((s) => s.lastStart);
  const series = useStore((s) => s.series);
  const bestOf = useStore((s) => s.bestOf);
  const noteStart = useStore((s) => s.noteStart);
  const resetSeries = useStore((s) => s.resetSeries);
  const expectNewMatch = useStore((s) => s.expectNewMatch);
  if (!state) return <div className="lobby"><div className="lobby-card"><p className="muted">Warte auf Spielzustand …</p></div></div>;
  const names = state.players.map((p) => p.name);
  const seriesWon = series ? seriesWinner(series, bestOf) : undefined;
  // Doppelklick-Schutz: nach noteStart() ist expectNewMatch bis zum naechsten Snapshot (oder error) gesetzt.
  const again = () => {
    if (!lastStart || expectNewMatch) return;
    if (seriesWon) resetSeries();
    noteStart(lastStart);
    send(lastStart);
  };
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
            {series && (series.games > 1 || bestOf > 0) && (
              <p className="series">Serie: {formatSeries(series, names)}{bestOf > 0 && ` · Best of ${bestOf}`}
                {seriesWon && <b> – {seriesWon} gewinnt die Serie</b>}</p>
            )}
            <div className="buttons">
              {lastStart && <button className="primary" disabled={expectNewMatch} onClick={again}>{seriesWon ? "Neue Serie" : "Nochmal spielen"}</button>}
              <button className={lastStart ? "quiet" : "primary"} onClick={backToLobby}>Zur Lobby</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
