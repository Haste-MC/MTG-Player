import type { DeckAnalysis } from "../protocol";
import { one } from "./StatTiles";

// Block 6 der Spec: was im Deck steckt - rein aus der Kartendatenbank, ohne eine einzige Partie. Die
// Einordnung ist Textmustererkennung (siehe DeckAnalysis): Groessenordnungen, keine Wahrheit. Genau das
// schreibt der Block dazu, statt die Zahlen als Befund auszugeben.

const CATEGORY_LABEL: Record<string, string> = {
  ramp: "Mana-Beschleunigung", draw: "Kartenziehen", removal: "Entfernung", wipes: "Massenentfernung",
  counters: "Konterzauber", flyerDefense: "gegen Flieger", wipeProtection: "gegen Massenentfernung",
  recursion: "Rückholer", tutors: "Sucher",
};
/** Reihenfolge der Farbquellen; "any" steht am Ende (Länder, die jede Farbe geben). */
const COLORS: { key: string; label: string; css: string }[] = [
  { key: "W", label: "Weiß", css: "#efe4c2" }, { key: "U", label: "Blau", css: "#4a8fd8" },
  { key: "B", label: "Schwarz", css: "#8a8a92" }, { key: "R", label: "Rot", css: "#d1544f" },
  { key: "G", label: "Grün", css: "#3f9e6b" }, { key: "any", label: "beliebig", css: "#b9c2c9" },
];
/** Schluessel der Manakurve in fester Reihenfolge (die Bridge schickt genau diese). */
const CURVE_KEYS = ["0", "1", "2", "3", "4", "5", "6", "7+"];

/** Manakurve als kleines Balkenbild: eine Reihe, ein Balken je Manabetrag, Zahl darueber. */
function Curve({ curve }: { curve: Record<string, number> }) {
  const max = Math.max(1, ...CURVE_KEYS.map((k) => curve[k] ?? 0));
  return (
    <div className="curve" role="img" aria-label={"Manakurve: " + CURVE_KEYS.map((k) => `${k}: ${curve[k] ?? 0}`).join(", ")}>
      {CURVE_KEYS.map((k) => {
        const n = curve[k] ?? 0;
        return (
          <div key={k} className="curve-col">
            <span className="curve-n">{n}</span>
            <span className="curve-bar" style={{ height: `${Math.round((n / max) * 100)}%` }} />
            <span className="curve-k">{k}</span>
          </div>
        );
      })}
    </div>
  );
}

/** Deckinhalt des gewaehlten Decks. `pending` unterscheidet "noch unterwegs" von "die Bridge kennt das
 * Deck nicht" - ein Deckname aus einer alten Partie kann laengst geloescht sein. */
export default function DeckAnalysisPanel(
  { deck, analysis, pending, explain }: { deck: string; analysis?: DeckAnalysis; pending: boolean; explain: boolean },
) {
  if (!analysis) {
    return (
      <section className="sb-block">
        <div className="sb-block-head"><h2>Deck</h2></div>
        <p className="muted">
          {pending
            ? `Deckanalyse für „${deck}“ wird geladen …`
            : `Keine Deckanalyse für „${deck}“ – die Bridge kennt kein Deck dieses Namens (gelöscht oder eine Textliste).`}
        </p>
      </section>
    );
  }
  const cats = Object.entries(analysis.categories);
  return (
    <section className="sb-block">
      <div className="sb-block-head">
        <h2>Deck</h2>
        <p className="sb-block-note">
          Aus der Kartendatenbank, ohne Partien. Die Einordnung liest Kartentexte mit Mustern – die Zahlen sind
          Größenordnungen, keine Wahrheit.
        </p>
      </div>
      <div className="deck-analysis">
        <div className="da-numbers">
          <div className="da-num"><b>{analysis.cards}</b><span>Karten</span></div>
          <div className="da-num"><b>{analysis.lands}</b><span>Länder, davon {analysis.basics} Standard</span></div>
          <div className="da-num"><b>{one(analysis.avgCmc)}</b><span>Ø Manabetrag</span></div>
          <div className="da-num"><b>{analysis.identity.join("") || "–"}</b><span>Farbidentität</span></div>
        </div>
        <div className="da-curve">
          <div className="da-title">Manakurve (ohne Länder)</div>
          <Curve curve={analysis.curve} />
          {explain && <p className="sb-block-note">Wie viele Nicht-Länder je Manabetrag im Deck stecken.</p>}
        </div>
        <div className="da-sources">
          <div className="da-title">Farbquellen</div>
          <div className="da-source-row">
            {COLORS.filter((c) => (analysis.sources[c.key] ?? 0) > 0).map((c) => (
              <span key={c.key} className="da-source" title={c.label}>
                <i style={{ background: c.css }} />{analysis.sources[c.key]}
              </span>
            ))}
          </div>
          {explain && <p className="sb-block-note">Länder mit Grundtyp oder „Add {"{"}X{"}"}“; „beliebig“ zählt separat.</p>}
        </div>
        <div className="da-cats">
          <div className="da-title">Karten je Aufgabe</div>
          <div className="da-chips">
            {cats.map(([key, n]) => (
              <span key={key} className={"da-chip" + (n === 0 ? " zero" : "")}>
                {CATEGORY_LABEL[key] ?? key}<b>{n}</b>
              </span>
            ))}
            <span className="da-chip muted-chip">ohne Kategorie<b>{analysis.unclassified}</b></span>
          </div>
          {explain && <p className="sb-block-note">Eine Karte kann mehrere Aufgaben erfüllen und zählt dann mehrfach.</p>}
        </div>
      </div>
    </section>
  );
}
