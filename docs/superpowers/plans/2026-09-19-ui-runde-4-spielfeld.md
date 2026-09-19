# UI-Runde 4 – Spielfeld-Reihen, Kartengröße per Messung, Tabletop-Stapel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spielfeld in Zuschauer-Panels und eigener Zone in drei Reihen (Kreaturen / übrige / Länder unten), Kartenbreite aus Breite **und** Höhe des Panels gemessen, getappt = gedreht, Stapel wie ein echter Kartenstapel (Ebenen, getappte gedreht darunter).

**Architecture:** Reine Funktion `fitCardWidth` (Binärsuche über die Kartenbreite, zeilenweiser Umbruch je Reihe) + Hook `useBoardSize` (ResizeObserver auf `.rows`, setzt `--bw` auf dem Spieler-Panel). `PlayerZone` rendert `.bf-creatures`/`.bf-other`/`.bf-lands`; CSS leitet alle Zuschauer-/Eigene-Zone-Breiten aus `--bw` ab. `CardBox` rendert bei `stack.count > 1` Ebenen hinter der Karte. Spec: `docs/superpowers/specs/2026-09-19-spielfeld-reihen-design.md`.

**Tech Stack:** React 18, TypeScript, Vite, Vitest, Playwright 1.63 (headless Chromium), CSS-Variablen. Bridge unverändert.

## Global Constraints

- Nur `web/` ändert sich; Protokoll und Bridge unverändert. Kompakte Gegnerzeilen der Tischansicht (`.player.compact:not(.spectator)`) bleiben einzeilig, ohne Drehung, mit Badge „N getappt" (Beschluss A).
- Suiten grün: `cd web && npm test` (37 + neue), `npm run build`, `npm run layout-check -- http://127.0.0.1:8080 fixtures/<f>.json` für **alle** Fixtures (`table`, `table-1v1`, `table-cmd-cast`, `spectator`, `spectator-heavy`, `spectator-rows`) bei `VIEWPORT=1280x720`, `1600x900`, `1920x1080`.
- Screenshots gegen Kevins laufende Bridge auf 8080 (sie liefert `web/dist` und `/img`; Fixtures legen den WebSocket still, also ungefährlich). `npm run build` vorher. Ist 8080 nicht erreichbar (curl 200 prüfen): eigene Bridge `cd bridge && mvn -q compile exec:java -Dmtgplayer.httpPort=18080 -Dmtgplayer.wsPort=18091` (nie zwei Maven-Prozesse gleichzeitig, Timeout 600000 ms) und `http://127.0.0.1:18080` verwenden.
- Jede Screenshot-Datei mit Read ansehen, Befund im Ledger notieren; Screenshots unter `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/scratchpad/polish4/`.
- Kevins Referenz-Screenshot (Ist-Zustand): `/tmp/claude-1000/-home-kevin-projects-DiscordBot/4cee7c96-6aaf-442e-8cd8-a9f8632f3c2b/images/4.webp`.
- Kartenbreite immer in `[50, 180]` px; Länder-Faktor 0,8; gedreht = 1,4 Einheiten breit; Ebenen-Versatz 5 px; max. 4 Ebenen.
- Commit-Messages deutsch, Kleinschreibung, Präfix `ui:`, Trailer `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`. Branch `feature/ui-polish-4` ab `main`.

---

### Task 1: `boardSize.ts` – `fitCardWidth` + `useBoardSize`

**Files:**
- Create: `web/src/boardSize.ts`
- Test: `web/src/boardSize.test.ts`

**Interfaces:**
- Produces:
  ```ts
  export interface RowSpec { units: number[]; scale: number }   // je Slot Breite in Karteneinheiten; scale 1 (Karten) oder 0.8 (Länder)
  export interface FitOpts { gap?: number; rowGap?: number; min?: number; max?: number }  // Standard 6, 4, 50, 180
  export function fitCardWidth(width: number, height: number, rows: RowSpec[], opts?: FitOpts): number
  export function slotUnits(g: Group): number   // 1.4 bei gedrehtem Slot, sonst 1 + 0.04 * Ebenen
  export function useBoardSize(ref: RefObject<HTMLElement>, rows: RowSpec[], enabled: boolean): number | undefined
  ```
  (`Group` aus `web/src/groups.ts`: `{ card, cards, tapped }`.)

