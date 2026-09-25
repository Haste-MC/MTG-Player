import { useState } from "react";
import { roleGaps, type Finding, type Role } from "../findings";
import type { CardSuggestion, DeckAnalysis } from "../protocol";
import { suggestFreshness } from "../suggestFreshness";
import { useStore } from "../store";
import { send } from "../ws";
import CardImage from "./CardImage";

// Kartenvorschlaege (Stueck 2, Spec §8): steht direkt unter den Auffaelligkeiten desselben Decks und
// bekommt dieselben Daten (analysis/found), aus denen roleGaps() errechnet, welche Rollen dem Deck am
// ehesten fehlen. Der Abruf startet NICHT beim Oeffnen - erst ein Klick schickt suggestCards. Alles
// andere waere eine Ueberraschung: ein Netzabruf zu EDHREC nur, weil jemand seine Statistik durchsieht.

/** Deutsche Namen der neun Rollen (dieselbe Reihenfolge wie in findings.ts/DeckAnalysisPanel.tsx, damit
 *  eine Gruppe immer an derselben Stelle steht, egal welche Rollen die Antwort tatsaechlich traegt). */
const ROLE_ORDER: Role[] = [
  "ramp", "draw", "removal", "wipes", "counters", "flyerDefense", "wipeProtection", "recursion", "tutors",
];
const ROLE_LABEL: Record<Role, string> = {
  ramp: "Ramp", draw: "Kartenzug", removal: "Entfernung", wipes: "Massenentfernung", counters: "Gegenzauber",
  flyerDefense: "Antwort auf Flieger", wipeProtection: "Schutz vor Massenentfernung", recursion: "Rückholer",
  tutors: "Suchkarten",
};

/** Anteil als ganze Prozent ("68 %") - dieselbe Rundung wie in findings.ts/StatTiles.tsx, aber lokal:
 *  share ist EDHREC-spezifisch und kommt sonst nirgends im Board vor. */
const pct = (x: number) => Math.round(x * 100) + " %";

/** Deck-Panel-Konstante fuer den Datenweitergabe-Satz (Spec §8) - fest am Abschnitt, keine Tooltip: wer
 *  klickt, soll vorher lesen koennen, was rausgeht. */
const PRIVACY_NOTE = "Für die Vorschläge fragt die App EDHREC nach deinem Commander – übertragen wird nur sein Name.";

export default function Suggestions({ deck, analysis, found }: { deck: string; analysis?: DeckAnalysis; found: Finding[] }) {
  const msg = useStore((s) => s.suggestions[deck]);
  // Der Store kennt fuer suggestCards keinen eigenen Ladezustand (anders als pendingAnalysis/pendingMatch
  // - siehe store.ts). pendingFor merkt hier nur, FUER WELCHES Deck zuletzt geklickt wurde: kommt eine
  // Antwort, ersetzt msg den Knopf ohnehin; wechselt man das Deck, passt pendingFor nicht mehr zum neuen
  // deck und der Knopf faengt dort wieder frisch an. Bleibt die Bridge die Antwort schuldig, haengt der
  // Knopf bei "lädt …" - ein bekannter, in Kauf genommener Randfall wie beim Sparring-Start.
  const [pendingFor, setPendingFor] = useState<string>();
  const roles = roleGaps(analysis, found);
  const loading = pendingFor === deck && !msg;
  // Nur bei Quelle "edhrec" gibt es ueberhaupt ein Abrufdatum (msg.fetched fehlt bei "db") - new Date()
  // hier statt einer Konstante: der Screenshot-Testlauf schickt Fixtures mit echten, im Verhaeltnis zum
  // jeweiligen "jetzt" alten Zeitpunkten, die Veraltet-Marke muss also gegen die aktuelle Uhrzeit pruefen.
  const stand = msg && msg.source === "edhrec" ? suggestFreshness(msg.fetched, new Date()) : undefined;

  const click = () => {
    setPendingFor(deck);
    send({ type: "suggestCards", deck, roles });
  };

  return (
    <section className="sb-block suggestions">
      <div className="sb-block-head">
        <h2>Kartenvorschläge{msg && <span className="suggest-count">{msg.suggestions.length}</span>}</h2>
        <p className="sb-block-note">{PRIVACY_NOTE}</p>
      </div>
      {msg ? (
        <>
          <p className="suggest-source">
            Quelle: {msg.source === "edhrec" ? "EDHREC" : "Kartendatenbank"}
            {stand && <span className="suggest-stand"> · Stand {stand.label}</span>}
            {stand?.stale && <span className="suggest-stale"> · veraltet</span>}
            {msg.note && <span className="suggest-note"> · {msg.note}</span>}
          </p>
          {ROLE_ORDER.filter((role) => msg.suggestions.some((c) => c.role === role)).map((role) => (
            <div key={role} className="suggest-group">
              <div className="da-title">{ROLE_LABEL[role]}</div>
              <div className="suggest-rows">
                {msg.suggestions.filter((c) => c.role === role).map((c) => <SuggestRow key={c.name} card={c} />)}
              </div>
            </div>
          ))}
        </>
      ) : roles.length === 0 ? (
        <p className="muted">Keine Lücke gefunden.</p>
      ) : (
        <button className="primary" disabled={loading} onClick={click}>{loading ? "lädt …" : "Vorschläge laden"}</button>
      )}
    </section>
  );
}

/** Eine Karte im Vorschlag: Bild, Name, Manakosten, bei vorhandenem `share` der EDHREC-Anteil, Marke
 *  „Game Changer“ wo gesetzt, rechts der Schnitt - ein Denkanstoss, keine Aufforderung (siehe
 *  CardSuggestion.cut in protocol.ts). `share` fehlt beim Datenbank-Rueckfall (kein EDHREC-Netz): die
 *  Zeile bleibt dann ohne Prozentangabe statt eine leere oder erfundene Zahl zu zeigen. */
function SuggestRow({ card }: { card: CardSuggestion }) {
  return (
    <div className="suggest-row">
      <div className="suggest-thumb">
        {card.imageKey
          ? <CardImage key={card.imageKey} imageKey={card.imageKey} className="art" />
          : <span className="pile-name">{card.name}</span>}
      </div>
      <div className="suggest-body">
        <span className="suggest-name">
          {card.name}
          {card.manaCost && <span className="cost">{card.manaCost}</span>}
          {card.gameChanger && <span className="suggest-gc">Game Changer</span>}
        </span>
        {card.share !== undefined && (
          <span className="suggest-share">{pct(card.share)} der vergleichbaren Decks spielen sie</span>
        )}
      </div>
      {card.cut && <span className="suggest-cut">raus: {card.cut.name} – {card.cut.reason}</span>}
    </div>
  );
}
