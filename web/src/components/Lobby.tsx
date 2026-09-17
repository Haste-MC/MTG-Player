import { useState } from "react";
import { useStore } from "../store";
import { send } from "../ws";

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const [human, setHuman] = useState("");
  const [ais, setAis] = useState<string[]>([""]);

  const ready = human !== "" && ais.every((a) => a !== "");

  const start = () => {
    send({
      type: "startGame",
      humanDeck: { precon: human },
      opponents: ais.map((precon, i) => ({ precon, name: `KI ${i + 1}` })),
    });
  };

  const select = (value: string, onChange: (v: string) => void) => (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">– Precon wählen –</option>
      {precons.map((p) => (
        <option key={p} value={p}>{p}</option>
      ))}
    </select>
  );

  return (
    <div className="lobby">
      <h1>MTG-Player</h1>
      {precons.length === 0 && <p>Verbinde mit der Bridge …</p>}
      <label>Dein Deck {select(human, setHuman)}</label>
      {ais.map((a, i) => (
        <label key={i}>
          KI {i + 1} {select(a, (v) => setAis(ais.map((x, j) => (j === i ? v : x))))}
          {ais.length > 1 && <button onClick={() => setAis(ais.filter((_, j) => j !== i))}>–</button>}
        </label>
      ))}
      {ais.length < 5 && <button onClick={() => setAis([...ais, ""])}>+ KI</button>}
      <button className="primary" disabled={!ready} onClick={start}>Spiel starten</button>
    </div>
  );
}
