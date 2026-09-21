import { useEffect, useMemo, useRef, useState } from "react";
import type { ArchidektEntry, DeckInfo } from "../protocol";
import type { Pick } from "../deckref";
import { filterDecks } from "../deckSearch";
import { classify, defaultSelection, progressLabel, updateAllIds, type EntryState } from "../archidektPlan";
import { loadArchidektUser, saveArchidektUser } from "../archidektSettings";
import { useStore, type LogEntry } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

type Tab = "precons" | "saved" | "import" | "archidekt";
const tabOf = (kind: Pick["kind"]): Tab => (kind === "saved" ? "saved" : kind === "precon" ? "precons" : "import");
/** Import-Formular zeigt nur einen text-/archidekt-Pick; bei precon/saved startet es leer (der Deckname gehoert nicht ins Feld). */
const draftOf = (pick: Pick): Pick => (pick.kind === "text" || pick.kind === "archidekt" ? pick : { kind: "text", value: "", name: "" });
const SRC_LABEL: Record<Pick["kind"], string> = { precon: "Precon", saved: "Eigenes Deck", text: "Textliste", archidekt: "Archidekt" };
/** CSS-Klasse je Zustandsmarke (ASCII, damit kein Umlaut im Selektor steht). */
const STATE_CLASS: Record<EntryState, string> = { neu: "neu", aktuell: "aktuell", "geändert": "geaendert", "übernehmen": "uebernehmen" };
const STATE_TITLE: Partial<Record<EntryState, string>> = { "übernehmen": "ersetzt das gleichnamige lokale Deck" };
/** Laufender Vorgang auf einem eigenen Deck (Reiter "Eigene Decks"): Resync oder Loeschen, mit dem Deckname. */
type DeckOp = { kind: "resync" | "delete"; name: string };
const OP_DONE: Record<DeckOp["kind"], string> = { resync: "Deck aktualisiert.", delete: "Deck gelöscht." };
/** Zwei-Klick-Bestaetigung des Loesch-Knopfs: so lange bleibt "Wirklich löschen?" stehen, wenn nichts anderes passiert. */
const CONFIRM_MS = 4000;
/** Markierung "Stand des Logs beim Klick": die damals letzte Zeile (null bei leerem Log). */
type LogMark = { last: LogEntry | null };
const markOf = (log: LogEntry[]): LogMark => ({ last: log[log.length - 1] ?? null });
/** Text der juengsten Warn-Zeile, die nach der Markierung ins Log kam, sonst undefined. Ist die markierte Zeile schon
 * aus dem (gekuerzten) Log gefallen, zaehlt das ganze Log - passiert nur, wenn waehrend des Vorgangs LOG_MAX Zeilen kamen. */
const lastWarnSince = (log: LogEntry[], mark: LogMark): string | undefined => {
  const from = mark.last ? log.lastIndexOf(mark.last) + 1 : 0;
  return log.slice(from).filter((l) => l.warn).pop()?.text;
};
const TAB_LABEL: Record<Tab, string> = { precons: "Precons", saved: "Eigene Decks", import: "Import", archidekt: "Archidekt" };

/** Deckauswahl je Sitz: Kachel mit Commander-Art, Klick oeffnet das Panel (Reiter Precons / Eigene Decks / Import /
 * Archidekt). Der Archidekt-Reiter holt die oeffentliche Deckliste eines Kontos und importiert/aktualisiert die
 * angehakten Decks (Liste und Fortschritt liegen im Store, damit sie beim Schliessen des Panels nicht verloren gehen). */
