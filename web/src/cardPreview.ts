/**
 * Lage und Groesse der grossen Kartenvorschau am Mauszeiger.
 *
 * Eigenes Modul, weil beides reine Rechnung ist: eine Komponente laesst sich nur im Browser
 * pruefen, diese beiden Funktionen an jedem Rand und in jeder Fenstergroesse.
 */

/** Seitenverhaeltnis einer Magic-Karte (Scryfall liefert 488x680). */
export const KARTE_RATIO = 680 / 488;

/** Wunschbreite der Vorschau; kleinere Fenster bekommen weniger (siehe {@link vorschauGroesse}). */
export const WUNSCH_BREITE = 420;

/** Abstand zum Mauszeiger und zum Fensterrand. */
export const ABSTAND = 24;
const RAND = 8;

export type Groesse = { breite: number; hoehe: number };
export type Punkt = { x: number; y: number };

/**
 * So gross wie moeglich, aber nie hoeher als das Fenster: auf einem flachen Fenster wuerde eine
 * 420px breite Karte (585px hoch) oben und unten herausragen, und genau der untere Teil traegt
 * den Regeltext.
 */
export function vorschauGroesse(fensterHoehe: number): Groesse {
  const maxBreite = (fensterHoehe - 2 * RAND) / KARTE_RATIO;
  const breite = Math.max(0, Math.min(WUNSCH_BREITE, maxBreite));
  return { breite, hoehe: breite * KARTE_RATIO };
}

/**
 * Rechts neben dem Zeiger, solange dort Platz ist - sonst links davon. Senkrecht mittig zum
 * Zeiger, aber immer vollstaendig im Bild: eine Vorschau, die halb aus dem Fenster ragt, ist
 * genau an der Stelle unlesbar, an der man hinsieht.
 */
export function vorschauPosition(maus: Punkt, fenster: Groesse, vorschau: Groesse): { left: number; top: number } {
  const rechts = maus.x + ABSTAND;
  const passtRechts = rechts + vorschau.breite + RAND <= fenster.breite;
  const links = maus.x - ABSTAND - vorschau.breite;
  const left = passtRechts ? rechts : Math.max(RAND, links);
  const top = Math.min(
    Math.max(RAND, maus.y - vorschau.hoehe / 2),
    Math.max(RAND, fenster.hoehe - vorschau.hoehe - RAND),
  );
  return { left, top };
}
