/**
 * Hoehe der Gegner-Zeile, wenn der Spieler die Trennlinie zieht.
 *
 * Eigenes Modul aus demselben Grund wie {@link ./cardPreview}: die Grenzen sind reine Rechnung
 * und ohne Browser pruefbar, das Ziehen selbst nicht.
 */

/** Unter dieser Hoehe ist von den Gegnern nichts mehr zu erkennen. */
export const MIN_HOEHE = 120;

/**
 * So viel Platz behaelt die eigene Haelfte mindestens.
 *
 * Gemessen, nicht geraten (2026-10-08, Fenster 1600x900): die eigene Haelfte traegt
 * Kommandozone (~215px, die Kommandokarte haengt an der Fensterhoehe und schrumpft NICHT mit),
 * Phasenleiste (~25), Prompt (~40) und Hand (~190). Bei 500px blieb die Kommandozone noch ueber
 * der Phasenleiste stehen; 560 liegt sichtbar darueber.
 *
 * Die Grenze sitzt bewusst hier und nicht als Ueberlauf im Layout: eine abgeschnittene
 * Kommandozone waere auch keine Antwort, und die Kartengroessen haengen an der Fensterhoehe.
 */
export const MIN_EIGENE = 560;

export const SPEICHER_SCHLUESSEL = "mtg.oppHoehe";

/** Begrenzt eine gezogene Hoehe auf etwas Benutzbares. */
export function begrenzeHoehe(roh: number, fensterHoehe: number): number {
  const max = Math.max(MIN_HOEHE, fensterHoehe - MIN_EIGENE);
  return Math.round(Math.min(Math.max(roh, MIN_HOEHE), max));
}

/**
 * Gemerkte Hoehe aus dem Browserspeicher, oder {@code undefined} fuer "so viel wie noetig".
 * Jeder Fehler ist ein {@code undefined}: ein gesperrter oder geleerter Speicher darf den Tisch
 * nicht kosten, und die Voreinstellung ist genau die bisherige Ansicht.
 */
export function geleseneHoehe(speicher: Storage | undefined, fensterHoehe: number): number | undefined {
  try {
    const roh = speicher?.getItem(SPEICHER_SCHLUESSEL);
    if (!roh) return undefined;
    const zahl = Number(roh);
    return Number.isFinite(zahl) && zahl > 0 ? begrenzeHoehe(zahl, fensterHoehe) : undefined;
  } catch {
    return undefined;
  }
}

/** Hoehe merken, oder mit {@code undefined} wieder vergessen (Doppelklick auf die Trennlinie). */
export function merkeHoehe(speicher: Storage | undefined, hoehe: number | undefined): void {
  try {
    if (hoehe === undefined) speicher?.removeItem(SPEICHER_SCHLUESSEL);
    else speicher?.setItem(SPEICHER_SCHLUESSEL, String(Math.round(hoehe)));
  } catch {
    // Ein nicht beschreibbarer Speicher ist kein Grund, das Ziehen scheitern zu lassen.
  }
}
