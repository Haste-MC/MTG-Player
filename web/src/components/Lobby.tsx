import { useEffect, useRef, useState } from "react";
import { loadAiSettings, restoreSlots, saveAiSettings } from "../aiSettings";
import { type Pick, toRef } from "../deckref";
import { buildStartGame, DEFAULT_AI } from "../lobbyPayload";
import type { AiPick } from "../protocol";
import { useStore } from "../store";
import { send } from "../ws";

const EMPTY: Pick = { kind: "precon", value: "", name: "" };

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const aiProfiles = useStore((s) => s.aiProfiles);
  const [human, setHuman] = useState<Pick>(EMPTY);
  const [ais, setAis] = useState<Pick[]>([EMPTY]);
  const [aiPicks, setAiPicks] = useState<AiPick[]>([DEFAULT_AI]);
  const [aiTimeout, setAiTimeout] = useState(5);
  const [spectate, setSpectate] = useState(false);
  const [shownError, setShownError] = useState<string>();
  // Erste echte "lobby"-Nachricht (precons gefuellt): einmalig die gespeicherte KI-Auswahl laden.
  const settingsLoaded = useRef(false);
  // Alle beim Laden gefundenen Picks, auch die, fuer die restoreSlots wegen min/max noch keinen Slot
  // angelegt hat - addAi/toggleSpectate greifen beim manuellen Hinzufuegen eines Slots hierauf zurueck,
  // statt immer DEFAULT_AI zu nehmen.
  const loadedPicks = useRef<AiPick[]>([]);

  const maxAis = spectate ? 6 : 5;
  const minAis = spectate ? 2 : 1;

  // Neue Fehler (letzte Log-Zeile) automatisch zeigen - unabhaengig davon, ob eine fruehere
  // Fehlermeldung gerade per editPicker/start weggewischt wurde.
  useEffect(() => {
    const last = log[log.length - 1];
    if (last?.warn) setShownError(last.text);
  }, [log]);

  useEffect(() => {
    if (settingsLoaded.current || precons.length === 0) return;
    settingsLoaded.current = true;
    const loaded = loadAiSettings(() => localStorage, aiProfiles);
    loadedPicks.current = loaded.picks;
    setAiTimeout(loaded.timeout);
    // AiSettings.picks.length ist die gespeicherte Slot-Zahl - so viele Slots (geklemmt auf min/max)
    // anlegen, nicht nur die Picks in die aktuell vorhandene (anfangs einzige) Zeile mappen.
    const picks = restoreSlots(loaded.picks, { min: minAis, max: maxAis });
    setAiPicks(picks);
    setAis((prev) => Array.from({ length: picks.length }, (_, i) => prev[i] ?? EMPTY));
  }, [precons, aiProfiles]);

  // Auswahl merken, sobald sie geladen ist (kein Ueberschreiben des Storage vor dem obigen Laden).
  useEffect(() => {
    if (!settingsLoaded.current) return;
    saveAiSettings(() => localStorage, { picks: aiPicks, timeout: aiTimeout });
  }, [aiPicks, aiTimeout]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const msg = buildStartGame(spectate, humanRef, aiRefs, aiPicks, aiTimeout);
  const ready = msg !== undefined;

  const toggleSpectate = (on: boolean) => {
    setShownError(undefined);
    setSpectate(on);
    // Zuschauer-Modus braucht mindestens 2 KIs (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
    if (on && ais.length < 2) {
      setAis([...ais, EMPTY]);
      setAiPicks([...aiPicks, loadedPicks.current[ais.length] ?? DEFAULT_AI]);
    }
  };
  const editHuman = (n: Pick) => {
    setShownError(undefined);
    setHuman(n);
  };
  const editAi = (i: number, n: Pick) => {
    setShownError(undefined);
    setAis(ais.map((x, j) => (j === i ? n : x)));
  };
  const editAiPick = (i: number, n: AiPick) => {
    setAiPicks(aiPicks.map((x, j) => (j === i ? n : x)));
  };
  const addAi = () => {
    setShownError(undefined);
    setAis([...ais, EMPTY]);
    setAiPicks([...aiPicks, loadedPicks.current[ais.length] ?? DEFAULT_AI]);
  };
  const removeAi = (i: number) => {
    setShownError(undefined);
    setAis(ais.filter((_, j) => j !== i));
    setAiPicks(aiPicks.filter((_, j) => j !== i));
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
        <option value="archidekt">Archidekt-URL</option>
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
      {p.kind === "archidekt" && (
        <div className="textdeck">
          <input placeholder="https://archidekt.com/decks/12345/…" value={p.value} onChange={(e) => onChange({ ...p, value: e.target.value })} />
          <input placeholder="Name (optional, sonst Archidekt-Deckname)" value={p.name} onChange={(e) => onChange({ ...p, name: e.target.value })} />
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
        <section className="lobby-section">
          <label>KI-Bedenkzeit</label>
          <div className="ai-timeout">
            <input
              type="number"
              min={1}
              max={60}
              value={aiTimeout}
              onChange={(e) => setAiTimeout(Math.min(60, Math.max(1, Number(e.target.value) || 1)))}
            /> s
            <span className="hint">gilt für alle KI-Modi; Simulation nutzt das Budget je Entscheidung</span>
          </div>
        </section>
        {ais.map((a, i) => (
          <section key={i} className="lobby-section">
            <label>KI {i + 1} {ais.length > minAis && (
              <button className="quiet small" title="Gegner entfernen" onClick={() => removeAi(i)}>entfernen</button>
            )}</label>
            {picker(a, (n) => editAi(i, n))}
            <div className="ai-pick">
              <select
                title="Standard: Forges Regel-KI. Hybrid: simuliert nur die Zauberwahl. Simulation: rechnet Züge vor – stärker, braucht je Entscheidung bis zur vollen Bedenkzeit"
                value={aiPicks[i]?.mode ?? DEFAULT_AI.mode}
                onChange={(e) => editAiPick(i, { ...(aiPicks[i] ?? DEFAULT_AI), mode: e.target.value as AiPick["mode"] })}
              >
                <option value="standard">Standard</option>
                <option value="hybrid">Hybrid</option>
                <option value="sim">Simulation</option>
              </select>
              <select
                value={aiPicks[i]?.profile ?? DEFAULT_AI.profile}
                onChange={(e) => editAiPick(i, { ...(aiPicks[i] ?? DEFAULT_AI), profile: e.target.value })}
              >
                {aiProfiles.map((p) => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
          </section>
        ))}
        {ais.length < maxAis && (
          <button className="ghost" onClick={addAi}>+ Gegner hinzufügen</button>
        )}
        <button className="primary big" disabled={!ready} onClick={start}>Spiel starten</button>
        {shownError && <pre className="import-error">{shownError}</pre>}
      </div>
    </div>
  );
}
