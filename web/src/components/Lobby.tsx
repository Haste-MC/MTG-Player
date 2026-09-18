import { useEffect, useState } from "react";
import { type Pick, toRef } from "../deckref";
import { buildStartGame } from "../lobbyPayload";
import { useStore } from "../store";
import { send } from "../ws";

const EMPTY: Pick = { kind: "precon", value: "", name: "" };

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const [human, setHuman] = useState<Pick>(EMPTY);
  const [ais, setAis] = useState<Pick[]>([EMPTY]);
  const [spectate, setSpectate] = useState(false);
  const [shownError, setShownError] = useState<string>();

  // Neue Fehler (letzte Log-Zeile) automatisch zeigen - unabhaengig davon, ob eine fruehere
  // Fehlermeldung gerade per editPicker/start weggewischt wurde.
  useEffect(() => {
    const last = log[log.length - 1];
    if (last?.warn) setShownError(last.text);
  }, [log]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const maxAis = spectate ? 6 : 5;
  const minAis = spectate ? 2 : 1;
  const msg = buildStartGame(spectate, humanRef, aiRefs);
  const ready = msg !== undefined;

  const toggleSpectate = (on: boolean) => {
    setShownError(undefined);
    setSpectate(on);
    // Zuschauer-Modus braucht mindestens 2 KIs (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
    if (on && ais.length < 2) setAis([...ais, EMPTY]);
  };
  const editHuman = (n: Pick) => {
    setShownError(undefined);
    setHuman(n);
  };
  const editAi = (i: number, n: Pick) => {
    setShownError(undefined);
    setAis(ais.map((x, j) => (j === i ? n : x)));
  };

  const start = () => {
    setShownError(undefined);
    if (msg) send(msg);
  };

  const picker = (p: Pick, onChange: (n: Pick) => void) => (
    <div className="pick">
      <select value={p.kind} onChange={(e) => onChange({ ...p, kind: e.target.value as Pick["kind"], value: "" })}>
        <option value="precon">Precon</option>
        <option value="saved">Eigenes Deck</option>
        <option value="text">Textliste</option>
      </select>
      {p.kind === "precon" && (
        <select value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })}>
          <option value="">– Precon wählen –</option>
          {precons.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
      )}
      {p.kind === "saved" && (
        <select value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })}>
          <option value="">– gespeichertes Deck –</option>
          {decks.map((x) => <option key={x} value={x}>{x}</option>)}
        </select>
      )}
      {p.kind === "text" && (
        <div className="textdeck">
          <input placeholder="Name (optional)" value={p.name} onChange={(e) => onChange({ ...p, name: e.target.value })} />
          <textarea rows={8} placeholder={"Archidekt/Arena-Export einfügen, z. B.\n1 Sol Ring (c21) 263\nCommander\n1 Felothar the Steadfast"}
            value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })} />
        </div>
      )}
    </div>
  );

  return (
    <div className="lobby">
      <div className="lobby-card">
        <div className="lobby-head">
          <h1>MTG-Player</h1>
          <p className="subtitle">Commander gegen die Forge-KI – Deck wählen, Gegner hinzufügen, losspielen.</p>
          {precons.length === 0 && <p className="connecting">Verbinde mit der Bridge …</p>}
        </div>
        <label className="spectate-toggle">
          <input type="checkbox" checked={spectate} onChange={(e) => toggleSpectate(e.target.checked)} />
          Nur KI – zuschauen
        </label>
        {!spectate && (
          <section className="lobby-section">
            <label>Dein Deck</label>
            {picker(human, editHuman)}
          </section>
        )}
        {ais.map((a, i) => (
          <section key={i} className="lobby-section">
            <label>KI {i + 1} {ais.length > minAis && (
              <button className="quiet small" title="Gegner entfernen" onClick={() => { setShownError(undefined); setAis(ais.filter((_, j) => j !== i)); }}>entfernen</button>
            )}</label>
            {picker(a, (n) => editAi(i, n))}
          </section>
        ))}
        {ais.length < maxAis && (
          <button className="ghost" onClick={() => { setShownError(undefined); setAis([...ais, EMPTY]); }}>+ Gegner hinzufügen</button>
        )}
        <button className="primary big" disabled={!ready} onClick={start}>Spiel starten</button>
        {shownError && <pre className="import-error">{shownError}</pre>}
      </div>
    </div>
  );
}
