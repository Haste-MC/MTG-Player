import { useEffect, useMemo, useState } from "react";
import { deckGames, summarize, type DeckSummary } from "../matchStats";
import type { MatchRecord, MatchSeat } from "../protocol";
import { useStore, type LogEntry } from "../store";
import { send } from "../ws";
import { TrashIcon } from "./DeckPicker";

/** Zwei-Klick-Bestaetigung des Loesch-Knopfs (wie im Deck-Panel): so lange bleibt "Wirklich löschen?" stehen. */
const CONFIRM_MS = 4000;
const SOURCE_LABEL: Record<MatchRecord["source"], string> = { live: "live", spectate: "Zuschauer", sparring: "Sparring" };
/** Forges Player.getOutcome().lossState (siehe Spec §1) auf deutsche Klartexte. */
const LOSS_LABEL: Record<string, string> = {
  Conceded: "aufgegeben", LifeReachedZero: "Leben auf 0", CommanderDamage: "Commander-Schaden",
  Milled: "Bibliothek leer", Poisoned: "Gift", SpellEffect: "Karteneffekt", OpponentWon: "Gegner gewann",
  IntentionalDraw: "Remis vereinbart",
};
/** Fehler der Bridge zu einer Partie ("Partie <id>: …"), im Log mit dem Warn-Zeichen des Stores davor. */
const MATCH_ERROR = /^⚠ Partie /;

const pct = (x: number) => Math.round(x * 100) + " %";
const one = (x: number) => x.toFixed(1).replace(".", ",");
/** Kennzahl mit einer Nachkommastelle, oder "–", wenn keine Partie dazu etwas hergibt (z. B. "Länder bis
 * zum eigenen Zug 5", wenn keine gewertete Partie so lange lief - siehe matchStats.landsTurn). */
