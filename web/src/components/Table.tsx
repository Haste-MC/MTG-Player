import { useEffect, useRef, useState } from "react";
import { useStore, type LogEntry } from "../store";
import { send } from "../ws";
import { formatSeries, nextSeriesStep, shouldArmCountdown } from "../series";
import PlayerZone from "./PlayerZone";
import Hand from "./Hand";
import Prompt from "./Prompt";
import Log from "./Log";
import PhaseBar from "./PhaseBar";
import Thinking from "./Thinking";
import CardDetail from "./CardDetail";
import CardImage from "./CardImage";

/** Sekunden, die der Auto-Start-Countdown im Spielende-Dialog laeuft (Spec §1). */
const COUNTDOWN_SECONDS = 5;

/** Markierung "Stand des Logs beim Start-Versuch" (wie DeckPicker.tsx: markOf/lastWarnSince) - nur
 * Warnungen, die NACH einem Start ("Jetzt starten" oder der automatische Ablauf) ins Log kamen, gehoeren
 * zu diesem Versuch; ein alter Fehler aus einer frueheren Partie darf nicht als Antwort darauf gelten. */
type LogMark = { last: LogEntry | null };
const markOf = (log: LogEntry[]): LogMark => ({ last: log[log.length - 1] ?? null });
const lastWarnSince = (log: LogEntry[], mark: LogMark): string | undefined => {
  const from = mark.last ? log.lastIndexOf(mark.last) + 1 : 0;
  return log.slice(from).filter((l) => l.warn).pop()?.text;
};

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
  const matches = useStore((s) => s.matches);
  const log = useStore((s) => s.log);
  const seriesCountdown = useStore((s) => s.seriesCountdown);
  const startSeriesCountdown = useStore((s) => s.startSeriesCountdown);
  const tickSeriesCountdown = useStore((s) => s.tickSeriesCountdown);
  const cancelSeriesCountdown = useStore((s) => s.cancelSeriesCountdown);
  // "Serie beenden" wurde fuer DIESEN Spielende-Dialog geklickt - haelt die Serie an, ohne den Stand zu
  // verwerfen (anders als resetSeries/"Neue Serie"). Setzt sich zurueck, sobald der Dialog neu aufgeht.
  const [cancelled, setCancelled] = useState(false);
  // Genau EIN automatischer Start je Spielende-Dialog (Review zu bbef779): ohne diese Sperre wuerde ein
  // von der Bruecke abgelehnter Auto-Start (error statt Snapshot, expectNewMatch faellt zurueck auf
  // false) den Countdown endlos neu aufziehen - siehe shouldArmCountdown in series.ts.
  const [autoStarted, setAutoStarted] = useState(false);
  // Fehlertext eines abgelehnten Start-Versuchs (automatisch oder "Jetzt starten") - siehe startMarkRef/
  // lastWarnSince unten. Ein manueller Retry-Klick setzt ihn wieder zurueck.
  const [startError, setStartError] = useState<string>();
  const startMarkRef = useRef<LogMark>();
  const dialogOpen = winner !== undefined || !!state?.gameOver;
  useEffect(() => {
    if (dialogOpen) { setCancelled(false); setAutoStarted(false); setStartError(undefined); startMarkRef.current = undefined; }
  }, [dialogOpen]);
  // Kommt nach einem markierten Start-Versuch eine neue Warn-Zeile ins Log (die Bridge lehnt startGame
  // z. B. wegen eines Deckfehlers mit "error" ab), ist das die Antwort auf genau diesen Versuch.
  useEffect(() => {
    if (startMarkRef.current === undefined) return;
    const warn = lastWarnSince(log, startMarkRef.current);
    if (!warn) return;
    startMarkRef.current = undefined;
    setStartError(warn);
  }, [log]);
  // Der Datensatz der zuletzt beendeten Partie ist erst mit der naechsten "matches"-Nachricht da (siehe
  // Aufgabenstellung) - bis dahin wird sie optimistisch als gewertet behandelt; kommt kurz danach
  // counted: false nach, faengt der Aufraeum-Effekt unten einen bereits gestarteten Countdown wieder ab.
  const lastMatch = matches[matches.length - 1];
  const lastMatchCounted = lastMatch ? lastMatch.counted : true;
  const step = cancelled ? { kind: "none" as const } : nextSeriesStep(series, bestOf, lastMatchCounted);
  // Countdown anstossen, sobald der Dialog mit kind "countdown" aufgeht - siehe shouldArmCountdown fuer
  // die Bedingungen (insbesondere !autoStarted gegen die Endlosschleife aus dem Review).
  useEffect(() => {
    if (shouldArmCountdown(step, { dialogOpen, expectNewMatch, autoStarted, seriesCountdown })) {
      startSeriesCountdown(COUNTDOWN_SECONDS);
    }
  }, [dialogOpen, step.kind, expectNewMatch, autoStarted, seriesCountdown, startSeriesCountdown]);
  // Aufraeumen: Dialog zu, kein countdown-Schritt (mehr) oder entschieden - ein laufender Timer-Stand
  // darf nicht stehen bleiben (z. B. wenn die "matches"-Nachricht nachtraeglich counted: false bringt).
  useEffect(() => {
    if ((!dialogOpen || step.kind !== "countdown") && seriesCountdown !== undefined) cancelSeriesCountdown();
  }, [dialogOpen, step.kind, seriesCountdown, cancelSeriesCountdown]);
  // Der eigentliche Sekundentakt: startet einmal, solange ein Countdown laeuft, und raeumt sich beim
  // Unmount oder sobald seriesCountdown wieder undefined ist (Ablauf oder Abbruch) selbst auf - liest
  // den aktuellen Stand direkt aus dem Store, damit der Interval nicht bei jedem Tick neu aufgesetzt wird.
  useEffect(() => {
    if (seriesCountdown === undefined) return;
    const id = window.setInterval(() => {
      const current = useStore.getState().seriesCountdown;
      if (current === undefined) return; // zwischenzeitlich abgebrochen
      if (current <= 1) {
        tickSeriesCountdown(); // -> undefined, laesst nie eine "0" stehen
        const { lastStart: latestStart, expectNewMatch: already } = useStore.getState();
        if (latestStart && !already) {
          setAutoStarted(true); // sperrt shouldArmCountdown dauerhaft fuer diesen Dialog
          startMarkRef.current = markOf(useStore.getState().log);
          noteStart(latestStart);
          send(latestStart);
        }
      } else {
        tickSeriesCountdown();
      }
    }, 1000);
    return () => window.clearInterval(id);
    // Bewusst nur an-/abschalten statt bei jedem Tick neu zu erstellen: der Vergleich mit undefined
    // reicht als Trigger, der aktuelle Stand kommt im Callback direkt aus dem Store.
  }, [seriesCountdown === undefined]);
  if (!state) return <div className="lobby"><div className="lobby-card"><p className="muted">Warte auf Spielzustand …</p></div></div>;
  const names = state.players.map((p) => p.name);
  // Doppelklick-Schutz: nach noteStart() ist expectNewMatch bis zum naechsten Snapshot (oder error) gesetzt.
  const again = () => {
    if (!lastStart || expectNewMatch) return;
    if (step.kind === "decided") resetSeries();
    noteStart(lastStart);
    send(lastStart);
  };
  const startNow = () => {
    setStartError(undefined);
    startMarkRef.current = markOf(useStore.getState().log);
    cancelSeriesCountdown();
    again();
  };
  const endSeries = () => {
    setCancelled(true);
    cancelSeriesCountdown();
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
        // Zuschauer: die Prompt-Leiste ist die Fusszeile - Serienstand und Denk-Anzeige stehen neben den
        // Steuerknoepfen (Spec §1: der Serienstand soll waehrend der Partie sichtbar sein).
        <Prompt state={state} dangerLabel="Beenden">
          {series && (series.games > 1 || bestOf > 0) && (
            <span className="series-status compact">Serie: {formatSeries(series, names)}{bestOf > 0 && ` · Best of ${bestOf}`}</span>
          )}
          <Thinking compact />
        </Prompt>
      ) : (
        <div className="mine">
          {me && <PlayerZone p={me} state={state} compact={false} />}
          <PhaseBar state={state} />
          <Prompt state={state}><Thinking /></Prompt>
          <Hand state={state} />
        </div>
      )}
      {dialogOpen && (
        <div className="overlay">
          <div className="dialog">
            <h3>{winner ? `${winner} gewinnt` : state.gameOver ? "Spiel beendet" : "Unentschieden"}</h3>
            {series && (series.games > 1 || bestOf > 0) && (
              <p className="series">Serie: {formatSeries(series, names)}{bestOf > 0 && ` · Best of ${bestOf}`}
                {step.kind === "decided" && <b> – {step.winner} gewinnt die Serie</b>}</p>
            )}
            {step.kind === "countdown" && (
              <p className={"series-countdown" + (startError ? " warn" : "")} aria-live="polite">
                {startError
                  // Der automatische (oder manuelle) Start ist gescheitert (die Bridge hat mit "error"
                  // geantwortet) - der Text ersetzt den Countdown, kein neuer Versuch von selbst.
                  ? startError
                  : seriesCountdown !== undefined
                  ? `Spiel ${series!.games + 1} von ${bestOf} startet in ${seriesCountdown} …`
                  // Countdown ist schon abgelaufen (expectNewMatch), das naechste Spiel wurde bereits
                  // angefordert - hier nur noch auf den Snapshot warten, kein neuer Countdown von vorn.
                  : `Spiel ${series!.games + 1} von ${bestOf} startet …`}
              </p>
            )}
            <div className="buttons">
              {step.kind === "countdown" ? (
                <>
                  {/* Nach einem Fehler bleibt bewusst nur "Jetzt starten"/"Zur Lobby" stehen (Review zu
                      bbef779): "Serie beenden" wuerde ein Abbrechen versprechen, das nichts mehr abbricht -
                      der fehlgeschlagene Start ist ja schon beantwortet. Waehrend ein Start unterwegs ist
                      (expectNewMatch), sind beide Knoepfe gesperrt statt nur "Jetzt starten" - "Serie
                      beenden" koennte den laufenden Auto-Start sonst nicht mehr zuruecknehmen. */}
                  {!startError && <button className="quiet" disabled={expectNewMatch} onClick={endSeries}>Serie beenden</button>}
                  <button className="primary" disabled={expectNewMatch} onClick={startNow}>Jetzt starten</button>
                </>
              ) : (
                // Nach "Serie beenden" bleibt der Dialog bewusst nur mit "Zur Lobby" stehen (Spec §1) -
                // kein manuelles "Nochmal spielen" als Hintertuer fuer einen Auto-Start, den man gerade
                // abgebrochen hat.
                !cancelled && lastStart && (
                  <button className="primary" disabled={expectNewMatch} onClick={again}>
                    {step.kind === "decided" ? "Neue Serie" : "Nochmal spielen"}
                  </button>
                )
              )}
              <button className={!cancelled && lastStart ? "quiet" : "primary"} onClick={backToLobby}>Zur Lobby</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
