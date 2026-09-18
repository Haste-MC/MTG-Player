import { useState } from "react";
import type { Choice, Option } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";
import { amountsValid, cardlistDirections, isPermutation, remaining } from "../dialogs";
import CardImage from "./CardImage";

export default function ChoiceDialog({ choice }: { choice: Choice }) {
  const clear = useStore((s) => s.clearChoice);
  const cards = useStore((s) => s.state?.cards ?? {});
  const [picked, setPicked] = useState<number[]>([]);
  const [text, setText] = useState("");
  const [amounts, setAmounts] = useState<number[]>(() => choice.options.map(() => 0));
  const [order, setOrder] = useState<number[]>(() => choice.options.map((o) => o.index));

  const answer = (value: unknown) => {
    send({ type: "answer", id: choice.id, value });
    clear(choice.id);
  };

  const label = (o: { label: string; card?: number }) =>
    o.card !== undefined && cards[String(o.card)]?.name && !o.label.includes(cards[String(o.card)].name!)
      ? `${o.label} [${cards[String(o.card)].name}]` : o.label;

  const optionView = (o: Option) => (
    <div className="opt-with-img">
      <div className="thumb">{o.detail?.imageKey && <CardImage imageKey={o.detail.imageKey} className="art-small" />}</div>
      <div className="grow">
        <div className="opt-label">{label(o)}</div>
        {o.detail && (
          <div className="opt-detail">
            <span className="type">{o.detail.typeLine}</span>
            {o.detail.power !== undefined && <span className="pt"> {o.detail.power}/{o.detail.toughness}</span>}
            <div className="text">{o.detail.text}</div>
          </div>
        )}
      </div>
    </div>
  );

  const body = () => {
    switch (choice.kind) {
      case "reveal":
        return (
          <>
            <ul className="options">
              {choice.options.map((o) => (
                <li key={o.index}>{optionView(o)}</li>
              ))}
            </ul>
            <div className="buttons">
              <button className="primary" onClick={() => clear(choice.id)}>OK</button>
            </div>
          </>
        );
      case "confirm":
        return (
          <div className="buttons">
            <button onClick={() => answer(false)}>{choice.options[1]?.label ?? "Nein"}</button>
            <button className="primary" onClick={() => answer(true)}>{choice.options[0]?.label ?? "Ja"}</button>
          </div>
        );
      case "number":
      case "text":
        return (
          <form onSubmit={(e) => { e.preventDefault(); answer(choice.kind === "number" ? Number(text || 0) : text); }}>
            <input autoFocus type={choice.kind === "number" ? "number" : "text"} value={text} onChange={(e) => setText(e.target.value)} />
            <div className="buttons">
              <button type="button" onClick={() => answer(null)}>Abbrechen</button>
              <button type="submit" className="primary">OK</button>
            </div>
          </form>
        );
      case "one":
      case "ability":
      case "entities":
      case "many":
      case "order": {
        const single = choice.kind === "one" || choice.kind === "ability" || (choice.max === 1 && choice.kind !== "order");
        const bare = choice.kind === "one" || choice.kind === "ability";
        const toggle = (i: number) => {
          if (single) { answer(bare ? i : [i]); return; }
          setPicked(picked.includes(i) ? picked.filter((x) => x !== i) : [...picked, i]);
        };
        const ok = choice.kind === "order" ? picked.length === choice.options.length
          : picked.length >= choice.min && (choice.max <= 0 || picked.length <= choice.max);
        return (
          <>
            <ul className="options">
              {choice.options.map((o) => (
                <li key={o.index} className={"options-row" + (picked.includes(o.index) ? " picked" : "")} onClick={() => toggle(o.index)}>
                  {choice.kind === "order" && picked.includes(o.index) && <span className="pos">{picked.indexOf(o.index) + 1}. </span>}
                  <div className="grow">{optionView(o)}</div>
                </li>
              ))}
            </ul>
            {!single && (
              <div className="buttons">
                {choice.min === 0 && <button onClick={() => answer([])}>Keine</button>}
                <button className="primary" disabled={!ok} onClick={() => answer(picked)}>OK</button>
              </div>
            )}
            {single && choice.min === 0 && <div className="buttons"><button onClick={() => answer(bare ? null : [])}>Keine</button></div>}
          </>
        );
      }
      case "damage":
      case "amount": {
        const total = choice.amount ?? 0;
        const maxPer = choice.options.map((o) => o.max);
        const ok = amountsValid(amounts, total, maxPer, choice.atLeastOne ?? false);
        const set = (i: number, v: number) => setAmounts(amounts.map((a, j) => (j === i ? v : a)));
        return (
          <>
            <ul className="options">
              {choice.options.map((o, i) => (
                <li key={o.index} className={"amount-row" + (amounts[i] > 0 ? " picked" : "")}>
                  <div className="thumb">{o.detail?.imageKey && <CardImage imageKey={o.detail.imageKey} className="art-small" />}</div>
                  <div className="grow">
                    <div className="opt-label-row">
                      <span className="opt-label">{label(o)}</span>
                      {o.lethal !== undefined && <span className="chip lethal">tödlich {o.lethal}</span>}
                      {o.max !== undefined && <span className="chip">max {o.max}</span>}
                    </div>
                    {o.detail && (
                      <div className="opt-detail">
                        <span className="type">{o.detail.typeLine}</span>
                        {o.detail.power !== undefined && <span className="pt"> {o.detail.power}/{o.detail.toughness}</span>}
                      </div>
                    )}
                  </div>
                  <div className="amount-input">
                    <input type="number" min={0} max={o.max ?? total} value={amounts[i]} onChange={(e) => set(i, Number(e.target.value))} />
                    {o.lethal !== undefined && <button onClick={() => set(i, Math.min(o.lethal!, remaining(amounts, total) + amounts[i]))}>tödlich</button>}
                  </div>
                </li>
              ))}
            </ul>
            <div className="buttons">
              <span className="hint grow">Rest: <b>{remaining(amounts, total)}</b> von {total}</span>
              <button onClick={() => answer(null)}>Automatisch</button>
              <button className="primary" disabled={!ok} onClick={() => answer(amounts)}>OK</button>
            </div>
          </>
        );
      }
      case "cardlist": {
        const move = (from: number, to: number) => {
          if (to < 0 || to >= order.length) return;
          const next = [...order];
          const [x] = next.splice(from, 1);
          next.splice(to, 0, x);
          setOrder(next);
        };
        const { top, bottom, anywhere } = cardlistDirections(choice.flags);
        return (
          <>
            <ul className="options">
              {order.map((idx, pos) => {
                const o = choice.options[idx];
                return (
                  <li key={o.index} className={"cardlist-row" + (o.movable ? " movable" : "")}>
                    <span className="pos">{pos + 1}.</span>
                    <div className="grow">{optionView(o)}</div>
                    {o.movable && <>
                      {top && <button onClick={() => move(pos, 0)}>⤒</button>}
                      {anywhere && <button onClick={() => move(pos, pos - 1)}>↑</button>}
                      {anywhere && <button onClick={() => move(pos, pos + 1)}>↓</button>}
                      {bottom && <button onClick={() => move(pos, order.length - 1)}>⤓</button>}
                    </>}
                  </li>
                );
              })}
            </ul>
            <div className="buttons">
              <button className="primary" disabled={!isPermutation(order, choice.options.length)} onClick={() => answer(order)}>OK</button>
            </div>
          </>
        );
      }
    }
  };

  return (
    <div className="overlay">
      <div className="dialog">
        <h3>{choice.title}</h3>
        {choice.message !== choice.title && <p className="dialog-message">{choice.message}</p>}
        {choice.kind === "order" && <p className="hint">In gewünschter Reihenfolge anklicken (oben zuerst).</p>}
        {choice.kind === "many" && <p className="hint">{choice.min}–{choice.max <= 0 ? "beliebig" : choice.max} auswählen</p>}
        {body()}
      </div>
    </div>
  );
}
