import { useEffect, useMemo, useState } from "react";
import { deckGames, formatOf, summarize, type DeckSummary, type Format } from "../matchStats";
import { findings } from "../findings";
import type { DeckInfo, MatchRecord, MatchSeat } from "../protocol";
import { useStore, type LogEntry } from "../store";
import { send } from "../ws";
import { Art, TrashIcon } from "./DeckPicker";
import StatBlocks, { mmss } from "./StatTiles";
import Findings from "./Findings";
import DeckAnalysisPanel from "./DeckAnalysisPanel";
import MatchTimeline from "./MatchTimeline";

/** Zwei-Klick-Bestaetigung des Loesch-Knopfs (wie im Deck-Panel): so lange bleibt "Wirklich löschen?" stehen. */
const CONFIRM_MS = 4000;
const SOURCE_LABEL: Record<MatchRecord["source"], string> = { live: "live", spectate: "Zuschauer", sparring: "Sparring" };
/** Forges Player.getOutcome().lossState (siehe Spec §1) auf deutsche Klartexte. */
const LOSS_LABEL: Record<string, string> = {
  Conceded: "aufgegeben", LifeReachedZero: "Leben auf 0", CommanderDamage: "Commander-Schaden",
  Milled: "Bibliothek leer", Poisoned: "Gift", SpellEffect: "Karteneffekt", OpponentWon: "Gegner gewann",
  IntentionalDraw: "Remis vereinbart",
};
/** Erklaert die Zeile „Deckel" in der Deckliste (MatchRecorder.markTurnCapped) als Tooltip. */
const TURN_CAP_HINT = "Partie wurde nach der maximalen Zugzahl abgebrochen – zählt nicht in die Bilanz.";
/** Fehler der Bridge zu einer Partie ("Partie <id>: …"), im Log mit dem Warn-Zeichen des Stores davor. */
const MATCH_ERROR = /^⚠ Partie /;
/** Die drei Format-Schalter im Kopf. "all" rechnet ueber beide - fuer die Bilanz brauchbar, fuer alles
 * Formatabhaengige mit Vorsicht (im Pod verliert man ueberwiegend, Schaden verteilt sich auf drei Gegner). */
const FORMATS: { id: Format; label: string }[] = [
  { id: "all", label: "Alle" }, { id: "duel", label: "1 vs 1" }, { id: "pod", label: "Pod (3+)" },
];

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

/** Screen "Statistik" (Spec §4), ueber die volle Fensterbreite: Kopf mit Format-Schaltern und dem
 * Erklaerungs-Schalter, links die Decks mit Commander-Bild, rechts die Auffaelligkeiten und die
 * Kennzahlen-Bloecke, unten die Partienliste - eine Zeile laesst sich aufklappen und laedt dann per
 * matchDetail ihre Zeitachse nach.
 *
 * Der Screen rechnet nur; geaendert wird ueber die Bridge (deleteMatch/setMatchCounted), die mit einer
 * frischen matches-Liste antwortet. */
