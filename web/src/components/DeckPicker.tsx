import { useEffect, useMemo, useRef, useState } from "react";
import type { ArchidektEntry, DeckInfo } from "../protocol";
import type { Pick } from "../deckref";
import { filterDecks } from "../deckSearch";
import { classify, defaultSelection, updateAllIds } from "../archidektPlan";
import { loadArchidektUser, saveArchidektUser } from "../archidektSettings";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

type Tab = "precons" | "saved" | "import" | "archidekt";
const tabOf = (kind: Pick["kind"]): Tab => (kind === "saved" ? "saved" : kind === "precon" ? "precons" : "import");
/** Import-Formular zeigt nur einen text-/archidekt-Pick; bei precon/saved startet es leer (der Deckname gehoert nicht ins Feld). */
const draftOf = (pick: Pick): Pick => (pick.kind === "text" || pick.kind === "archidekt" ? pick : { kind: "text", value: "", name: "" });
const SRC_LABEL: Record<Pick["kind"], string> = { precon: "Precon", saved: "Eigenes Deck", text: "Textliste", archidekt: "Archidekt" };
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
  const [syncing, setSyncing] = useState<string>();          // Deckname, dessen Resync laeuft
  const [status, setStatus] = useState<{ text: string; warn: boolean }>(); // Ergebnis des letzten Resync (Reiter "Eigene Decks")
  const searchRef = useRef<HTMLInputElement>(null);
  // Reiter "Archidekt": Benutzername (zuletzt geladener aus dem Store, sonst der gemerkte aus localStorage) und die
  // angehakten Ids. Die Liste selbst liegt im Store (ad.decks); bei einer neuen Liste werden alle "geändert" vorbelegt.
  const [username, setUsername] = useState(() => ad.username ?? loadArchidektUser(() => localStorage));
  const [selected, setSelected] = useState<Set<number>>(() => new Set(ad.decks ? defaultSelection(ad.decks, decks) : []));
  const loadingRef = useRef(ad.loading);
  const chosen: DeckInfo | undefined = pick.kind === "precon" ? precons.find((d) => d.name === pick.value)
    : pick.kind === "saved" ? decks.find((d) => d.name === pick.value) : undefined;
  useEffect(() => {
    if (!open) return;
    setTab(tabOf(pick.kind));
    setQuery("");
    setDraft(draftOf(pick));
    setSyncing(undefined);
    setStatus(undefined);
    searchRef.current?.focus();
  }, [open]);
  useEffect(() => { setStatus(undefined); }, [tab]);
  // "synchronisiert …" endet mit der naechsten lobby-Nachricht (decks) oder einem Fehler der Bridge (letzte
  // Log-Zeile mit warn - ein fehlgeschlagener Resync schickt nur "error", decks bleibt gleich). Das Ergebnis
  // steht danach als Statuszeile unter dem Raster, damit man es nicht im Log suchen muss.
  useEffect(() => {
    if (syncing === undefined) return;
    setSyncing(undefined);
    setStatus({ text: "Deck aktualisiert.", warn: false });
  }, [decks]);
  useEffect(() => {
    const last = log[log.length - 1];
    if (!last?.warn || syncing === undefined) return;
    setSyncing(undefined);
    setStatus({ text: last.text, warn: true });
  }, [log]);
  // Neue Archidekt-Liste: Auswahl vorbelegen, Eingabefeld auf das geladene Konto, alte Statuszeile weg.
  useEffect(() => {
    if (!ad.decks) return;
    setSelected(new Set(defaultSelection(ad.decks, decks)));
    if (ad.username) setUsername(ad.username);
    setStatus(undefined);
  }, [ad.decks]);
  // Ein "error" der Bridge beendet ad.loading, ohne eine Liste zu liefern (Reducer setzt loading=false und haengt die
  // Log-Zeile im selben Update an): steht dann eine Warnung als letzte Log-Zeile, ist das die Antwort auf "Decks laden".
  useEffect(() => {
    const ended = loadingRef.current && !ad.loading;
    loadingRef.current = ad.loading;
    if (!ended) return;
    const last = log[log.length - 1];
    if (last?.warn && tab === "archidekt") setStatus({ text: last.text, warn: true });
  }, [ad.loading, log]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);
  const list = useMemo(() => filterDecks(tab === "precons" ? precons : decks, query), [tab, precons, decks, query]);
  const progress = ad.progress;
  const importing = progress != null && progress.current != null;   // laufender archidektImport
  const chosenIds = useMemo(() => (ad.decks ?? []).filter((e) => selected.has(e.id)).map((e) => e.id), [ad.decks, selected]);
  const updateIds = useMemo(() => updateAllIds(ad.decks ?? [], decks), [ad.decks, decks]);
  const canLoad = username.trim() !== "" && !ad.loading;
  const loadList = () => {
    if (!canLoad) return;
    const name = username.trim();
    setStatus(undefined);
    saveArchidektUser(() => localStorage, name);
    requestArchidektList(name);
  };
  const toggle = (id: number) => setSelected((prev) => {
    const next = new Set(prev);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });
  const listKind: Pick["kind"] = tab === "precons" ? "precon" : "saved";
  const choose = (d: DeckInfo) => { onChange({ kind: listKind, value: d.name, name: "" }); setOpen(false); };
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
                  <input placeholder="Archidekt-Benutzername" value={username} onChange={(e) => setUsername(e.target.value)}
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
                  <div key={d.name} className={"deck-card" + (pick.kind === listKind && chosen?.name === d.name ? " on" : "")}>
                    <button type="button" className="deck-card-main" onClick={() => choose(d)}>
                      <Art commanders={d.commanders} big />
                      <span className="deck-card-name">{d.name}</span>
                      <span className="deck-card-cmd">{d.commanders.map((c) => c.name).join(" / ")}</span>
                    </button>
                    {tab === "saved" && d.archidekt && (
                      <button type="button" className="resync" title={"Neu von Archidekt laden (" + d.archidekt + ")"} disabled={syncing === d.name}
                        onClick={() => { setSyncing(d.name); send({ type: "resyncDeck", name: d.name }); }}>
                        {syncing === d.name ? "synchronisiert …" : "↻ Resync"}
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
                        <span className={"state-badge " + state}>{state}</span>
                      </div>
                    );
                  })}
                </div>
                <div className="deck-actions">
                  <button type="button" className="primary" disabled={chosenIds.length === 0 || importing}
                    onClick={() => { setStatus(undefined); send({ type: "archidektImport", ids: chosenIds }); }}>
                    Ausgewählte holen ({chosenIds.length})
                  </button>
                  <button type="button" disabled={updateIds.length === 0 || importing} title="Alle schon importierten Decks dieses Kontos neu von Archidekt laden"
                    onClick={() => { setStatus(undefined); send({ type: "archidektImport", ids: updateIds }); }}>
                    Alle aktualisieren
                  </button>
                  {progress && (
                    <span className="deck-status">
                      {importing ? `${progress.done}/${progress.total} · ${progress.current} …` : `${progress.done}/${progress.total} fertig`}
                    </span>
                  )}
                  {status && <span className={"deck-status" + (status.warn ? " warn" : "")}>{status.text}</span>}
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