- [ ] **Step 1: Failing Tests schreiben** – `web/src/boardSize.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { fitCardWidth, slotUnits } from "./boardSize";
import type { CardSnap } from "./protocol";

const row = (n: number, scale = 1, units?: number[]): { units: number[]; scale: number } =>
  ({ units: units ?? Array(n).fill(1), scale });
const snap = (id: number, over: Partial<CardSnap> = {}): CardSnap => ({ id, faceDown: false, name: "Forest", ...over });

describe("fitCardWidth", () => {
  it("leere reihen -> maximum", () => {
    expect(fitCardWidth(600, 500, [])).toBe(180);
    expect(fitCardWidth(600, 500, [row(0)])).toBe(180);
  });

  it("eine reihe mit 4 karten in 600x500: umbruch in zwei zeilen statt vier winzige nebeneinander", () => {
    // 4 nebeneinander: (4w + 18) <= 600 -> w <= 145. Zwei Zeilen a 2: Hoehe 2*1.4w + 4 <= 500 -> w <= 177,
    // Breite 2w + 6 <= 600 -> w <= 297. Also passt 177, nicht nur 145.
    const w = fitCardWidth(600, 500, [row(4)]);
    expect(w).toBeGreaterThan(160);
    expect(w).toBeLessThanOrEqual(180);
  });

  it("getappte karten (1.4 einheiten) verbreitern die reihe", () => {
    const upright = fitCardWidth(600, 200, [row(4)]);
    const tapped = fitCardWidth(600, 200, [row(4, 1, [1.4, 1.4, 1.4, 1.4])]);
    expect(tapped).toBeLessThan(upright);
  });

  it("laender-faktor: eine laenderreihe (0.8) laesst breitere karten zu als eine kartenreihe", () => {
    const lands = fitCardWidth(600, 200, [row(4, 0.8)]);
    const cards = fitCardWidth(600, 200, [row(4)]);
    expect(lands).toBeGreaterThan(cards);
  });

  it("nie unter min oder ueber max", () => {
    expect(fitCardWidth(100, 60, [row(10)])).toBe(50);
    expect(fitCardWidth(5000, 5000, [row(1)])).toBe(180);
    expect(fitCardWidth(5000, 5000, [row(1)], { max: 132 })).toBe(132);
  });

  it("hoehe ist bindend: drei reihen muessen uebereinander passen", () => {
    // 3 Reihen je 1 Karte, Hoehe 300: 1.4w*(1+1+0.8) + 2*4 <= 300 -> w <= 74.
    const w = fitCardWidth(1000, 300, [row(1), row(1), row(1, 0.8)]);
    expect(w).toBeGreaterThanOrEqual(72);
    expect(w).toBeLessThanOrEqual(75);
  });
});

describe("slotUnits", () => {
  it("einzelkarte: 1, getappt 1.4", () => {
    expect(slotUnits({ card: snap(1), cards: [snap(1)], tapped: 0 })).toBe(1);
    expect(slotUnits({ card: snap(1, { tapped: true }), cards: [snap(1, { tapped: true })], tapped: 1 })).toBe(1.4);
  });
  it("stapel: mit getappten 1.4, sonst 1 + 0.04 je ebene (max 4)", () => {
    const cards = [snap(1), snap(2), snap(3)];
    expect(slotUnits({ card: cards[0], cards, tapped: 0 })).toBeCloseTo(1.08);
    expect(slotUnits({ card: cards[0], cards, tapped: 1 })).toBe(1.4);
    const six = [1, 2, 3, 4, 5, 6].map((i) => snap(i));
    expect(slotUnits({ card: six[0], cards: six, tapped: 0 })).toBeCloseTo(1.16);
  });
});
```