export default function Stats() {
  const matches = useStore((s) => s.matches);
  const matchesTotal = useStore((s) => s.matchesTotal);
  const precons = useStore((s) => s.precons);
  const savedDecks = useStore((s) => s.decks);
  const deckAnalyses = useStore((s) => s.deckAnalyses);
  const pendingAnalysis = useStore((s) => s.pendingAnalysis);
  const matchDetails = useStore((s) => s.matchDetails);
  const pendingMatch = useStore((s) => s.pendingMatch);
  const requestDeckAnalysis = useStore((s) => s.requestDeckAnalysis);
  const requestMatchDetail = useStore((s) => s.requestMatchDetail);
  const log = useStore((s) => s.log);
  const backToLobby = useStore((s) => s.backToLobby);
  const [pick, setPick] = useState<string>();
  const [format, setFormat] = useState<Format>("all");
  const [explain, setExplain] = useState(true);
  const [onlyCounted, setOnlyCounted] = useState(true);
  const [open, setOpen] = useState<string>();                     // aufgeklappte Partie (Id)
  const [confirmDelete, setConfirmDelete] = useState<string>();   // Partie-Id, deren Knopf nachfragt
  const [status, setStatus] = useState<string>();                 // Fehler der Bridge zur letzten Aktion

  // Commander-Bilder kommen aus der Lobby-Liste (Precons + eigene Decks), nicht aus dem Datensatz: eine
  // Partie speichert nur den Decknamen. Ein Deck, das es nicht mehr gibt, bleibt ohne Bild.
  const deckInfo = useMemo(() => {
    const map = new Map<string, DeckInfo>();
    for (const d of [...precons, ...savedDecks]) map.set(d.name, d);
    return map;
  }, [precons, savedDecks]);

  // Partienzahl je Format-Schalter: nur gewertete Partien, denn nur die stehen in den Kennzahlen.
  const formatCounts = useMemo(() => {
    let duel = 0, pod = 0;
    for (const m of matches) {
      if (!m.counted) continue;
      if (formatOf(m) === "duel") duel += 1; else pod += 1;
    }
    return { all: duel + pod, duel, pod };
  }, [matches]);

  const decks = useMemo(() => deckGames(matches, format), [matches, format]);
  // Kein eigener Effekt fuer die Vorauswahl: faellt das gewaehlte Deck aus der Liste (letzte Partie
  // geloescht, nicht mehr gewertet oder anderes Format), greift wieder die erste Zeile.
  const deck = pick && decks.some((d) => d.deck === pick) ? pick : decks[0]?.deck;
  const summary = useMemo(() => (deck ? summarize(matches, deck, format) : undefined), [matches, deck, format]);
  // Bilanz je Deck fuer die Liste links - dieselbe Rechnung wie rechts, nur fuer jedes Deck.
  const deckSummaries = useMemo(() => {
    const map = new Map<string, DeckSummary>();
    for (const d of decks) {
      const s = summarize(matches, d.deck, format);
      if (s) map.set(d.deck, s);
    }
    return map;
  }, [decks, matches, format]);
  const picked = decks.find((d) => d.deck === deck);
  const analysis = deck ? deckAnalyses[deck] : undefined;
  const found = useMemo(() => findings(summary, analysis, format), [summary, analysis, format]);
  // Neueste zuerst; die Liste zeigt bewusst alle Decks, nicht nur das gewaehlte: eine nicht gewertete
  // Partie taucht in der Deckliste gar nicht auf und waere sonst nicht mehr erreichbar. Der
  // Format-Schalter gilt aber auch hier - sonst stuenden oben Duelle und unten Pods.
  const rows = useMemo(
    () => [...matches].reverse()
      .filter((m) => (!onlyCounted || m.counted) && (format === "all" || formatOf(m) === format)),
    [matches, onlyCounted, format],
  );

  // Deckwechsel (oder erster Aufbau): Deckanalyse anfordern - der Store fragt jedes Deck nur einmal.
  useEffect(() => { if (deck) requestDeckAnalysis(deck); }, [deck, requestDeckAnalysis]);
  // Aufgeklappte Partie: Zeitachse nachladen (die Liste traegt sie nicht). Bewusst im Klick und nicht
  // in einem Effekt: so steht pendingMatch schon im selben Commit, in dem die Zeile aufgeht - sonst
  // zeigte sie fuer einen Frame die Fehlermeldung ("nicht geladen"), bevor die Anfrage ueberhaupt raus ist.
  const toggleRow = (id: string) => {
    if (open === id) { setOpen(undefined); return; }
    setOpen(id);
    requestMatchDetail(id);
  };

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
    <div className="statboard">
      <header className="sb-head">
        <div className="sb-title">
          <h1>Statistik</h1>
          <p className="subtitle">
            Was das Deck tatsächlich tut – getrennt nach 1 vs 1 und Pod.
            {matchesTotal > matches.length && ` Von ${matchesTotal} gespeicherten Partien liegen die letzten ${matches.length} vor.`}
          </p>
        </div>
        <div className="sb-controls">
          <div className="sb-chips" role="group" aria-label="Format">
            {FORMATS.map((f) => (
              <button key={f.id} className={"sb-chip" + (f.id === format ? " on" : "")}
                aria-pressed={f.id === format} onClick={() => setFormat(f.id)}>
                {f.label}<span className="sb-chip-n">{formatCounts[f.id]}</span>
              </button>
            ))}
          </div>
          <label className="sb-explain">
            <input type="checkbox" checked={explain} onChange={(e) => setExplain(e.target.checked)} />
            Erklärungen
          </label>
          <button className="ghost" onClick={backToLobby}>Zur Lobby</button>
        </div>
      </header>

      {matches.length === 0 ? (
        <p className="muted empty-stats">Noch keine Partien – spiele eine Runde oder nutze später das Sparring.</p>
      ) : (
        <>
          <div className="sb-body">
            <aside className="sb-decks">
              <div className="sb-side-title">Decks</div>
              {decks.length === 0 && (
                <p className="muted">
                  Keine gewertete Partie in diesem Format – wähle oben „Alle“ oder werte unten eine Partie wieder.
                </p>
              )}
              {decks.map((d) => {
                const info = deckInfo.get(d.deck);
                const s = deckSummaries.get(d.deck);
                const commanders = info?.commanders.map((c) => c.name).join(" & ");
                return (
                  <button key={d.deck} className={"sb-deck" + (d.deck === deck ? " on" : "")} onClick={() => setPick(d.deck)}>
                    <Art commanders={info?.commanders ?? []} />
                    <span className="sb-deck-text">
                      <span className="sb-deck-name">{commanders || d.deck}</span>
                      {commanders && <span className="sb-deck-sub">{d.deck}</span>}
                      <span className="sb-deck-record">
                        {s ? `${s.wins} S · ${s.losses} N${s.draws > 0 ? ` · ${s.draws} R` : ""}` : "keine Bilanz"}
                        {d.capped > 0 && <span className="sb-deck-capped" title={TURN_CAP_HINT}> · {d.capped} Deckel</span>}
                      </span>
                    </span>
                    <span className="sb-deck-games" title="gewertete Partien">{d.games}</span>
                  </button>
                );
              })}
            </aside>

            <main className="sb-panels">
              {summary && deck ? (
                <>
                  <Findings findings={found} analyzed={analysis !== undefined} />
                  <StatBlocks s={summary} format={format} explain={explain} />
                  <DeckAnalysisPanel deck={deck} analysis={analysis} explain={explain}
                    pending={pendingAnalysis.includes(deck)} />
                  <Opponents s={summary} deckInfo={deckInfo} explain={explain} />
                </>
              ) : picked && picked.capped > 0 ? (
                <OnlyCapped capped={picked.capped} />
              ) : (
                <p className="muted">Kein Deck ausgewählt.</p>
              )}
            </main>
          </div>

          <div className="match-list">
            <div className="match-head">
              <span className="sb-side-title">Partien</span>
              <label className="match-filter">
                <input type="checkbox" checked={onlyCounted} onChange={(e) => setOnlyCounted(e.target.checked)} />
                nur gewertete
              </label>
              <span className="match-hint">Pfeil links: Zeitachse der Partie</span>
              {status && <span className="deck-status warn" aria-live="polite">{status}</span>}
            </div>
            <div className="match-row head">
              <span />
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
              <Row key={m.id} record={m} deck={deck} confirm={confirmDelete === m.id}
                onDelete={() => clickDelete(m.id)} open={open === m.id}
                onToggle={() => toggleRow(m.id)}
                detail={matchDetails[m.id]} pending={pendingMatch.includes(m.id)} />
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function Row(
  { record, deck, confirm, onDelete, open, onToggle, detail, pending }:
  {
    record: MatchRecord; deck?: string; confirm: boolean; onDelete: () => void;
    open: boolean; onToggle: () => void; detail?: MatchRecord; pending: boolean;
  },
) {
  const seat = viewSeat(record, deck);
  const opponents = record.seats.filter((s) => s !== seat).map((s) => s.deck).join(" · ");
  const result = record.draw ? "Remis" : seat?.winner ? "Sieg" : "Niederlage";
  const resultClass = record.draw ? "draw" : seat?.winner ? "win" : "loss";
  return (
    // data-match traegt die Partie-Id: der Screenshot-Automat (scripts/shot-stats.mjs) klappt darueber
    // genau die Partie auf, zu der er anschliessend das Detail einspielt.
    <div className={"match-entry" + (open ? " open" : "")} data-match={record.id}>
      <div className={"match-row" + (record.counted ? "" : " uncounted")}>
        <button className="match-toggle" onClick={onToggle} aria-expanded={open}
          aria-label={(open ? "Zeitachse schließen: " : "Zeitachse öffnen: ") + stamp(record.startedAt)}>
          <svg className="icon" viewBox="0 0 12 12" width="11" height="11" aria-hidden="true">
            <path d="M4 2.5 8 6l-4 3.5" fill="none" stroke="currentColor" strokeWidth="1.6"
              strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <span className="match-when">{stamp(record.startedAt)}</span>
        <span className="match-source">
          <span className={"chip src-" + record.source}>{SOURCE_LABEL[record.source]}</span>
          {/* Bedenkzeit der Partie, sofern der Datensatz sie kennt (aeltere haben sie nicht) - dezent
              hinter der Quelle, weil sie erklaert, warum eine Partie lange lief. */}
          {record.aiTimeout != null && (
            <span className="chip timeout" title="KI-Bedenkzeit je Entscheidung">{record.aiTimeout} s</span>
          )}
        </span>
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
      {open && (
        <div className="match-detail">
          {/* Weder Detail noch offene Anfrage heisst: die Bridge hat mit "error" geantwortet (der Store
              raeumt pendingMatch dabei leer) - sonst stuende hier fuer immer "wird geladen …". Der
              Wortlaut des Fehlers steht oben ueber der Liste (status). */}
          {detail
            ? <MatchTimeline record={detail} />
            : pending
            ? <p className="muted">Zeitachse wird geladen …</p>
            : <p className="muted warn">Zeitachse nicht geladen – zum erneuten Versuch zu- und wieder aufklappen.</p>}
        </div>
      )}
    </div>
  );
}

/** Statt der Bloecke fuer ein Deck, das nur am Zugdeckel endet: summarize hat dafuer keine Bilanz
 * (undefined), die Partien stehen aber unten in der Liste, sobald der Filter "nur gewertete" aus ist. */
function OnlyCapped({ capped }: { capped: number }) {
  return (
    <section className="sb-block">
      <div className="sb-block-head"><h2>Keine gewertete Partie</h2></div>
      <p className="muted">
        {capped} von {capped} Partien liefen in den Zugdeckel – das Deck beendet die Partie nicht.
      </p>
    </section>
  );
}

/** Block 7 (Gegner, mit Commander-Bild) und die Todesursachen daneben - beides Listen statt Kacheln. */
function Opponents({ s, deckInfo, explain }: { s: DeckSummary; deckInfo: Map<string, DeckInfo>; explain: boolean }) {
  const losses = Object.entries(s.lossReasons).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  return (
    <section className="sb-block">
      <div className="sb-block-head">
        <h2>Gegner</h2>
        {explain && <p className="sb-block-note">Jedes gegnerische Deck zählt je Partie einmal – im Pod also mehrere je Partie.</p>}
      </div>
      <div className="sb-two">
        <div className="opp-list">
          {s.opponents.length === 0 && <p className="muted">keine Gegner erfasst</p>}
          {s.opponents.map((o) => {
            const info = deckInfo.get(o.deck);
            return (
              <div key={o.deck} className="opp">
                <Art commanders={info?.commanders ?? []} />
                <span className="opp-text">
                  <span className="opp-name">{o.deck}</span>
                  <span className="opp-sub">{o.games} {o.games === 1 ? "Partie" : "Partien"}</span>
                </span>
                <span className="opp-record">{o.wins} S · {o.games - o.wins} N</span>
              </div>
            );
          })}
        </div>
        <div className="loss-list">
          <div className="da-title">Todesursachen</div>
          {losses.length === 0 && <p className="muted">keine Niederlage</p>}
          {losses.map(([reason, n]) => (
            <div key={reason} className="stat-line">
              <span>{LOSS_LABEL[reason] ?? reason}</span><span className="num">{n}</span>
            </div>
          ))}
          {explain && losses.length > 0 && (
            <p className="sb-block-note">Forges Grund für dein Ausscheiden – „aufgegeben“ ist dabei deine eigene Entscheidung.</p>
          )}
        </div>
      </div>
    </section>
  );
}
