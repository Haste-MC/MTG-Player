export function isPermutation(idx: number[], n: number): boolean {
  if (idx.length !== n) return false;
  const seen = new Set<number>();
  for (const i of idx) {
    if (!Number.isInteger(i) || i < 0 || i >= n || seen.has(i)) return false;
    seen.add(i);
  }
  return true;
}

export function amountsValid(a: number[], total: number, maxPer: (number | undefined)[], atLeastOne: boolean): boolean {
  if (a.length !== maxPer.length) return false;
  let sum = 0;
  for (let i = 0; i < a.length; i++) {
    const v = a[i];
    if (!Number.isInteger(v) || v < 0) return false;
    if (atLeastOne && v < 1) return false;
    const m = maxPer[i];
    if (m !== undefined && m > 0 && v > m) return false;
    sum += v;
  }
  return sum === total;
}

export function remaining(a: number[], total: number): number {
  return total - a.reduce((s, v) => s + (Number.isFinite(v) ? v : 0), 0);
}

/**
 * Welche Verschieberichtungen die cardlist-Dialoganzeige erlauben soll.
 * Fehlt flags (alte Bridge ohne Task-Feld), wird "beliebig" angenommen.
 */
export function cardlistDirections(flags?: string[]): { top: boolean; bottom: boolean; anywhere: boolean } {
  if (!flags) return { top: true, bottom: true, anywhere: true };
  const anywhere = flags.includes("anywhere");
  return {
    top: anywhere || flags.includes("top"),
    bottom: anywhere || flags.includes("bottom"),
    anywhere,
  };
}
