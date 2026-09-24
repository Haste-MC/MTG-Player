import { NOTHING_TITLE, SUGGESTIONS_HINT, TOO_FEW_TITLE, type Finding } from "../findings";

// Auffaelligkeiten (Spec §5): der Block, der Kevins "ich sehe nicht, wo man Schwaechen rauslesen soll"
// beantwortet. Er steht UEBER den Kacheln, weil er die Kacheln in Saetze uebersetzt - die Regeln selbst
// stehen in findings.ts und kommen hier fertig an.

/** Warnzeichen bzw. Hinweiszeichen - als Zeichnung statt Emoji, damit die Schriftart nichts dazu sagt. */
function LevelIcon({ level }: { level: Finding["level"] }) {
  return level === "warn" ? (
    <svg className="icon" viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
      <path d="M8 1.6 15 14H1L8 1.6Z" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" />
      <path d="M8 6v3.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="8" cy="11.8" r="0.9" fill="currentColor" />
    </svg>
  ) : (
    <svg className="icon" viewBox="0 0 16 16" width="15" height="15" aria-hidden="true">
      <circle cx="8" cy="8" r="6.5" fill="none" stroke="currentColor" strokeWidth="1.4" />
      <path d="M8 7.2v4" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="8" cy="4.8" r="0.9" fill="currentColor" />
    </svg>
  );
}

/** Die Befunde des gewaehlten Decks im gewaehlten Format. Die Liste ist nie leer: findings() liefert
 * auch fuer "zu wenige Partien" und "nichts Auffaelliges" genau eine Zeile. `hint` haengt den Verweis
 * auf die Kartenvorschlaege (Stueck 2) an - nur an echte Befunde, nicht an diese beiden Saetze. */
export default function Findings({ findings, analyzed }: { findings: Finding[]; analyzed: boolean }) {
  const real = findings.some((f) => f.title !== TOO_FEW_TITLE && f.title !== NOTHING_TITLE);
  return (
    <section className="sb-block findings">
      <div className="sb-block-head">
        <h2>Auffälligkeiten</h2>
        {real && !analyzed && (
          <p className="sb-block-note">Ohne Deckanalyse: Regeln, die den Deckinhalt brauchen, schweigen hier.</p>
        )}
      </div>
      <ul className="finding-list">
        {findings.map((f) => (
          <li key={f.title} className={"finding " + f.level}>
            <span className="finding-icon"><LevelIcon level={f.level} /></span>
            <div className="finding-text">
              <b>{f.title}</b>
              <span>{f.text}</span>
              {f.needs && <span className="finding-needs">{f.needs}</span>}
            </div>
            {real && <span className="finding-more">{SUGGESTIONS_HINT}</span>}
          </li>
        ))}
      </ul>
    </section>
  );
}
