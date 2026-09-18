import { useEffect, useState } from "react";
import type { DeckRef } from "../protocol";
import { type Pick, toRef } from "../deckref";
import { useStore } from "../store";
import { send } from "../ws";

const EMPTY: Pick = { kind: "precon", value: "", name: "" };

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const [human, setHuman] = useState<Pick>(EMPTY);
  const [ais, setAis] = useState<Pick[]>([EMPTY]);
  const [shownError, setShownError] = useState<string>();

  // Neue Fehler (letzte Log-Zeile) automatisch zeigen - unabhaengig davon, ob eine fruehere
  // Fehlermeldung gerade per editPicker/start weggewischt wurde.
  useEffect(() => {
    const last = log[log.length - 1];
    if (last && last.startsWith("⚠")) setShownError(last);
  }, [log]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const ready = humanRef !== undefined && aiRefs.every((r) => r !== undefined);

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
    if (!ready) return;
    send({
      type: "startGame",
      humanDeck: humanRef!,
      opponents: aiRefs.map((r, i) => ({ ...(r as DeckRef), name: `KI ${i + 1}` })),
    });
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
      <h1>MTG-Player</h1>
      {precons.length === 0 && <p>Verbinde mit der Bridge …</p>}
      <label>Dein Deck</label>
      {picker(human, editHuman)}
      {ais.map((a, i) => (
        <div key={i}>
          <label>KI {i + 1} {ais.length > 1 && (
            <button onClick={() => { setShownError(undefined); setAis(ais.filter((_, j) => j !== i)); }}>–</button>
          )}</label>
          {picker(a, (n) => editAi(i, n))}
        </div>
      ))}
      {ais.length < 5 && (
        <button onClick={() => { setShownError(undefined); setAis([...ais, EMPTY]); }}>+ KI</button>
      )}
      <button className="primary" disabled={!ready} onClick={start}>Spiel starten</button>
      {shownError && <pre className="import-error">{shownError}</pre>}
    </div>
  );
}