- [ ] **Step 2: Laufen lassen, muss fehlschlagen** – `cd web && npx vitest run src/boardSize.test.ts` → „Cannot find module './boardSize'".

- [ ] **Step 3: Implementieren** – `web/src/boardSize.ts`:

```ts
import { useEffect, useMemo, useState } from "react";
import type { RefObject } from "react";
import type { Group } from "./groups";

/** Eine Spielfeldreihe fuer die Messung: je Slot die Breite in Karteneinheiten (1 aufrecht, 1.4 gedreht,
 *  1 + 0.04 je Stapel-Ebene), scale 1 fuer Karten, 0.8 fuer Laender. */
export interface RowSpec { units: number[]; scale: number }
export interface FitOpts { gap?: number; rowGap?: number; min?: number; max?: number }

const MAX_LAYERS = 4;
const RATIO = 1.4;

/** Breite eines Stapels/einer Karte in Einheiten (siehe RowSpec). Gedreht, wenn getappt bzw. im Stapel
 *  getappte Karten liegen (die ragen als gedrehte Ebenen links/rechts heraus). */
export function slotUnits(g: Group): number {
  const stacked = g.cards.length > 1;
  if (!stacked) return g.card.tapped ? RATIO : 1;
  if (g.tapped > 0) return RATIO;
  return 1 + 0.04 * Math.min(g.cards.length - 1, MAX_LAYERS);
}

/** Passt die Reihen bei Kartenbreite w in width x height? Jede Reihe bricht zeilenweise um (eine Zeile
 *  enthaelt immer mindestens einen Slot); Zeilenhoehe = RATIO * w * scale + gap. */
function fits(w: number, width: number, height: number, rows: RowSpec[], gap: number, rowGap: number): boolean {
  let total = 0;
  let filled = 0;
  for (const r of rows) {
    if (r.units.length === 0) continue;
    const lineH = RATIO * w * r.scale;
    let lines = 1;
    let x = 0;
    for (const u of r.units) {
      const sw = u * w * r.scale;
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
export function useBoardSize(ref: RefObject<HTMLElement>, rows: RowSpec[], enabled: boolean): number | undefined {
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
  const sig = rows.map((r) => r.scale + ":" + r.units.join(",")).join("|");
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => (enabled && size ? fitCardWidth(size.w, size.h, rows, {}) : undefined), [enabled, size, sig]);
}
```

- [ ] **Step 4: Tests grün** – `npx vitest run src/boardSize.test.ts` → 8 passed. Danach `npm test` → 45 passed.

- [ ] **Step 5: Commit** – `git add web/src/boardSize.ts web/src/boardSize.test.ts && git commit -m "ui: fitCardWidth und useBoardSize – kartenbreite aus breite und hoehe des spielfelds"`.

---

### Task 2: Drei Reihen, Messung in PlayerZone, Drehung, CSS-Umbau

