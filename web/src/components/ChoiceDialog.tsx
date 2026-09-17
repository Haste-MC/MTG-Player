import { useState } from "react";
import type { Choice } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

export default function ChoiceDialog({ choice }: { choice: Choice }) {
  const clear = useStore((s) => s.clearChoice);
  const cards = useStore((s) => s.state?.cards ?? {});
  const [picked, setPicked] = useState<number[]>([]);
  const [text, setText] = useState("");

  const answer = (value: unknown) => {
    send({ type: "answer", id: choice.id, value });
    clear();
  };

  const label = (o: { label: string; card?: number }) =>
    o.card !== undefined && cards[String(o.card)]?.name && !o.label.includes(cards[String(o.card)].name!)
      ? `${o.label} [${cards[String(o.card)].name}]` : o.label;

  const body = () => {
    switch (choice.kind) {
      case "reveal":
        return (
          <>
            <ul className="options">
              {choice.options.map((o) => (
                <li key={o.index}>{label(o)}</li>
              ))}
            </ul>
            <div className="buttons">
              <button className="primary" onClick={() => clear()}>OK</button>
            </div>
          </>
        );
      case "confirm":
        return (
          <div className="buttons">
            <button className="primary" onClick={() => answer(true)}>{choice.options[0]?.label ?? "Ja"}</button>
            <button onClick={() => answer(false)}>{choice.options[1]?.label ?? "Nein"}</button>
          </div>
        );
      case "number":
      case "text":
        return (
          <form onSubmit={(e) => { e.preventDefault(); answer(choice.kind === "number" ? Number(text || 0) : text); }}>
            <input autoFocus type={choice.kind === "number" ? "number" : "text"} value={text} onChange={(e) => setText(e.target.value)} />
            <button type="submit" className="primary">OK</button>
          </form>
        );
      case "one":
      case "ability":
      case "entities":
      case "many":
      case "order": {
        const single = choice.kind === "one" || choice.kind === "ability" || (choice.max === 1 && choice.kind !== "order");
        const toggle = (i: number) => {
          if (single) { answer(i); return; }
          setPicked(picked.includes(i) ? picked.filter((x) => x !== i) : [...picked, i]);
        };
        const ok = choice.kind === "order" ? picked.length === choice.options.length
          : picked.length >= choice.min && (choice.max <= 0 || picked.length <= choice.max);
        return (
          <>
            <ul className="options">
              {choice.options.map((o) => (
                <li key={o.index} className={picked.includes(o.index) ? "picked" : ""} onClick={() => toggle(o.index)}>
                  {choice.kind === "order" && picked.includes(o.index) ? `${picked.indexOf(o.index) + 1}. ` : ""}{label(o)}
                </li>
              ))}
            </ul>
            {!single && (
              <div className="buttons">
                <button className="primary" disabled={!ok} onClick={() => answer(picked)}>OK</button>
                {choice.min === 0 && <button onClick={() => answer([])}>Keine</button>}
              </div>
            )}
            {single && choice.min === 0 && <div className="buttons"><button onClick={() => answer(null)}>Keine</button></div>}
          </>
        );
      }
    }
  };

  return (
    <div className="overlay">
      <div className="dialog">
        <h3>{choice.title}</h3>
        {choice.message !== choice.title && <p>{choice.message}</p>}
        {choice.kind === "order" && <p className="hint">In gewünschter Reihenfolge anklicken (oben zuerst).</p>}
        {choice.kind === "many" && <p className="hint">{choice.min}–{choice.max <= 0 ? "beliebig" : choice.max} auswählen</p>}
        {body()}
      </div>
    </div>
  );
}
