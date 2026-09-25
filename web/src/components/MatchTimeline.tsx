import type { MatchRecord, MatchSeat, TurnPoint } from "../protocol";

// Zeitachse einer aufgeklappten Partie (Spec §4): je Sitz eine kleine Kurve aus Laendern, Kreaturen und
// Leben je eigenem Zug. Reines Inline-SVG, keine Bibliothek.
//
// Bewusst ZWEI uebereinanderliegende Felder statt einer Kurve mit zwei Achsen: Leben laeuft von 40
// abwaerts, Laender und Kreaturen von 0 auf ein Dutzend hoch. In ein gemeinsames Feld gezwungen
// (zweite y-Achse rechts) sagt der Schnittpunkt zweier Linien nichts - er haengt nur an den gewaehlten
// Skalen. Getrennt liest man beides richtig, die x-Achse bleibt dieselbe.

/** Koordinatensystem des SVG (skaliert per width:100% mit). */
const W = 520;
const PAD_L = 30;   // Platz fuer die Hoechstwert-Beschriftung links
const PAD_R = 46;   // Platz fuer den Endwert rechts neben der Linie
const PAD_T = 10;
const PANEL_H = 44; // Hoehe eines Feldes
const GAP = 20;     // Abstand zwischen Leben- und Brett-Feld (traegt die Zwischenbeschriftung)
const PAD_B = 16;   // Zugnummern unter dem unteren Feld
const H = PAD_T + PANEL_H + GAP + PANEL_H + PAD_B;
const INNER_W = W - PAD_L - PAD_R;
/** Farben aus der Oberflaeche (styles.css): Gold und Blau sind auch bei Rot-Gruen-Schwaeche klar
 * auseinanderzuhalten (geprueft), Leben steht allein in seinem Feld. */
const COLOR = { life: "var(--red)", lands: "var(--gold)", creatures: "var(--accent)" };

/** x-Position einer Zugnummer; bei nur einem Punkt sitzt er links. */
function xOf(turn: number, first: number, last: number): number {
  return last === first ? PAD_L : PAD_L + ((turn - first) / (last - first)) * INNER_W;
}

