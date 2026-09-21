import { useEffect, useMemo, useRef, useState } from "react";
import type { DeckInfo } from "../protocol";
import type { Pick } from "../deckref";
import { filterDecks } from "../deckSearch";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

type Tab = "precons" | "saved" | "import";
const SRC_LABEL: Record<Pick["kind"], string> = { precon: "Precon", saved: "Eigenes Deck", text: "Textliste", archidekt: "Archidekt" };
const TAB_LABEL: Record<Tab, string> = { precons: "Precons", saved: "Eigene Decks", import: "Import" };

/** Deckauswahl je Sitz: Kachel mit Commander-Art, Klick oeffnet das Panel (Reiter Precons / Eigene Decks / Import). */
export default function DeckPicker({ pick, onChange, label }: { pick: Pick; onChange: (p: Pick) => void; label: string }) {
  const precons = useStore((s) => s.precons);
  const decks = useStore((s) => s.decks);
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<Tab>(pick.kind === "saved" ? "saved" : pick.kind === "precon" ? "precons" : "import");
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<Pick>(pick);           // Import-Formulare
  const [syncing, setSyncing] = useState<string>();          // Deckname, dessen Resync laeuft
  const searchRef = useRef<HTMLInputElement>(null);
  const chosen: DeckInfo | undefined = pick.kind === "precon" ? precons.find((d) => d.name === pick.value)
    : pick.kind === "saved" ? decks.find((d) => d.name === pick.value) : undefined;
  useEffect(() => { if (open) { setQuery(""); setDraft(pick); searchRef.current?.focus(); } }, [open]);
  useEffect(() => { setSyncing(undefined); }, [decks]);      // neue lobby-Nachricht beendet "synchronisiert …"
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOpen(false); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);
  const list = useMemo(() => filterDecks(tab === "precons" ? precons : decks, query), [tab, precons, decks, query]);
  const listKind: Pick["kind"] = tab === "precons" ? "precon" : "saved";
  const choose = (d: DeckInfo) => { onChange({ kind: listKind, value: d.name, name: "" }); setOpen(false); };
  const title = chosen?.name
    ?? (pick.kind === "text" && pick.value.trim() ? (pick.name.trim() || "Textliste")
      : pick.kind === "archidekt" && pick.value.trim() ? (pick.name.trim() || pick.value.trim()) : undefined);
  return (
    <>
      <button type="button" className={"deck-tile" + (title ? "" : " empty")} onClick={() => setOpen(true)} aria-label={label}>
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
              {tab !== "import" && <input ref={searchRef} className="deck-search" placeholder="Suchen (Deck oder Commander)" value={query} onChange={(e) => setQuery(e.target.value)} />}
              <button type="button" className="quiet small" onClick={() => setOpen(false)}>Schließen</button>
            </div>
            {tab !== "import" && (
              <div className="deck-grid">
                {list.length === 0 && <div className="muted">{tab === "saved" && decks.length === 0 ? "Noch keine eigenen Decks – über „Import“ anlegen." : "kein Treffer"}</div>}
                {list.map((d) => (
                  <button key={d.name} type="button" className={"deck-card" + (pick.kind === listKind && chosen?.name === d.name ? " on" : "")}
                    onClick={() => choose(d)}>
                    <Art commanders={d.commanders} big />
                    <span className="deck-card-name">{d.name}</span>
                    <span className="deck-card-cmd">{d.commanders.map((c) => c.name).join(" / ")}</span>
                    {tab === "saved" && d.archidekt && (
                      <span className="resync" role="button" title={"Neu von Archidekt laden (" + d.archidekt + ")"}
                        onClick={(e) => { e.stopPropagation(); setSyncing(d.name); send({ type: "resyncDeck", name: d.name }); }}>
                        {syncing === d.name ? "synchronisiert …" : "↻ Resync"}
                      </span>
                    )}
                  </button>
                ))}
              </div>
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
