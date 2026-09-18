import { useState } from "react";
import type { DeckRef } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

type Pick = { kind: "precon" | "saved" | "text"; value: string; name: string };

const EMPTY: Pick = { kind: "precon", value: "", name: "" };

function toRef(p: Pick): DeckRef | undefined {
  if (p.kind === "text") return p.value.trim() ? { text: p.value, name: p.name.trim() || undefined } : undefined;
  if (!p.value) return undefined;
  return p.kind === "precon" ? { precon: p.value } : { saved: p.value };
}

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const [human, setHuman] = useState<Pick>(EMPTY);
  const [ais, setAis] = useState<Pick[]>([EMPTY]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const ready = humanRef !== undefined && aiRefs.every((r) => r !== undefined);
  const lastError = [...log].reverse().find((l) => l.startsWith("⚠"));

  const start = () => {
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
      {picker(human, setHuman)}
      {ais.map((a, i) => (
        <div key={i}>
          <label>KI {i + 1} {ais.length > 1 && <button onClick={() => setAis(ais.filter((_, j) => j !== i))}>–</button>}</label>
          {picker(a, (n) => setAis(ais.map((x, j) => (j === i ? n : x))))}
        </div>
      ))}
      {ais.length < 5 && <button onClick={() => setAis([...ais, EMPTY])}>+ KI</button>}
      <button className="primary" disabled={!ready} onClick={start}>Spiel starten</button>
      {lastError && <pre className="import-error">{lastError}</pre>}
    </div>
  );
}