const num = (x?: number) => (x != null ? one(x) : "–");
/** Dauer als mm:ss (Minuten laufen ueber 60 weiter - eine lange Partie zeigt lieber 84:10 als 1:24:10). */
const mmss = (ms: number) => {
  const s = Math.round(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
// Feste Locale: die Oberflaeche ist durchgehend deutsch, und der Screenshot-Browser laeuft sonst mit
// en-US (9/10/2026, 8:02 PM).
const stamp = (iso: string) => new Date(iso).toLocaleString("de-DE", { dateStyle: "short", timeStyle: "short" });

/** Sicht einer Listenzeile: der Sitz mit dem ausgewaehlten Deck (bevorzugt der menschliche, wie in
 * matchStats.pickSeat), sonst der menschliche Sitz, sonst der erste. Ergebnis und "Gegner" der Zeile
 * beziehen sich auf diesen Sitz. undefined nur bei einer Partie ganz ohne Sitze. */
function viewSeat(record: MatchRecord, deck?: string): MatchSeat | undefined {
  const forDeck = record.seats.filter((s) => s.deck === deck);
  return forDeck.find((s) => s.human) ?? forDeck[0] ?? record.seats.find((s) => s.human) ?? record.seats[0];
}

/** Screen "Statistik": links die Decks mit gewerteten Partien, rechts die Kennzahlen des gewaehlten Decks
 * (matchStats.summarize), darunter die Partienliste mit "gewertet"-Schalter und Papierkorb. Loeschen und
 * Werten gehen als deleteMatch/setMatchCounted an die Bridge, die mit einer frischen matches-Liste antwortet. */
export default function Stats() {
  const matches = useStore((s) => s.matches);
  const log = useStore((s) => s.log);
  const backToLobby = useStore((s) => s.backToLobby);
  const [pick, setPick] = useState<string>();
  const [onlyCounted, setOnlyCounted] = useState(true);
  const [confirmDelete, setConfirmDelete] = useState<string>();   // Partie-Id, deren Knopf nachfragt
  const [status, setStatus] = useState<string>();                 // Fehler der Bridge zur letzten Aktion

  const decks = useMemo(() => deckGames(matches), [matches]);
  // Kein eigener Effekt fuer die Vorauswahl: faellt das gewaehlte Deck aus der Liste (letzte Partie
  // geloescht oder nicht mehr gewertet), greift wieder die erste Zeile.
  const deck = pick && decks.some((d) => d.deck === pick) ? pick : decks[0]?.deck;
  const summary = useMemo(() => (deck ? summarize(matches, deck) : undefined), [matches, deck]);
  // Neueste zuerst; die Liste zeigt bewusst alle Partien, nicht nur die des gewaehlten Decks: eine nicht
  // gewertete Partie taucht in der Deckliste gar nicht auf und waere sonst nicht mehr erreichbar.
  const rows = useMemo(
    () => [...matches].reverse().filter((m) => !onlyCounted || m.counted),
    [matches, onlyCounted],
  );

  // "Wirklich löschen?" faellt nach CONFIRM_MS oder beim naechsten Klick ausserhalb dieses Knopfs zurueck
  // (der bestaetigende Klick auf den Knopf selbst ist ausgenommen - mousedown kommt vor click).
  useEffect(() => {
    if (confirmDelete === undefined) return;
    const timer = setTimeout(() => setConfirmDelete(undefined), CONFIRM_MS);
    const onDown = (e: MouseEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest("button.delete.confirm")) setConfirmDelete(undefined);
    };
    document.addEventListener("mousedown", onDown);
    return () => { clearTimeout(timer); document.removeEventListener("mousedown", onDown); };
  }, [confirmDelete]);
  // Auf diesem Screen ist kein Log sichtbar - ein abgelehntes Loeschen/Werten muss also hier stehen.
  // Eine erfolgreiche Aktion liefert eine frische matches-Liste und raeumt die Zeile wieder weg (der
  // Effekt darunter laeuft im selben Commit spaeter und gewinnt damit).
  useEffect(() => {
    const last: LogEntry | undefined = log[log.length - 1];
    if (last?.warn && MATCH_ERROR.test(last.text)) setStatus(last.text);
  }, [log]);
  useEffect(() => { setStatus(undefined); }, [matches]);

  const clickDelete = (id: string) => {
    if (confirmDelete === id) {
      setConfirmDelete(undefined);
      send({ type: "deleteMatch", id });
    } else setConfirmDelete(id);
  };

  return (
    <div className="lobby stats-screen">
      <div className="lobby-card stats">
        <div className="lobby-head stats-head">
          <div>
            <h1>Statistik</h1>
            <p className="subtitle">Gewertete Partien je Deck – Bilanz, Kurve, Schwachstellen.</p>
          </div>
          <button className="ghost" onClick={backToLobby}>Zur Lobby</button>
        </div>
        {matches.length === 0 ? (
          <p className="muted empty-stats">Noch keine Partien – spiele eine Runde oder nutze später das Sparring.</p>
        ) : (
          <>
            <div className="stats-main">
              <div className="stat-decks">
                <div className="stat-decks-title">Decks</div>
                {decks.length === 0 && <p className="muted">Keine gewertete Partie – unten wieder werten.</p>}
                {decks.map((d) => (
                  <button key={d.deck} className={"stat-deck" + (d.deck === deck ? " on" : "")} onClick={() => setPick(d.deck)}>
                    <span className="stat-deck-name">{d.deck}</span>
                    <span className="stat-deck-games">{d.games}</span>
                  </button>
                ))}
              </div>
              <div className="stat-right">
                {summary ? <Tiles s={summary} /> : <p className="muted">Kein Deck ausgewählt.</p>}
                {summary && <Tables s={summary} />}
              </div>
            </div>
            <div className="match-list">
              <div className="match-head">
                <span className="match-title">Partien</span>
                <label className="match-filter">
                  <input type="checkbox" checked={onlyCounted} onChange={(e) => setOnlyCounted(e.target.checked)} />
                  nur gewertete
                </label>
                {status && <span className="deck-status warn" aria-live="polite">{status}</span>}
              </div>
              <div className="match-row head">
                <span>Datum</span><span>Quelle</span><span>Deck / Gegner</span><span>Ergebnis</span>
                <span className="num">Züge</span><span className="num">Dauer</span>
                <span className="match-counted">gewertet</span><span />
              </div>
              {rows.length === 0 && (
                <p className="muted">
                  {onlyCounted
                    ? "Keine gewertete Partie – nimm den Haken bei „nur gewertete“ weg, um alle zu sehen."
                    : "Keine Partie in dieser Auswahl."}
                </p>
              )}
              {rows.map((m) => (
                <Row key={m.id} record={m} deck={deck} confirm={confirmDelete === m.id} onDelete={() => clickDelete(m.id)} />
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function Row({ record, deck, confirm, onDelete }: { record: MatchRecord; deck?: string; confirm: boolean; onDelete: () => void }) {
  const seat = viewSeat(record, deck);
  const opponents = record.seats.filter((s) => s !== seat).map((s) => s.deck).join(" · ");
  const result = record.draw ? "Remis" : seat?.winner ? "Sieg" : "Niederlage";
  const resultClass = record.draw ? "draw" : seat?.winner ? "win" : "loss";
  return (
    <div className={"match-row" + (record.counted ? "" : " uncounted")}>
      <span className="match-when">{stamp(record.startedAt)}</span>
      <span><span className={"chip src-" + record.source}>{SOURCE_LABEL[record.source]}</span></span>
      <span className="match-decks">
        <b>{seat?.deck ?? "?"}</b>
        {opponents && <span className="muted"> gegen {opponents}</span>}
      </span>
      <span className={"match-result " + resultClass}>
        {result}
        {!record.counted && record.excludeReason && <span className="chip excl">{record.excludeReason}</span>}
      </span>
      <span className="num">{record.turns}</span>
      <span className="num">{mmss(record.durationMs)}</span>
      <span className="match-counted">
        <input type="checkbox" checked={record.counted} aria-label={"Gewertet: " + stamp(record.startedAt)}
          onChange={() => send({ type: "setMatchCounted", id: record.id, counted: !record.counted })} />
      </span>
      <button className={"delete" + (confirm ? " confirm" : "")} onClick={onDelete}
        aria-label={(confirm ? "Wirklich löschen: " : "Partie löschen: ") + stamp(record.startedAt)}
        title={confirm ? undefined : "Partie löschen"}>
        {confirm ? "Wirklich löschen?" : <TrashIcon />}
      </button>
    </div>
  );
}

/** Kennzahlen aus matchStats.summarize als Kacheln (Wert gross, Label klein, Zusatz darunter). */
function Tiles({ s }: { s: DeckSummary }) {
  return (
    <div className="stat-grid">
      <Tile value={String(s.games)} label="Partien" sub={`${s.wins} S · ${s.losses} N · ${s.draws} R`} />
      <Tile value={pct(s.winRate)} label="Siegquote" sub={`95 %: ${pct(s.ci[0])} – ${pct(s.ci[1])}`} />
      <Tile value={one(s.avgTurns)} label="Ø Züge" />
      <Tile value={mmss(s.avgDurationMs)} label="Ø Dauer (mm:ss)" />
      <Tile value={pct(s.mulliganRate)} label="Partien mit Mulligan" sub={`Ø ${one(s.avgMulligans)}`} />
      <Tile value={num(s.avgLandsTurn3)} label="Ø Länder bis zum eigenen Zug 3" sub={`eigener Zug 5: ${num(s.avgLandsTurn5)}`} />
      <Tile value={pct(s.missedLandDropRate)} label="Partien mit verpasster Landabgabe" sub={`Ø ${one(s.avgMissedLandDrops)}`} />
      <Tile value={one(s.avgSpells)} label="Ø Zauber" sub={`Ø Mana ${one(s.avgSpellMana)}`} />
      <Tile value={num(s.avgCommanderTurn)} label="Ø Zug des 1. Commanders"
        sub={`Ø Steuer ${one(s.avgCommanderTax)}`} />
      <Tile value={one(s.avgDamageDealt)} label="Ø Schaden gemacht" sub={`genommen ${one(s.avgDamageTaken)}`} />
    </div>
  );
}

function Tile({ value, label, sub }: { value: string; label: string; sub?: string }) {
  return (
    <div className="stat-tile">
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
      {sub && <span className="stat-sub">{sub}</span>}
    </div>
  );
}

/** Todesursachen und Gegner-Tabelle (Spec §5) unter den Kacheln. */
function Tables({ s }: { s: DeckSummary }) {
  const losses = Object.entries(s.lossReasons).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  return (
    <div className="stat-tables">
      <section>
        <div className="stat-decks-title">Todesursachen</div>
        {losses.length === 0 && <p className="muted">keine Niederlage</p>}
        {losses.map(([reason, n]) => (
          <div key={reason} className="stat-line"><span>{LOSS_LABEL[reason] ?? reason}</span><span className="num">{n}</span></div>
        ))}
      </section>
      <section>
        <div className="stat-decks-title">Gegner</div>
        {s.opponents.length === 0 && <p className="muted">keine Gegner erfasst</p>}
        {s.opponents.map((o) => (
          <div key={o.deck} className="stat-line">
            <span>{o.deck}</span>
            <span className="num">{o.wins}/{o.games}</span>
          </div>
        ))}
      </section>
    </div>
  );
}
