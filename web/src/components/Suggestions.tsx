import { roleGaps, type Finding, type Role } from "../findings";
import type { CardSuggestion, DeckAnalysis } from "../protocol";
import { suggestFreshness } from "../suggestFreshness";
import { useStore } from "../store";
import CardImage from "./CardImage";

// Kartenvorschlaege (Stueck 2, Spec §8): steht direkt unter den Auffaelligkeiten desselben Decks und
// bekommt dieselben Daten (analysis/found), aus denen roleGaps() errechnet, welche Rollen dem Deck am
// ehesten fehlen. Der Abruf startet NICHT beim Oeffnen - erst ein Klick schickt suggestCards. Alles
// andere waere eine Ueberraschung: ein Netzabruf zu EDHREC nur, weil jemand seine Statistik durchsieht.

/** Deutsche Namen der neun Rollen. */
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

/** Befund 7: eine Antwort mit 0 Vorschlaegen (Farbidentitaet oder Bracket haben alles weggefiltert) zeigt
 *  sonst nur die Ueberschrift - Kevin soll lesen koennen, dass das kein Fehler war. */
const NO_CANDIDATES_NOTE = "Keine passenden Karten gefunden – Farbidentität oder Bracket haben alle Kandidaten herausgefiltert.";

/** Rollen in der Reihenfolge, in der ihre Vorschlaege in `suggestions` zuerst auftauchen (Befund 6): die
 *  Antwort traegt die Rollen schon in der Prioritaet von roleGaps() (belegte Auffaelligkeiten zuerst) -
 *  eine feste Anzeige-Reihenfolge wuerde diese Prioritaet wieder wegsortieren. */
function orderedRoles(suggestions: CardSuggestion[]): Role[] {
  const roles: Role[] = [];
  for (const c of suggestions) {
    const role = c.role as Role;
    if (!roles.includes(role)) roles.push(role);
  }
  return roles;
}

export default function Suggestions({ deck, analysis, found }: { deck: string; analysis?: DeckAnalysis; found: Finding[] }) {
  const msg = useStore((s) => s.suggestions[deck]);
  // Befund 3: pendingSuggestions liegt jetzt im Store, keyed by Deckname (dasselbe Muster wie
  // pendingMatch/pendingAnalysis) - ein error der Bridge (unbekanntes Deck, jede Ausnahme) raeumt den
  // Eintrag dort weg (siehe store.ts case "error"), der Knopf haengt also nie mehr auf "lädt …" fest.
  const pending = useStore((s) => s.pendingSuggestions.includes(deck));
  const requestCardSuggestions = useStore((s) => s.requestCardSuggestions);
  const roles = roleGaps(analysis, found);
  // Nur bei Quelle "edhrec" gibt es ueberhaupt ein Abrufdatum (msg.fetched fehlt bei "db") - new Date()
  // hier statt einer Konstante: der Screenshot-Testlauf schickt Fixtures mit echten, im Verhaeltnis zum
  // jeweiligen "jetzt" alten Zeitpunkten, die Veraltet-Marke muss also gegen die aktuelle Uhrzeit pruefen.
  const stand = msg && msg.source === "edhrec" ? suggestFreshness(msg.fetched, new Date()) : undefined;

  const click = () => requestCardSuggestions(deck, roles);

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
            {/* Befund 3: nach einem Reimport hat sich der Deckinhalt geaendert, ohne dass sich am
               gemerkten Stand etwas ruehrt - "neu laden" fragt bewusst OHNE die sonst uebliche Sperre
               gegen einen schon vorliegenden Stand erneut an (siehe store.requestCardSuggestions). */}
            <button className="quiet small suggest-reload" disabled={pending} onClick={click}>
              {pending ? "lädt …" : "neu laden"}
            </button>
          </p>
          {msg.suggestions.length === 0 ? (
            <p className="muted">{NO_CANDIDATES_NOTE}</p>
          ) : (
            orderedRoles(msg.suggestions).map((role) => (
              <div key={role} className="suggest-group">
                <div className="da-title">{ROLE_LABEL[role] ?? role}</div>
                <div className="suggest-rows">
                  {msg.suggestions.filter((c) => c.role === role).map((c) => <SuggestRow key={c.name} card={c} />)}
                </div>
              </div>
            ))
          )}
        </>
      ) : roles.length === 0 ? (
        <p className="muted">Keine Lücke gefunden.</p>
      ) : (
        <button className="primary" disabled={pending} onClick={click}>{pending ? "lädt …" : "Vorschläge laden"}</button>
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
        {/* Befund 11: der Orakeltext geht schon ueber die Leitung (Suggestions.Item.text), wurde aber
           nirgends angezeigt - als Tooltip an der Kartenzeile, ohne die Zeile selbst zu verlaengern. */}
        <span className="suggest-name" title={card.text}>
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