/** Linie durch die Punkte, y ueber `max` normiert (0 unten). max 0 -> flache Linie auf der Grundlinie. */
function line(points: TurnPoint[], value: (p: TurnPoint) => number, max: number, top: number): string {
  const first = points[0].turn, last = points[points.length - 1].turn;
  return points
    .map((p, i) => {
      const y = top + PANEL_H - (max > 0 ? (value(p) / max) * PANEL_H : 0);
      return `${i === 0 ? "M" : "L"}${xOf(p.turn, first, last).toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

/** Eine Reihe: Linie, Punkt am Ende und der Endwert als Text (die Beschriftung traegt Text-, keine
 * Reihenfarbe - die Identitaet steckt im Punkt daneben). */
function Series({ points, value, max, top, color, label }:
  { points: TurnPoint[]; value: (p: TurnPoint) => number; max: number; top: number; color: string; label: string }) {
  const last = points[points.length - 1];
  const x = xOf(last.turn, points[0].turn, last.turn);
  const y = top + PANEL_H - (max > 0 ? (value(last) / max) * PANEL_H : 0);
  return (
    <g>
      <path d={line(points, value, max, top)} fill="none" stroke={color} strokeWidth="2"
        strokeLinecap="round" strokeLinejoin="round" />
      <circle cx={x} cy={y} r="2.6" fill={color} />
      <text className="tl-end" x={x + 6} y={y + 3.5}>{value(last)}</text>
      <title>{label}</title>
    </g>
  );
}

/** Grundlinie und Hoechstwert eines Feldes. */
function Frame({ top, max, caption }: { top: number; max: number; caption: string }) {
  return (
    <g>
      <line className="tl-axis" x1={PAD_L} y1={top + PANEL_H} x2={W - PAD_R} y2={top + PANEL_H} />
      <line className="tl-grid" x1={PAD_L} y1={top} x2={W - PAD_R} y2={top} />
      <text className="tl-axis-label" x={PAD_L - 5} y={top + 4} textAnchor="end">{max}</text>
      <text className="tl-axis-label" x={PAD_L - 5} y={top + PANEL_H + 3} textAnchor="end">0</text>
      <text className="tl-caption" x={PAD_L} y={top - 2}>{caption}</text>
    </g>
  );
}

/** Ab v3 zaehlt TurnPoint.turn den eigenen Zug des Sitzes; in einem aelteren Datensatz steht dort
 * weiterhin Forges globale Zugnummer (alte Datensaetze werden nicht umgerechnet, siehe
 * MatchRecord.VERSION in der Bridge) - die Achse sagt dem Betrachter, was er gerade sieht. */
function turnAxisLabel(v: number | undefined): string {
  return (v ?? 1) >= 3 ? "Eigener Zug" : "Partiezug (alle Sitze)";
}

/** Die Kurve eines Sitzes. Ohne Punkte (Partie endete vor dem ersten eigenen Zug) bleibt ein Satz statt
 * eines leeren Rahmens. */
function SeatChart({ seat, turnLabel }: { seat: MatchSeat; turnLabel: string }) {
  const points = seat.timeline ?? [];
  const title = `${seat.name} · ${seat.deck}`;
  if (points.length === 0) {
    return (
      <div className="timeline-seat">
        <div className="timeline-name">{title}</div>
        <p className="muted">Keine Zeitachse – die Partie endete vor dem ersten eigenen Zug dieses Sitzes.</p>
      </div>
    );
  }
  const lifeMax = Math.max(1, ...points.map((p) => p.life));
  const boardMax = Math.max(1, ...points.map((p) => Math.max(p.lands, p.creatures)));
  const boardTop = PAD_T + PANEL_H + GAP;
  const first = points[0].turn, last = points[points.length - 1].turn;
  // Hoechstens sechs Zugnummern unter der Achse - bei einer langen Partie sonst ein Zahlenteppich.
  const ticks = points.filter((_, i) => points.length <= 6 || i % Math.ceil(points.length / 6) === 0 || i === points.length - 1);
  return (
    <div className="timeline-seat">
      <div className="timeline-name">{title}</div>
      <svg className="timeline-svg" viewBox={`0 0 ${W} ${H}`} role="img"
        aria-label={`Zeitachse ${title}: Leben, Länder und Kreaturen je eigenem Zug`}>
        <Frame top={PAD_T} max={lifeMax} caption="Leben" />
        <Series points={points} value={(p) => p.life} max={lifeMax} top={PAD_T} color={COLOR.life} label="Leben" />
        <Frame top={boardTop} max={boardMax} caption="Länder und Kreaturen im Spiel" />
        <Series points={points} value={(p) => p.lands} max={boardMax} top={boardTop} color={COLOR.lands} label="Länder" />
        <Series points={points} value={(p) => p.creatures} max={boardMax} top={boardTop} color={COLOR.creatures} label="Kreaturen" />
        {ticks.map((p) => (
          <text key={p.turn} className="tl-tick" x={xOf(p.turn, first, last)} y={H - 4} textAnchor="middle">{p.turn}</text>
        ))}
        {/* ganz rechts in den Rand gesetzt, damit die Beschriftung nicht auf der letzten Zugnummer liegt */}
        <text className="tl-axis-label" x={W} y={H - 4} textAnchor="end">{turnLabel}</text>
      </svg>
    </div>
  );
}

/** Legende: ohne sie haengt die Identitaet der drei Linien allein an der Farbe. */
function Legend() {
  return (
    <div className="timeline-legend">
      {([["Leben", COLOR.life], ["Länder", COLOR.lands], ["Kreaturen", COLOR.creatures]] as const).map(([label, color]) => (
        <span key={label}><i style={{ background: color }} />{label}</span>
      ))}
    </div>
  );
}

/** Zeitachse aller Sitze einer Partie (Stand zu Beginn jedes eigenen Zuges). `record` ist der per
 * matchDetail nachgeladene Datensatz - die Partienliste selbst traegt die Zeitachse nicht. */
export default function MatchTimeline({ record }: { record: MatchRecord }) {
  const any = record.seats.some((s) => s.timeline != null);
  if (!any) {
    return <p className="muted">Diese Partie ist älter als die Zeitachse – für sie wurde nichts aufgezeichnet.</p>;
  }
  const turnLabel = turnAxisLabel(record.v);
  return (
    <div className="timeline">
      <Legend />
      <div className="timeline-grid">
        {record.seats.map((seat, i) => <SeatChart key={seat.name + i} seat={seat} turnLabel={turnLabel} />)}
      </div>
    </div>
  );
}
