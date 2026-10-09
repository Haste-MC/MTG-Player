import { useEffect, useRef, useState } from "react";
import { type AiSettings, loadAiSettings, restoreSlots, saveAiSettings } from "../aiSettings";
import { EMPTY_PICK, type Pick, toRef } from "../deckref";
import { fitTeams, humanHasPartner, lineupProblem, lineupText, NO_TEAM, seatTeams, splitSeats, TEAM_CHOICES, twoVsTwo, withoutSeat } from "../lineup";
import { buildStartGame, DEFAULT_AI } from "../lobbyPayload";
import { dropMissing, loadPicks, savePicks } from "../lobbyPicks";
import type { AiPick } from "../protocol";
import { formatSeries, seriesWinner } from "../series";
import { useStore } from "../store";
import { type UpdateBanner, updateBanner } from "../update";
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
  // Update-Hinweis (Aufgabe 6): version/updateState kommen unveraendert von der Bridge, updateBanner
  // (reine Funktion, siehe update.ts) macht Text und Zustand daraus. updateDismissed lebt im Store statt
  // in lokalem State, weil Lobby beim Wechsel zum Tisch und zurueck neu montiert wird - "Später" soll die
  // ganze Sitzung ueber wirken, nicht nur bis zum naechsten Spiel.
  const version = useStore((s) => s.version);
  const updateState = useStore((s) => s.updateState);
  const updateDismissed = useStore((s) => s.updateDismissed);
  const requestUpdate = useStore((s) => s.requestUpdate);
  const dismissUpdate = useStore((s) => s.dismissUpdate);
  const banner = updateDismissed ? undefined : updateBanner(version, updateState);
  const [human, setHuman] = useState<Pick>(EMPTY_PICK);
  const [ais, setAis] = useState<Pick[]>([EMPTY_PICK]);
  const [aiPicks, setAiPicks] = useState<AiPick[]>([DEFAULT_AI]);
  const [aiTimeout, setAiTimeout] = useState(5);
  const [bestOf, setBestOf] = useState<AiSettings["bestOf"]>(0);
  const [spectate, setSpectate] = useState(false);
  // Teams getrennt vom Sitzplan: die Zahl des Menschen und eine Liste im Gleichschritt mit `ais`. Die
  // Reihenfolge der Nachricht entsteht erst unten (seatTeams) - so bleibt beim Umschalten des
  // Zuschauer-Modus oder beim Hinzufuegen/Entfernen eines Sitzes kein Team fuer einen Sitz uebrig, den es nicht gibt.
  const [humanTeam, setHumanTeam] = useState(NO_TEAM);
  const [aiTeams, setAiTeams] = useState<number[]>([]);
  const [revealPartner, setRevealPartner] = useState(false);
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
    setRevealPartner(loaded.revealPartner);
    // AiSettings.picks.length ist die gespeicherte Slot-Zahl - so viele Slots (geklemmt auf min/max)
    // anlegen, nicht nur die Picks in die aktuell vorhandene (anfangs einzige) Zeile mappen.
    const picks = restoreSlots(loaded.picks, { min: minAis, max: maxAis });
    setAiPicks(picks);
    // Gespeichert ist die Aufstellung mit dem Menschen vorn; die Teams der KI-Sitze folgen der Slot-Zahl.
    setHumanTeam(loaded.teams[0] ?? NO_TEAM);
    setAiTeams(fitTeams(loaded.teams.slice(1), picks.length));
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
    saveAiSettings(() => localStorage, {
      picks: aiPicks, timeout: aiTimeout, bestOf, teams: [humanTeam, ...fitTeams(aiTeams, aiPicks.length)], revealPartner,
    });
  }, [aiPicks, aiTimeout, bestOf, humanTeam, aiTeams, revealPartner]);
  useEffect(() => {
    if (!settingsLoaded.current) return;
    savePicks(() => localStorage, { human, ais });
  }, [human, ais]);
  // Der Store braucht bestOf fuer die Serien-Entscheidung im Spielende-Overlay.
  useEffect(() => { useStore.getState().setBestOf(bestOf); }, [bestOf]);

  const humanRef = toRef(human);
  const aiRefs = ais.map(toRef);
  // Auch hier auf die Sitzzahl zugeschnitten: ein veralteter Eintrag darf nie in die Nachricht gelangen.
  const teams = seatTeams(spectate, humanTeam, fitTeams(aiTeams, ais.length));
  const lineupError = lineupProblem(teams);
  // Eine ungueltige Aufstellung lehnt die Bridge mit einem Fehler ab - dann gar nicht erst senden.
  const msg = lineupError === undefined
    ? buildStartGame(spectate, humanRef, aiRefs, aiPicks, aiTimeout, teams, revealPartner)
    : undefined;
  const ready = msg !== undefined;
  // So heissen die Sitze am Tisch ("You", "AI 1" ...) - in der Lobby nicht umbenennen.
  const seatNames = [...(spectate ? [] : ["You"]), ...ais.map((_, i) => "AI " + (i + 1))];
  const lineup = lineupText(seatNames, teams);
  const quickLineup = twoVsTwo(seatNames.length);
  // Jeder simulierende Sitz rechnet je Entscheidung bis zur vollen Bedenkzeit - ab zwei Sitzen summiert
  // sich das sichtbar (Spec §3). Nur ein Hinweis, kein Zwang und keine Aenderung der Voreinstellung.
  const simSeats = aiPicks.filter((p) => p.mode === "sim").length;
  const winner = series ? seriesWinner(series, bestOf) : undefined;

  const toggleSpectate = (on: boolean) => {
    setShownError(undefined);
    setSpectate(on);
    // Zuschauer-Modus braucht mindestens 2 KIs (Forges Spectator-Pfad, siehe HumanMatch.startSpectator).
    if (on && ais.length < 2) {
      setAis([...ais, EMPTY_PICK]);
      setAiPicks([...aiPicks, loadedPicks.current[ais.length] ?? DEFAULT_AI]);
      setAiTeams([...fitTeams(aiTeams, ais.length), NO_TEAM]);
    }
  };
  const setSeatTeam = (seat: number, team: number) => {
    // seat zaehlt in Sitzreihenfolge der Nachricht; im Zuschauer-Modus gibt es den Menschen nicht.
    const next = splitSeats(spectate, teams.map((t, i) => (i === seat ? team : t)), humanTeam);
    setHumanTeam(next.humanTeam);
    setAiTeams(next.aiTeams);
  };
  const applyQuickLineup = () => {
    if (!quickLineup) return;
    const next = splitSeats(spectate, quickLineup, humanTeam);
    setHumanTeam(next.humanTeam);
    setAiTeams(next.aiTeams);
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
    setAiTeams([...fitTeams(aiTeams, ais.length), NO_TEAM]);
  };
  const removeAi = (i: number) => {
    setShownError(undefined);
    setAis(ais.filter((_, j) => j !== i));
    setAiPicks(aiPicks.filter((_, j) => j !== i));
    setAiTeams(withoutSeat(fitTeams(aiTeams, ais.length), i));
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
        {banner && (
          <UpdateNotice banner={banner} notes={version?.notes} onUpdate={requestUpdate} onLater={dismissUpdate} />
        )}
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
            <div className="seat-deck">
              <DeckPicker pick={human} onChange={editHuman} label="Dein Deck" />
              <TeamPick label="Team von You" value={humanTeam} onChange={(t) => setSeatTeam(0, t)} />
            </div>
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
          {simSeats > 1 && (
            <span className="hint sim-hint">
              <b>{simSeats} Simulations-Sitze</b>: die KIs rechnen reihum – in einer 4er-Runde dauert eine Partie
              schnell 15–30 Minuten, einzelne Züge über eine Minute. Weniger Sim-Sitze oder ein kleineres Budget
              machen es flüssiger.
            </span>
          )}
        </section>
        {ais.map((a, i) => (
          <section key={i} className="lobby-section">
            <label>AI {i + 1} {ais.length > minAis && (
              <button className="quiet small" title="Gegner entfernen" onClick={() => removeAi(i)}>entfernen</button>
            )}</label>
            <div className="seat-deck">
              <DeckPicker pick={a} onChange={(n) => editAi(i, n)} label={"AI " + (i + 1)} />
              <TeamPick label={`Team von AI ${i + 1}`} value={aiTeams[i] ?? NO_TEAM} onChange={(t) => setSeatTeam(spectate ? i : i + 1, t)} />
            </div>
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
        <div className="lineup">
          <div className="lineup-head">
            <button className="quiet small" disabled={!quickLineup}
              title={quickLineup ? "Sitze 1 und 2 gegen Sitze 3 und 4" : "Nur bei genau vier Sitzen"}
              onClick={applyQuickLineup}>2v2</button>
            <button className="quiet small" disabled={teams.every((t) => t === NO_TEAM)}
              title="Alle Teams entfernen – jeder gegen jeden"
              onClick={() => { setHumanTeam(NO_TEAM); setAiTeams(fitTeams([], ais.length)); }}>Teams löschen</button>
            <span className="hint">{lineup || "ohne Teams – jeder gegen jeden"}</span>
          </div>
          {lineupError && <span className="hint warn">{lineupError}</span>}
          {!spectate && humanHasPartner(teams) && (
            <label className="series-pick" title="Du siehst die Handkarten deines Partners">
              <input type="checkbox" checked={revealPartner} onChange={(e) => setRevealPartner(e.target.checked)} />
              Partnerhand zeigen
            </label>
          )}
        </div>
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

/** Team-Auswahl eines Sitzes; "kein Team" ist Jeder gegen jeden. */
function TeamPick({ label, value, onChange }: { label: string; value: number; onChange: (team: number) => void }) {
  return (
    <select className="team-pick" aria-label={label} title="Team des Sitzes; Partner greifen einander nicht an"
      value={value} onChange={(e) => onChange(Number(e.target.value))}>
      <option value={NO_TEAM}>kein Team</option>
      {TEAM_CHOICES.map((t) => <option key={t} value={t}>Team {t}</option>)}
    </select>
  );
}

/** Die Update-Leiste selbst (Aufgabe 6): Knoepfe nur im Zustand "verfuegbar" (Aktualisieren/Später) bzw.
 *  "fehler" (Erneut versuchen, mit dem Grund aus der Bridge); die drei Zwischenzustaende zeigen nur den
 *  Text aus updateBanner - "neustart" ist der letzte, danach reisst die Bridge die Verbindung selbst ab,
 *  ein Knopf waere dort ohnehin folgenlos. `notes` (Release-Text) steht bei "verfuegbar" aufklappbar
 *  darunter, wenn die Bridge einen mitgeschickt hat - nicht dauerhaft sichtbar, damit die Leiste knapp
 *  bleibt (dasselbe Platzprinzip wie der Orakeltext-Tooltip in Suggestions.tsx). */
function UpdateNotice({ banner, notes, onUpdate, onLater }: { banner: UpdateBanner; notes?: string; onUpdate: () => void; onLater: () => void }) {
  const hasNotes = banner.state === "verfuegbar" && !!notes?.trim();
  return (
    <div className={"update-banner" + (banner.state === "fehler" ? " error" : "")}>
      <div className="update-banner-row">
        <span>{banner.text}</span>
        {banner.state === "verfuegbar" && (
          <div className="update-actions">
            <button className="primary small" onClick={onUpdate}>Aktualisieren</button>
            <button className="quiet small" onClick={onLater}>Später</button>
          </div>
        )}
        {banner.state === "fehler" && (
          <button className="primary small" onClick={onUpdate}>Erneut versuchen</button>
        )}
      </div>
      {hasNotes && (
        <details className="update-notes">
          <summary>Was ist neu</summary>
          <pre>{notes}</pre>
        </details>
      )}
    </div>
  );
}