export default function DeckPicker({ pick, onChange, label }: { pick: Pick; onChange: (p: Pick) => void; label: string }) {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const log = useStore((s) => s.log);
  const ad = useStore((s) => s.archidekt);
  const requestArchidektList = useStore((s) => s.requestArchidektList);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<Tab>(tabOf(pick.kind));
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<Pick>(draftOf(pick)); // Import-Formulare
  const [op, setOp] = useState<DeckOp>();                    // laufender Resync/Loeschvorgang (ein Deck)
  const [confirmDelete, setConfirmDelete] = useState<string>(); // Deckname, dessen Loesch-Knopf "Wirklich löschen?" zeigt
  const [status, setStatus] = useState<{ text: string; warn: boolean }>(); // Ergebnis des letzten Resync/Loeschens (Reiter "Eigene Decks")
  const [adStatus, setAdStatus] = useState<{ text: string; warn: boolean }>(); // Fehler des letzten Ladens/Imports (Reiter "Archidekt")
  const searchRef = useRef<HTMLInputElement>(null);
  // Reiter "Archidekt": Benutzername (zuletzt geladener aus dem Store, sonst der gemerkte aus localStorage) und die
  // angehakten Ids. Die Liste selbst liegt im Store (ad.decks); bei einer neuen Liste werden alle "geändert" vorbelegt.
  const [username, setUsername] = useState(() => ad.username ?? loadArchidektUser(() => localStorage));
  const [selected, setSelected] = useState<Set<number>>(() => new Set(ad.decks ? defaultSelection(ad.decks, decks) : []));
  const loadingRef = useRef(ad.loading);
  // Letzte Log-Zeile beim Klick auf "Decks laden" bzw. "Ausgewählte holen"/"Alle aktualisieren": nur Warnungen, die danach
  // ins Log kamen, gehoeren zu diesem Vorgang (ein alter Tippfehler-Fehler darf nicht unter einer korrekt geladenen Liste
  // stehen). Die Zeile selbst statt log.length, weil der Store das Log bei LOG_MAX vorn kuerzt und Indizes dann wandern.
  const loadMarkRef = useRef<LogMark>();
  const importMarkRef = useRef<LogMark>();
  // true zwischen dem Klick auf einen Import-Knopf und der ersten Fortschrittsmeldung mit einem laufenden Deck - die Bridge
  // schickt zuerst "0/n" ohne current, das darf weder als "fertig" gelten noch die Knoepfe kurz freigeben.
  const [starting, setStarting] = useState(false);
  const chosen: DeckInfo | undefined = pick.kind === "precon" ? precons.find((d) => d.name === pick.value)
    : pick.kind === "saved" ? decks.find((d) => d.name === pick.value) : undefined;
  useEffect(() => {
    if (!open) return;
    setTab(tabOf(pick.kind));
    setQuery("");
    setDraft(draftOf(pick));
    setOp(undefined);
    setConfirmDelete(undefined);
    setStatus(undefined);
    setAdStatus(undefined);
    searchRef.current?.focus();
  }, [open]);
  useEffect(() => { setConfirmDelete(undefined); setStatus(undefined); setAdStatus(undefined); }, [tab]);
  // "synchronisiert …"/"löscht …" endet mit der naechsten lobby-Nachricht (decks) oder einem Fehler der Bridge (letzte
  // Log-Zeile mit warn - ein fehlgeschlagener Resync/Loeschversuch schickt nur "error", decks bleibt gleich). Das
  // Ergebnis steht danach als Statuszeile unter dem Raster, damit man es nicht im Log suchen muss.
  useEffect(() => {
    if (!op) return;
    setOp(undefined);
    setStatus({ text: OP_DONE[op.kind], warn: false });
  }, [decks]);
  useEffect(() => {
    const last = log[log.length - 1];
    if (!last?.warn || !op) return;
    setOp(undefined);
    setStatus({ text: last.text, warn: true });
  }, [log]);
  // "Wirklich löschen?" faellt nach CONFIRM_MS oder beim naechsten Mausklick ausserhalb dieses Knopfs zurueck (der
  // bestaetigende Klick auf den Knopf selbst ist davon ausgenommen - mousedown kommt vor dem click).
  useEffect(() => {
    if (confirmDelete === undefined) return;
    const timer = setTimeout(() => setConfirmDelete(undefined), CONFIRM_MS);
    const onDown = (e: MouseEvent) => {
      if (!(e.target instanceof Element) || !e.target.closest("button.delete.confirm")) setConfirmDelete(undefined);
    };
    document.addEventListener("mousedown", onDown);
    return () => { clearTimeout(timer); document.removeEventListener("mousedown", onDown); };
  }, [confirmDelete]);
  const progress = ad.progress;
  const importing = starting || (progress != null && progress.current != null);   // laufender archidektImport
  // Ein "error" der Bridge beendet ad.loading, ohne eine Liste zu liefern (Reducer setzt loading=false und haengt die
  // Log-Zeile im selben Update an): eine Warnung, die seit dem Klick auf "Decks laden" ins Log kam, ist die Antwort darauf.
  // Laeuft vor dem Listen-Effekt unten, damit bei Erfolg dessen Leeren der Statuszeile gewinnt.
  useEffect(() => {
    const ended = loadingRef.current && !ad.loading;
    loadingRef.current = ad.loading;
    if (!ended || loadMarkRef.current === undefined) return;
    const warn = lastWarnSince(log, loadMarkRef.current);
    loadMarkRef.current = undefined;
    if (warn) setAdStatus({ text: warn, warn: true });
  }, [ad.loading, log]);
  // Waehrend eines Imports (ab Klick bis zur letzten Fortschrittsmeldung): Fehler der Bridge ("Import läuft noch",
  // Listenfehler) landen als rote Statuszeile im Reiter statt nur im Log; ein solcher Fehler beendet auch "startet …".
  useEffect(() => {
    if (!importing || importMarkRef.current === undefined) return;
    const warn = lastWarnSince(log, importMarkRef.current);
    if (!warn) return;
    setAdStatus({ text: warn, warn: true });
    setStarting(false);
  }, [log, importing]);
  // Erste Fortschrittsmeldung mit laufendem Deck (oder ein sofort fertiger Lauf) beendet den Startzustand.
  useEffect(() => {
    if (progress && (progress.current != null || progress.done === progress.total)) setStarting(false);
  }, [progress]);
  // Neue Archidekt-Liste: Auswahl vorbelegen, Eingabefeld auf das geladene Konto, alte Statuszeile weg (letzter Effekt,
  // damit er die Warnpruefung oben ueberstimmt).
  useEffect(() => {
    if (!ad.decks) return;
    setSelected(new Set(defaultSelection(ad.decks, decks)));
    if (ad.username) setUsername(ad.username);
    setAdStatus(undefined);
  }, [ad.decks]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);
  const list = useMemo(() => filterDecks(tab === "precons" ? precons : decks, query), [tab, precons, decks, query]);
  const chosenIds = useMemo(() => (ad.decks ?? []).filter((e) => selected.has(e.id)).map((e) => e.id), [ad.decks, selected]);
  const updateIds = useMemo(() => updateAllIds(ad.decks ?? [], decks), [ad.decks, decks]);
  const canLoad = username.trim() !== "" && !ad.loading;
  const loadList = () => {
    if (!canLoad) return;
    const name = username.trim();
    setAdStatus(undefined);
    loadMarkRef.current = markOf(log);
    saveArchidektUser(() => localStorage, name);
    requestArchidektList(name);
  };
  const startImport = (ids: number[]) => {
    setAdStatus(undefined);
    importMarkRef.current = markOf(log);
    setStarting(true);
    send({ type: "archidektImport", ids });
  };
  const toggle = (id: number) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const listKind: Pick["kind"] = tab === "precons" ? "precon" : "saved";
  const choose = (d: DeckInfo) => { onChange({ kind: listKind, value: d.name, name: "" }); setOpen(false); };
  const startOp = (kind: DeckOp["kind"], name: string) => {
    setConfirmDelete(undefined);
    setStatus(undefined);
    setOp({ kind, name });
    send({ type: kind === "resync" ? "resyncDeck" : "deleteDeck", name });
  };
  // Erster Klick fragt nach, der zweite (im Bestaetigungszustand) loescht.
  const clickDelete = (name: string) => {
    if (confirmDelete === name) startOp("delete", name);
    else setConfirmDelete(name);
  };
  const title = chosen?.name
    ?? (pick.kind === "text" && pick.value.trim() ? (pick.name.trim() || "Textliste")
      : pick.kind === "archidekt" && pick.value.trim() ? (pick.name.trim() || pick.value.trim()) : undefined);
  return (
    <>
      <button type="button" className={"deck-tile" + (title ? "" : " empty")} onClick={() => setOpen(true)} aria-label={`${label}: ${title ?? "Deck wählen"}`}>
        <Art commanders={chosen?.commanders ?? []} />
        <span className="deck-tile-text">
          <span className="deck-tile-name">{title ?? "Deck wählen"}</span>
          {title && <span className="badge-src">{SRC_LABEL[pick.kind]}</span>}
          {chosen && <span className="deck-tile-cmd">{chosen.commanders.map((c) => c.name).join(" / ")}</span>}
        </span>
      </button>
      {open && (
        <div className="deck-panel-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(false); }}>
          <div className="deck-panel" role="dialog" aria-label={label}>
            <div className="deck-panel-head">
              <div className="deck-tabs">
                {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
                  <button key={t} type="button" className={"tab" + (tab === t ? " on" : "")} onClick={() => setTab(t)}>
                    {TAB_LABEL[t]}
                  </button>
                ))}
              </div>
              {(tab === "precons" || tab === "saved") && <input ref={searchRef} className="deck-search" placeholder="Suchen (Deck oder Commander)" value={query} onChange={(e) => setQuery(e.target.value)} />}
              {tab === "archidekt" && (
                <div className="archidekt-head">
                  <input placeholder="Archidekt-Benutzername" aria-label="Archidekt-Benutzername" value={username} onChange={(e) => setUsername(e.target.value)}
                    onKeyDown={(e) => { if (e.key === "Enter") loadList(); }} />
                  <button type="button" className="primary small" disabled={!canLoad} onClick={loadList}>{ad.loading ? "lädt …" : "Decks laden"}</button>
                </div>
              )}
              <button type="button" className="quiet small" onClick={() => setOpen(false)}>Schließen</button>
            </div>
            {(tab === "precons" || tab === "saved") && (
              <div className="deck-grid">
                {list.length === 0 && <div className="muted">{tab === "saved" && decks.length === 0 ? "Noch keine eigenen Decks – über „Import“ anlegen." : "kein Treffer"}</div>}
                {list.map((d) => (
                  // div statt button, damit der Resync-Knopf ein eigener (per Tastatur erreichbarer) Button sein kann
                  <div key={d.name} className={"deck-card" + (tab === "saved" ? " own" : "") + (pick.kind === listKind && chosen?.name === d.name ? " on" : "")}>
                    <button type="button" className="deck-card-main" onClick={() => choose(d)}>
                      <Art commanders={d.commanders} big />
                      <span className="deck-card-name">{d.name}</span>
                      <span className="deck-card-cmd">{d.commanders.map((c) => c.name).join(" / ")}</span>
                    </button>
                    {tab === "saved" && d.archidekt && (
                      <button type="button" className="resync" title={"Neu von Archidekt laden (" + d.archidekt + ")"} disabled={op?.name === d.name}
                        onClick={() => startOp("resync", d.name)}>
                        {op?.kind === "resync" && op.name === d.name ? "synchronisiert …" : "↻ Resync"}
                      </button>
                    )}
                    {tab === "saved" && (
                      <button type="button" className={"delete" + (confirmDelete === d.name ? " confirm" : "")} aria-label={(confirmDelete === d.name ? "Wirklich löschen: " : "Deck löschen: ") + d.name}
                        title={confirmDelete === d.name ? undefined : "Deck löschen"} disabled={op?.name === d.name} onClick={() => clickDelete(d.name)}>
                        {op?.kind === "delete" && op.name === d.name ? "löscht …" : confirmDelete === d.name ? "Wirklich löschen?" : <TrashIcon />}
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
            {tab === "saved" && status && (
              <div className={"deck-status" + (status.warn ? " warn" : "")}>{status.text}</div>
            )}
            {tab === "archidekt" && (
              <>
                <div className="deck-grid">
                  {!ad.decks && <div className="muted">{ad.loading ? "lädt Deckliste …" : "Benutzername eingeben und „Decks laden“ – die Liste zeigt die öffentlichen Commander-Decks des Kontos."}</div>}
                  {ad.decks && ad.decks.length === 0 && <div className="muted">keine öffentlichen Commander-Decks gefunden</div>}
                  {ad.decks?.map((e) => {
                    const state = classify(e, decks);
                    const on = selected.has(e.id);
                    return (
                      // div mit Hauptknopf (toggelt) und eigener Checkbox daneben - keine verschachtelten Bedienelemente;
                      // die Checkbox toggelt selbst und wird nicht vom Kartenklick doppelt ausgeloest (eigenes Element).
                      <div key={e.id} className={"deck-card" + (on ? " checked" : "")}>
                        <button type="button" className="deck-card-main" onClick={() => toggle(e.id)} aria-pressed={on}>
                          <RemoteArt url={e.art} />
                          <span className="deck-card-name">{e.name}</span>
                        </button>
                        <input type="checkbox" className="deck-check" checked={on} onChange={() => toggle(e.id)} aria-label={"Auswählen: " + e.name} />
                        <span className={"state-badge " + STATE_CLASS[state]} title={STATE_TITLE[state]}>{state}</span>
                      </div>
                    );
                  })}
                </div>
                <div className="deck-actions">
                  <button type="button" className="primary" disabled={chosenIds.length === 0 || importing}
                    onClick={() => startImport(chosenIds)}>
                    Ausgewählte holen ({chosenIds.length})
                  </button>
                  <button type="button" disabled={updateIds.length === 0 || importing} title="Alle schon importierten Decks dieses Kontos neu von Archidekt laden"
                    onClick={() => startImport(updateIds)}>
                    Alle aktualisieren
                  </button>
                  {(starting || progress) && (
                    <span className="deck-status" aria-live="polite">
                      {starting ? "startet …"
                        : progress!.current != null ? `${progress!.done}/${progress!.total} · ${progressLabel(progress!.current, ad.decks)} …`
                        : `${progress!.done}/${progress!.total} fertig`}
                    </span>
                  )}
                  {adStatus && <span className={"deck-status" + (adStatus.warn ? " warn" : "")} aria-live="polite">{adStatus.text}</span>}
                </div>
                {progress && progress.errors.length > 0 && <div className="deck-status warn">{progress.errors.join("\n")}</div>}
                <p className="hint">Nur öffentliche/ungelistete Decks; private sieht Archidekt ohne Login nicht. Importierte Decks erscheinen unter „Eigene Decks“.</p>
              </>
            )}
            {tab === "import" && (
              <div className="deck-import">
                <div>
                  <label><input type="radio" checked={draft.kind !== "archidekt"} onChange={() => setDraft({ kind: "text", value: draft.kind === "text" ? draft.value : "", name: draft.name })} /> Textliste</label>
                  <label><input type="radio" checked={draft.kind === "archidekt"} onChange={() => setDraft({ kind: "archidekt", value: draft.kind === "archidekt" ? draft.value : "", name: draft.name })} /> Archidekt-URL</label>
                </div>
                {draft.kind === "archidekt" ? (
                  <div className="textdeck">
                    <input placeholder="https://archidekt.com/decks/12345/…" value={draft.value} onChange={(e) => setDraft({ ...draft, value: e.target.value })} />
                    <input placeholder="Name (optional, sonst Archidekt-Deckname)" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                  </div>
                ) : (
                  <div className="textdeck">
                    <input placeholder="Name (optional)" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                    <textarea rows={8} placeholder={"Archidekt/Arena-Export einfügen, z. B.\n1 Sol Ring (c21) 263\nCommander\n1 Felothar the Steadfast"} value={draft.value} onChange={(e) => setDraft({ ...draft, value: e.target.value })} />
                  </div>
                )}
                <p className="hint">Beim Spielstart wird das Deck gespeichert und erscheint danach unter „Eigene Decks“ (Archidekt-Decks mit Resync).</p>
                <button type="button" className="primary" disabled={!draft.value.trim()} onClick={() => { onChange({ ...draft, kind: draft.kind === "archidekt" ? "archidekt" : "text" }); setOpen(false); }}>Übernehmen</button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}

/** Papierkorb als Inline-SVG statt Emoji: das Emoji haengt an einer installierten Emoji-Schrift und wird sonst zum
 * Kaestchen (so im headless Chromium der Screenshots). */
function TrashIcon() {
  return (
    <svg className="icon" viewBox="0 0 16 16" width="12" height="12" aria-hidden="true" focusable="false">
      <path fill="currentColor" d="M6 1.5h4l.5 1H14v1.5H2V2.5h3.5l.5-1ZM3 5h10l-.7 9.1A1.5 1.5 0 0 1 10.8 15.5H5.2a1.5 1.5 0 0 1-1.5-1.4L3 5Zm3 2v6h1.3V7H6Zm2.7 0v6H10V7H8.7Z" />
    </svg>
  );
}

/** Vorschaubild einer Archidekt-Deckzeile: die art-URL direkt von Archidekt (ohne Referrer, damit der Bild-Host nicht
 * auf die Herkunft reagiert); fehlt sie oder laedt sie nicht, bleibt die Schraffur. Archidekt liefert schon den
 * Art-Ausschnitt, deshalb kein Zoom wie bei den Kartenbildern (Klasse remote). */
function RemoteArt({ url }: { url: ArchidektEntry["art"] }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [url]);
  const show = !!url && !failed;
  return (
    <span className="deck-art big remote">
      {!show && <span className="deck-art-empty" />}
      {show && <img className="art" src={url} alt="" referrerPolicy="no-referrer" loading="lazy" onError={() => setFailed(true)} />}
    </span>
  );
}

/** Commander-Art einer Kachel: Ausschnitt des Kartenbilds (CSS zoomt auf den Art-Bereich), bei zwei Commandern beide je zur
 * Haelfte nebeneinander; ohne Commander ein Schraffur-Platzhalter. CardImage rendert bei fehlendem imageKey oder 404 nichts,
 * dann bleibt der dunkle Grund. */
function Art({ commanders, big }: { commanders: DeckInfo["commanders"]; big?: boolean }) {
  return (
    <span className={"deck-art" + (big ? " big" : "") + (commanders.length > 1 ? " pair" : "")}>
      {commanders.length === 0 && <span className="deck-art-empty" />}
      {commanders.slice(0, 2).map((c) => (
        <span key={c.imageKey ?? c.name} className="art-slot"><CardImage imageKey={c.imageKey} className="art" /></span>
      ))}
    </span>
  );
}