**Files:**
- Modify: `web/src/components/PlayerZone.tsx` (ganze Datei, `useFit` entfällt)
- Modify: `web/src/styles.css` (Abschnitte „Spielfeld-Zonen" Z. 170–183, „Zuschauer-Panels" Z. 250–289, „Karten" Z. 296–302, Zuschauer-Raster Z. 126–135)
- Create: `web/fixtures/spectator-rows.json`
- Modify: `web/scripts/layout-check.mjs` (neue Prüfungen 7 und 8)

**Interfaces:**
- Consumes: `fitCardWidth`, `slotUnits`, `useBoardSize`, `RowSpec` aus Task 1; `groupCards` aus `groups.ts`.
- Produces: CSS-Variable `--bw` (px) auf `.player.own` / `.player.spectator`; Reihen-Klassen `.bf-rows` (Container), `.bf-creatures`, `.bf-other`, `.bf-lands`.

- [ ] **Step 1: Fixture `web/fixtures/spectator-rows.json`** anlegen (Format wie `spectator-heavy.json`: ein `state`-Objekt mit `spectator: true`, `players` (6, ids 1–6, `name` „KI n", `isAi: true`), `cards`-Dict). Inhalt je Spieler (Karten-IDs `p*100 + n`):
  - Kommandozone: Commander bei Spieler 1, 3, 5 (`commander: true`, `zone: "command"`); bei 2, 4, 6 ist der Commander im Spiel (`zone: "battlefield"`, `commander: true`, Kreatur).
  - Kreaturen 3–8 (`typeLine: "Creature — …"`, `power`/`toughness`), davon mindestens die Hälfte `tapped: true`; Spieler 4 zusätzlich 3 gleichnamige „Beast"-Token (`token: true`, ohne `imageKey`), 2 davon getappt.
  - Übrige 2–4: `"Artifact"`, `"Enchantment"`, `"Legendary Planeswalker — …"` (ohne P/T), je eine getappt.
  - Länder 6–12 als Basics: Spieler 1: 4× Forest (2 getappt) + 3× Swamp (alle getappt) + 1 Command Tower; Spieler 2: 5× Island (0 getappt) + 6 weitere verschiedene; Spieler 3–6 gemischt; überall ≥ 50 % getappt.
  - `imageKey` wie in `spectator-heavy.json` (`c:Forest|TDC|1` usw.; für Kreaturen die dortigen Keys wiederverwenden, damit Bilder aus dem Cache kommen); Hand 2–5 Karten; `graveyard` 1–3, `exile` 0–1; `turn: 12`, `activePlayer: 4`, `prompt` wie in `spectator-heavy.json`.

- [ ] **Step 2: `PlayerZone.tsx` umbauen.** `useFit` und `sizing` mit `--n-lands/--n-bf/--n/--fit` entfernen. Neu:

```tsx
import { useRef } from "react";
import { slotUnits, useBoardSize } from "../boardSize";
import type { RowSpec } from "../boardSize";

const isLand = (c: CardSnap) => !!c.typeLine?.includes("Land");
const isCreature = (c: CardSnap) => !!c.typeLine?.includes("Creature");
// Drei Reihen: Kreaturen (auch Kreatur-Laender/Artefakt-Kreaturen), uebrige bleibende Karten, Laender unten.
function splitRows(bf: CardSnap[]) {
  const creatures = groupCards(bf.filter(isCreature));
  const other = groupCards(bf.filter((c) => !isCreature(c) && !isLand(c)));
  const lands = groupCards(bf.filter((c) => !isCreature(c) && isLand(c)));
  return { creatures, other, lands };
}
```

  In der Komponente: `const { creatures, other, lands } = splitRows(bf);` (ersetzt `others`/`lands`). Messung nur für eigene Zone und Zuschauer (`measured = !compact || !!spectator`):

```tsx
const rowsRef = useRef<HTMLDivElement>(null);
const rowSpecs: RowSpec[] = [
  { units: creatures.map(slotUnits), scale: 1 },
  { units: other.map(slotUnits), scale: 1 },
  { units: lands.map(slotUnits), scale: 0.8 },
];
const bw = useBoardSize(rowsRef, rowSpecs, measured);
const sizing = {
  ...(bw !== undefined ? { "--bw": `${bw}px` } : {}),
  ...(compact && !spectator ? { "--n-lands": lands.length } : {}),
} as CSSProperties;
```

  JSX des Spielfelds (Zuschauer + eigene Zone; kompakte Gegnerzeile behält `bf-main`/`bf-lands` mit `others = [...creatures, ...other]`):

```tsx
{measured ? (
  <div className="rows bf-rows" ref={rowsRef}>
    {creatures.length > 0 && <div className="row bf-creatures"><Stacks groups={creatures} /></div>}
    {other.length > 0 && <div className="row bf-other"><Stacks groups={other} /></div>}
    {lands.length > 0 && <div className="row bf-lands"><Stacks groups={lands} /></div>}
    {creatures.length + other.length + lands.length === 0 && <div className="bf-empty">keine bleibenden Karten</div>}
  </div>
) : (
  <div className="rows">
    <div className="row bf-main"><Stacks groups={[...creatures, ...other]} /></div>
    <div className="row bf-lands"><Stacks groups={lands} /></div>
  </div>
)}
```

  `useFit`-Funktion und ihre Kommentare löschen; `CSSProperties`-Import bleibt.

- [ ] **Step 3: CSS.** Änderungen in `web/src/styles.css`:

  1. Zuschauer-Raster (Z. 132): Panels mit 5–6 Spielern unter 1800 px bekommen eine feste Höhe statt `auto`, sonst wäre die Messung zirkulär (Container wächst mit Karten): `.spectator-grid.players-5, .spectator-grid.players-6 { grid-auto-rows: calc((100vh - 3 * var(--gap) - 44px) / 2); align-content: start; }` (44 px ≈ Prompt-Leiste; der ab 1800 px geltende `minmax(0, 1fr)`-Block bleibt).
  2. Reihen-Container (neu, hinter `.rows`): 
     ```css
     .bf-rows { position: relative; overflow: hidden; min-height: 0; gap: 4px; justify-content: flex-start; }
     .bf-rows .row { flex-wrap: wrap; gap: 6px; padding: 2px; overflow: visible; mask-image: none; -webkit-mask-image: none; }
     .bf-rows .bf-lands { margin-top: auto; }
     .bf-rows .bf-creatures, .bf-rows .bf-other { --w: var(--bw, var(--card-w)); }
     .bf-rows .bf-lands { --w: calc(var(--bw, var(--land-w)) * 0.8); opacity: 0.92; }
     .bf-empty { color: var(--faint); font-size: 11px; padding: 4px; }
     ```
  3. Eigene Zone: `.player.own .rows { justify-content: center; }` → für `.bf-rows` gilt `flex-start` (Regel oben ist spezifischer; prüfen). `.player.own .bf-main`/`.player.own .bf-lands` (Z. 181–183) löschen. `.player.own .row { flex-wrap: nowrap; overflow-x: auto; … }` bleibt für `.command .row`, aber `.bf-rows .row` überschreibt (Reihenfolge: `.bf-rows`-Block **nach** den `.player.own`-Regeln einfügen). `.player.own .command { --w: var(--bw, var(--card-w)); }`.
  4. Zuschauer (Z. 257–284): `--panel-inner`, `--w-fit`, `--w-land-fit`, `--fit` löschen. `--w-spec: var(--bw, clamp(60px, 21vh - 70px, 170px));` `--w-spec-land` löschen; `.player.spectator .bf-main`, `.player.spectator .bf-lands`-Regeln löschen (die `.bf-rows`-Regeln gelten). `.player.spectator .rows { grid-area: bf; }` bleibt.
  5. Drehung (Z. 297–302): Selektor `.player.own .battlefield` → `.bf-rows` in allen fünf Regeln (`.card-slot.tapped-slot`, `.card-frame.tapped`, `.card.tapped`, `.tag.tapped-tag`, `.card.tapped .art`). Kommentar anpassen: „Gedreht in allen gemessenen Reihen (eigene Zone, Zuschauer); Gegnerzeile der Tischansicht abgedunkelt + ⟳".
  6. Alte Kommentare zur `--n-bf`-Formel (Z. 250–254) durch einen Satz ersetzen: „Kartenbreite kommt aus `useBoardSize` (`--bw`), Fallback nur bis zur ersten Messung."

- [ ] **Step 4: `layout-check.mjs` erweitern** (im `page.evaluate`-Block vor `return out;`):

```js
    // 7. Gemessene Reihen (.bf-rows): mit <= 5 Kreaturen-Stapeln muss die Kartenbreite bei >= 1600px Breite
    //    mindestens 110px, bei 1920x1080 mindestens 120px sein - der Befund "Karten winzig, Panel leer".
    const minW = vw >= 1920 && vh >= 1080 ? 120 : vw >= 1600 ? 110 : 0;
    for (const rows of document.querySelectorAll(".player.spectator .bf-rows")) {
      const creatures = rows.querySelectorAll(".bf-creatures .card-slot").length;
      const first = rows.querySelector(".bf-creatures .card:not(.tapped)");
      if (!first || creatures > 5 || minW === 0) continue;
      const w = r(first).width;
      if (w < minW) out.push(`Zuschauer-Panel "${rows.closest(".player")?.querySelector(".pname")?.textContent}": Kartenbreite ${Math.round(w)}px < ${minW}px bei ${creatures} Kreaturen`);
    }
    // 8. Laender liegen unten: die Laenderreihe endet nicht mehr als 8px ueber der Unterkante des Reihen-Containers.
    for (const rows of document.querySelectorAll(".bf-rows")) {
      const lands = rows.querySelector(".bf-lands");
      if (!lands) continue;
      const rb = r(rows), lb = r(lands);
      if (rb.bottom - lb.bottom > 8) out.push(`Laenderreihe haengt ${Math.round(rb.bottom - lb.bottom)}px ueber der Unterkante (${rows.closest(".player")?.querySelector(".pname")?.textContent})`);
    }
```

  Und nach dem Block (außerhalb von `page.evaluate`, vor dem Hover-Test) die Stabilitätsprüfung – die Messung darf nicht pendeln:

```js
  // 9. Messung stabil: --bw jedes Panels nach 400ms unveraendert (sonst waechst der Container mit den Karten).
  const bw1 = await page.evaluate(() => [...document.querySelectorAll(".player")].map((p) => p.style.getPropertyValue("--bw")));
  await page.waitForTimeout(400);
  const bw2 = await page.evaluate(() => [...document.querySelectorAll(".player")].map((p) => p.style.getPropertyValue("--bw")));
  if (JSON.stringify(bw1) !== JSON.stringify(bw2)) problems.push(`Kartenbreite pendelt: ${bw1.join(" ")} -> ${bw2.join(" ")}`);
```

- [ ] **Step 5: Bauen, Screenshots, iterieren.** `npm run build`, dann (aus `web/`, Ziel `polish4/`):
  - `VIEWPORT=1920x1080 npm run shot -- http://127.0.0.1:8080 <scratch>/polish4/spectator-rows-1920.png fixtures/spectator-rows.json`
  - `VIEWPORT=1280x720 npm run shot -- … spectator-rows-1280.png fixtures/spectator-rows.json`
  - `VIEWPORT=1920x1080 … spectator-heavy-1920.png fixtures/spectator-heavy.json`
  - `VIEWPORT=1600x900 … table-1600.png fixtures/table.json` und `VIEWPORT=1280x720 … table-1280.png fixtures/table.json`
  Jede Datei mit Read ansehen. Sollzustand: Karten in Zuschauer-Panels deutlich größer als in Kevins Screenshot (≥ 120 px bei 1920), Länder an der Unterkante, getappte gedreht, keine leere Fläche zwischen Reihen und Panelrand, eigene Zone ohne Überlauf bei 1280×720 (Hand bleibt vollständig sichtbar). Bei Abweichungen CSS nachbessern und erneut schießen.

- [ ] **Step 6: Layout-Check** für alle 6 Fixtures × 3 Viewports (18 Läufe), z. B.
  `for f in table table-1v1 table-cmd-cast spectator spectator-heavy spectator-rows; do for v in 1280x720 1600x900 1920x1080; do VIEWPORT=$v npm run layout-check -- http://127.0.0.1:8080 fixtures/$f.json || echo "FAIL $f $v"; done; done` (fish: entsprechend umschreiben oder `bash -c`). Alle grün. `npm test` grün.

- [ ] **Step 7: Commit** – `git add web/src web/fixtures/spectator-rows.json web/scripts/layout-check.mjs && git commit -m "ui: spielfeld in drei reihen, laender unten, kartenbreite gemessen, getappt gedreht in zuschauer und eigener zone"`.

---

### Task 3: Tabletop-Stapel in `CardBox`

**Files:**
- Modify: `web/src/components/CardBox.tsx`
- Modify: `web/src/styles.css` (Stapel-Block Z. 308–318)
- Modify: `README.md` (Abschnitt „Spielen", Stapel-Satz)

**Interfaces:**
- Consumes: `StackInfo { count, tapped }` (bereits in `CardBox`), `Group` aus `groups.ts`, `slotUnits` aus Task 1 (Breiten müssen zur CSS passen: gedreht 1,4·w, sonst w + 5 px·Ebenen).

- [ ] **Step 1: `CardBox.tsx`** – hinter `const stacked = …`:

```tsx
const layers = stacked ? Math.min(stack.count - 1, 4) : 0;
const tappedLayers = stacked ? Math.min(stack.tapped, layers) : 0;   // gedrehte Ebenen unten im Stapel
const allTapped = stacked && stack.tapped === stack.count;
// Die gezeigte Karte (erste ungetappte) dreht sich nur, wenn alle getappt sind; in der kompakten
// Gegnerzeile (ohne Drehung) bleibt der Stapel aufrecht und traegt "N getappt".
const rotated = card.tapped || allTapped;
```

  Klassen: `.card` bekommt `tapped` bei `rotated` (statt `card.tapped`); `.card-slot` `tapped-slot`, `.card-frame` `tapped` ebenso bei `rotated`; zusätzlich `.card-slot` `stack-tapped-slot` wenn `stacked && stack.tapped > 0 && !allTapped` (Slot 1,4·w breit, Ebenen ragen heraus); Inline-Style am Slot `style={{ "--layers": layers } as CSSProperties}`.

  Ebenen vor dem `.card` im `.card-frame` rendern:

```tsx
{layers > 0 && (
  <div className="stack-layers" aria-hidden>
    {Array.from({ length: layers }, (_, i) => (
      <div key={i} className={"stack-layer" + (i < tappedLayers ? " tapped" : "")}
        style={{ "--i": layers - i } as CSSProperties}>
        {!noImg && <CardImage imageKey={card.imageKey} className="art" />}
      </div>
    ))}
  </div>
)}
```

  (`--i` = Tiefe: die erste gerenderte Ebene liegt am tiefsten; getappte Ebenen sind die tiefsten.)
  Badge `stack-tapped` nur noch rendern, wenn das Panel keine Drehung kennt: `stacked && stack.tapped > 0 && !allTapped` bleibt, CSS blendet es in `.bf-rows` aus (Ebenen zeigen es).

- [ ] **Step 2: CSS** – Stapel-Block ersetzen:

```css
/* Tabletop-Stapel (groups.ts): bis zu 4 Ebenen hinter der obersten Karte, je 5px nach oben/rechts versetzt;
   getappte Karten liegen als gedrehte, abgedunkelte Ebenen zuunterst und ragen links/rechts heraus. */
.card-frame.stacked { isolation: isolate; }
.stack-layers { position: absolute; inset: 0; z-index: -1; pointer-events: none; }
.stack-layer { position: absolute; inset: 0; border-radius: 7px; background: #0c1013; box-shadow: var(--shadow-card); overflow: hidden;
  transform: translate(calc(var(--i) * 5px), calc(var(--i) * -5px)); }
.stack-layer .art { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.stack-layer:not(:has(.art)) { border: 1px solid var(--line-2); background: linear-gradient(180deg, #232d35, #182027); }
/* Slot waechst um den Versatz nach rechts, damit die Ebenen nicht in den Nachbarn ragen. */
.card-slot:has(.stack-layers) { width: calc(var(--w) + var(--layers, 0) * 5px); justify-content: flex-start; }
/* Gemessene Reihen: getappte Ebenen gedreht (Slot 1,4 w breit, Karte mittig), Badge "n getappt" entfaellt. */
.bf-rows .card-slot.stack-tapped-slot { width: var(--h); justify-content: center; }
.bf-rows .stack-layer.tapped { transform: translate(calc(var(--i) * 5px), calc(var(--i) * -5px)) rotate(90deg); }
.bf-rows .stack-layer.tapped .art { filter: brightness(0.55) saturate(0.8); }
.bf-rows .tag.stack-tapped { display: none; }
/* Gegnerzeile (ohne Drehung): Ebenen aufrecht, getappte nur abgedunkelt, Badge bleibt. */
.player.compact:not(.spectator) .stack-layer.tapped .art { filter: brightness(0.55) saturate(0.8); }
.tag.stack-count { left: 3px; top: 3px; font-size: 11px; color: var(--gold); border-color: rgba(242, 193, 78, 0.5); background: rgba(24, 19, 6, 0.92); }
.tag.stack-tapped { left: 3px; bottom: 3px; color: var(--muted); font-weight: 600; }
.player.compact .bf-lands .tag.stack-tapped .word { display: none; }
.player.compact .bf-lands .tag.stack-tapped::after { content: "\27F3"; font-weight: 400; margin-left: 1px; }
```

  Die `@media (max-height: 800px)`-Regel für `.player.own .bf-lands .tag.stack-tapped` löschen (Badge in `.bf-rows` ohnehin aus). `.card-frame.stacked::before` (Schatten-Versatz) löschen. Hover-Lift (`.card-frame:hover { transform }`) hebt Ebenen mit, da sie im Frame liegen – gewollt.

- [ ] **Step 3: Screenshots** wie Task 2 Step 5 (`spectator-rows-1920.png`, `spectator-rows-1280.png`, `table-1600.png`, `table-1280.png`) neu erzeugen und ansehen. Sollzustand: Forest-Stapel mit sichtbaren Ebenen, getappte Ebenen gedreht darunter herausragend, „×N" oben links, kein „N getappt" in gemessenen Reihen; in der Gegnerzeile der Tischansicht Ebenen aufrecht mit Badge. Ebenen dürfen keine Nachbarkarte überdecken (Slot-Breite prüfen) und nicht aus dem Panel ragen.

- [ ] **Step 4: Layout-Check** (18 Läufe wie Task 2 Step 6) und `npm test`, `npm run build` grün. Falls Prüfung 3 (Clipping) bei gedrehten Ebenen oben anschlägt (Ebene ragt 5·4 = 20 px über die Karte hinaus): `.bf-rows .row { padding-top: 22px }` statt 2 px – Ebenen brauchen Luft nach oben; erneut prüfen.

- [ ] **Step 5: README** – Satz zu Stapeln ersetzen: „Gleiche Länder/Token liegen als Stapel (bis zu vier sichtbare Ebenen, ×N); getappte Karten des Stapels liegen gedreht darunter. Klick tappt die erste ungetappte." Abschnitt zum Spielfeld ergänzen: „Zuschauer-Panels und eigene Zone: Kreaturen oben, übrige bleibende Karten in der Mitte, Länder unten; die Kartengröße passt sich der Panelgröße an."

- [ ] **Step 6: Commit** – `git add web/src/components/CardBox.tsx web/src/styles.css README.md && git commit -m "ui: tabletop-stapel – ebenen hinter der karte, getappte gedreht darunter"`.

---

## Abschluss (Controller)

- Whole-branch-Review, Co-Author-Trailer aller Commits prüfen (`Claude Opus 5`), `git log main..HEAD`.
- Finale Screenshots `spectator-rows-1920.png` und `table-1600.png` an Kevin senden.
- `git checkout main && git merge --ff-only feature/ui-polish-4 && git push` (immer `&&`).
- Ledger `.superpowers/sdd/progress.md` und Memory (`mtg-player-projekt.md`) fortschreiben.
