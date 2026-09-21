import { useEffect, useMemo, useState } from "react";
import type { RefObject } from "react";
import type { Group } from "./groups";

/** Eine Spielfeldreihe fuer die Messung: je Slot die Breite in Karteneinheiten (1 aufrecht, 1.4 gedreht,
 *  1 + 0.04 je Stapel-Ebene), scale 1 fuer Karten, 0.8 fuer Laender.
 *  headroom: Hoehenzuschlag der Reihe in Karteneinheiten, bezogen auf w * scale (nicht auf die
 *  Kartenhoehe RATIO * w * scale) – fuer nach oben herausschauende Anhaenge. */
export interface RowSpec { units: number[]; scale: number; headroom?: number }
export interface FitOpts { gap?: number; rowGap?: number; min?: number; max?: number }

const MAX_LAYERS = 4;
const RATIO = 1.4;
const ATTACH_DY = 0.22;

/** Breite eines Stapels/einer Karte in Einheiten (siehe RowSpec). Gedreht, wenn getappt bzw. im Stapel
 *  getappte Karten liegen (die ragen als gedrehte Ebenen links/rechts heraus). */
export function slotUnits(g: Group): number {
  const stacked = g.cards.length > 1;
  if (!stacked) return g.card.tapped ? RATIO : 1;
  if (g.tapped > 0) return RATIO;
  return 1 + 0.04 * Math.min(g.cards.length - 1, MAX_LAYERS);
}

/** Hoehenzuschlag (in Karteneinheiten, bezogen auf w * scale) fuer einen Wirt mit n Anhaengen: jede der
 *  max. 4 Ebenen schaut um ATTACH_DY der Kartenhoehe (RATIO * w) nach oben heraus. Getappter Wirt: der
 *  Rahmen ist nur w hoch, die aufrechten Ebenen ragen zusaetzlich um (RATIO - 1) * w darueber hinaus. */
export function slotHeadroom(attachedCount: number, tapped = false): number {
  if (attachedCount <= 0) return 0;
  const base = tapped ? RATIO - 1 : 0;
  return base + Math.min(attachedCount, MAX_LAYERS) * ATTACH_DY * RATIO;
}

/** Passt die Reihen bei Kartenbreite w in width x height? Jede Reihe bricht zeilenweise um (eine Zeile
 *  enthaelt immer mindestens einen Slot); Zeilenhoehe = RATIO * w * scale + headroom * w * scale + gap. */
function fits(w: number, width: number, height: number, rows: RowSpec[], gap: number, rowGap: number): boolean {
  let total = 0;
  let filled = 0;
  for (const r of rows) {
    if (r.units.length === 0) continue;
    const lineH = RATIO * w * r.scale + (r.headroom ?? 0) * w * r.scale;
    let lines = 1;
    let x = 0;
    for (const u of r.units) {
      const sw = u * w * r.scale;
      // Ein Slot allein breiter als der Container passt auf keine Zeile, egal wie oft umgebrochen wird -
      // ohne diese Sperre wuerde er stillschweigend ueber den Rand ragen statt fits() scheitern zu lassen.
      if (sw > width) return false;
      if (x > 0 && x + gap + sw > width) { lines++; x = sw; } else { x = x === 0 ? sw : x + gap + sw; }
    }
    total += lines * lineH + (lines - 1) * gap + (filled > 0 ? rowGap : 0);
    filled++;
  }
  return total <= height;
}

/** Groesste ganzzahlige Kartenbreite in [min, max], bei der alle Reihen in width x height passen. */
export function fitCardWidth(width: number, height: number, rows: RowSpec[], opts: FitOpts = {}): number {
  const { gap = 6, rowGap = 4, min = 50, max = 180 } = opts;
  if (rows.every((r) => r.units.length === 0)) return max;
  if (!fits(min, width, height, rows, gap, rowGap)) return min;
  let lo = min, hi = max;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (fits(mid, width, height, rows, gap, rowGap)) lo = mid; else hi = mid - 1;
  }
  return lo;
}

/**
 * Misst den Reihen-Container (Content-Box per ResizeObserver) und liefert die passende Kartenbreite.
 * Der Container muss von aussen begrenzt sein (Grid-/Flex-Zeile mit fester Hoehe, overflow hidden) –
 * sonst waechst er mit den Karten und die Messung wuerde pendeln. undefined, solange nichts gemessen ist
 * oder enabled false ist (dann greifen die CSS-Fallbacks).
 */
export function useBoardSize(ref: RefObject<HTMLElement>, rows: RowSpec[], enabled: boolean, opts: FitOpts = {}): number | undefined {
  const [size, setSize] = useState<{ w: number; h: number }>();
  useEffect(() => {
    const el = ref.current;
    if (!enabled || !el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver((entries) => {
      const cr = entries[0]?.contentRect;
      if (!cr) return;
      const w = Math.floor(cr.width), h = Math.floor(cr.height);
      setSize((s) => (s && s.w === w && s.h === h ? s : { w, h }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref, enabled]);
  // Signatur statt rows-Referenz: PlayerZone baut die Reihen bei jedem Render neu.
  const sig = rows.map((r) => r.scale + ":" + (r.headroom ?? 0) + ":" + r.units.join(",")).join("|");
  const optsSig = opts.min + ":" + opts.max + ":" + opts.gap + ":" + opts.rowGap;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => (enabled && size ? fitCardWidth(size.w, size.h, rows, opts) : undefined), [enabled, size, sig, optsSig]);
}
