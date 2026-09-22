import { useEffect, useRef, useState } from "react";
import { type AiSettings, loadAiSettings, restoreSlots, saveAiSettings } from "../aiSettings";
import { EMPTY_PICK, type Pick, toRef } from "../deckref";
import { buildStartGame, DEFAULT_AI } from "../lobbyPayload";
import { dropMissing, loadPicks, savePicks } from "../lobbyPicks";
import type { AiPick } from "../protocol";
import { formatSeries, seriesWinner } from "../series";
import { useStore } from "../store";
import { send } from "../ws";
import DeckPicker from "./DeckPicker";

export default function Lobby() {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const aiProfiles = useStore((s) => s.aiProfiles);
  const series = useStore((s) => s.series);
  const resetSeries = useStore((s) => s.resetSeries);
  const openStats = useStore((s) => s.openStats);
  // true zwischen "Spiel starten" und dem ersten Snapshot (bzw. error) - schuetzt vor einem zweiten startGame.
  const expectNewMatch = useStore((s) => s.expectNewMatch);
  const [human, setHuman] = useState<Pick>(EMPTY_PICK);
  const [ais, setAis] = useState<Pick[]>([EMPTY_PICK]);
  const [aiPicks, setAiPicks] = useState<AiPick[]>([DEFAULT_AI]);
  const [aiTimeout, setAiTimeout] = useState(5);
  const [bestOf, setBestOf] = useState<AiSettings["bestOf"]>(0);
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
    setBestOf(loaded.bestOf);
    // AiSettings.picks.length ist die gespeicherte Slot-Zahl - so viele Slots (geklemmt auf min/max)
    // anlegen, nicht nur die Picks in die aktuell vorhandene (anfangs einzige) Zeile mappen.
    const picks = restoreSlots(loaded.picks, { min: minAis, max: maxAis });
    setAiPicks(picks);
    // Gemerkte Decks je Slot; ein Deck, das die Bridge nicht mehr anbietet, bleibt leer.
    const p = dropMissing(loadPicks(() => localStorage), precons, decks);
    setHuman(p.human);
    setAis(Array.from({ length: picks.length }, (_, i) => p.ais[i] ?? EMPTY_PICK));
  }, [precons, decks, aiProfiles]);

  // Nach dem Laden: verschwindet ein gewaehltes Precon/eigenes Deck aus dem Angebot der Bridge (z. B. geloescht im
  // Deck-Panel), wird der Sitz leer; Text-/Archidekt-Picks bleiben. Ein Vergleich, damit gleiche Picks kein setState
  // (und kein erneutes Speichern) ausloesen.
  useEffect(() => {
    if (!settingsLoaded.current) return;
    const cleaned = dropMissing({ human, ais }, precons, decks);
    if (JSON.stringify(cleaned) !== JSON.stringify({ human, ais })) {
      setHuman(cleaned.human);
      setAis(cleaned.ais);
    }
  }, [precons, decks]);
  // Auswahl merken, sobald sie geladen ist (kein Ueberschreiben des Storage vor dem obigen Laden).
  useEffect(() => {
    if (!settingsLoaded.current) return;
    saveAiSettings(() => localStorage, { picks: aiPicks, timeout: aiTimeout, bestOf });
  }, [aiPicks, aiTimeout, bestOf]);
  useEffect(() => {
    if (!settingsLoaded.current) return;
    savePicks(() => localStorage, { human, ais });
  }, [human, ais]);
  // Der Store braucht bestOf fuer die Serien-Entscheidung im Spielende-Overlay.
  useEffect(() => { useStore.getState().setBestOf(bestOf); }, [bestOf]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  const msg = buildStartGame(spectate, humanRef, aiRefs, aiPicks, aiTimeout);
  const ready = msg !== undefined;
  const seatNames = [...(spectate ? [] : ["Du"]), ...ais.map((_, i) => "KI " + (i + 1))];
  const winner = series ? seriesWinner(series, bestOf) : undefined;

  const toggleSpectate = (on: boolean) => {
    setShownError(undefined);
    setSpectate(on);
    // Zuschauer-Modus braucht mindestens 2 KIs (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
    if (on && ais.length < 2) {
      setAis([...ais, EMPTY_PICK]);
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
    setAis([...ais, EMPTY_PICK]);
    setAiPicks([...aiPicks, loadedPicks.current[ais.length] ?? DEFAULT_AI]);
  };
  const removeAi = (i: number) => {
    setShownError(undefined);
    setAis(ais.filter((_, j) => j !== i));
    setAiPicks(aiPicks.filter((_, j) => j !== i));
  };

  const start = () => {
    setShownError(undefined);
    if (msg && !expectNewMatch) {
      useStore.getState().noteStart(msg);
      send(msg);
    }
  };

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
            <DeckPicker pick={human} onChange={editHuman} label="Dein Deck" />
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
            <label className="series-pick" title="Siege über mehrere Partien mit derselben Deck-Konstellation zählen; „Nochmal spielen“ im Spielende-Overlay setzt die Serie fort">
              Serie
              <select value={bestOf} onChange={(e) => setBestOf(Number(e.target.value) as AiSettings["bestOf"])}>
                <option value={0}>aus</option>
                <option value={3}>Best of 3</option>
                <option value={5}>Best of 5</option>
                <option value={7}>Best of 7</option>
              </select>
            </label>
          </div>
          <span className="hint">Richtwert je Entscheidung, kann bis ~2× überschreiten; gilt für alle KI-Modi, Simulation nutzt das Budget je Entscheidung</span>
        </section>
        {ais.map((a, i) => (
          <section key={i} className="lobby-section">
            <label>KI {i + 1} {ais.length > minAis && (
              <button className="quiet small" title="Gegner entfernen" onClick={() => removeAi(i)}>entfernen</button>
            )}</label>
            <DeckPicker pick={a} onChange={(n) => editAi(i, n)} label={"KI " + (i + 1)} />
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
        <div className="lobby-actions">
          <button className="primary big" disabled={!ready || expectNewMatch} onClick={start}>Spiel starten</button>
          <button className="ghost big" title="Bilanz und Kennzahlen der gespielten Partien" onClick={openStats}>Statistik</button>
        </div>
        {series && series.games > 0 && (
          <div className="hint series-line">
            Serie: {formatSeries(series, seatNames)}{winner && ` – ${winner} hat die Serie gewonnen`}
            <button className="quiet small" onClick={resetSeries}>zurücksetzen</button>
          </div>
        )}
        {shownError && <pre className="import-error">{shownError}</pre>}
      </div>
    </div>
  );
}
