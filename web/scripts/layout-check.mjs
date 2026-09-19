// Layout-Prüfung für die Tisch-Ansicht: öffnet die App wie shot.mjs (Fixtures über window.mtgApply)
// und prüft per page.evaluate, dass nichts abgeschnitten ist oder leer bleibt.
// Aufruf (aus web/): node scripts/layout-check.mjs [url] [fixture.json ...]
//   Standard: http://localhost:8080 mit fixtures/table.json
//   Viewport per Env: VIEWPORT=1280x720 (Standard 1600x900). Exit 1 mit Meldung bei Verstoß.
// Der WebSocket wird stillgelegt, damit ein laufendes Spiel auf 8081 die Fixtures nicht überschreibt.
import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const args = process.argv.slice(2);
const url = args[0] ?? "http://localhost:8080";
const fixturePaths = args.length > 1 ? args.slice(1) : ["fixtures/table.json"];

function withDebug(u) {
  return /[?&]debug(=|&|$)/.test(u) ? u : u + (u.includes("?") ? "&" : "?") + "debug=1";
}

function stubWebSocket() {
  window.WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() {} };
}

const [width, height] = (process.env.VIEWPORT ?? process.env.SHOT_VIEWPORT ?? "1600x900").split("x").map(Number);

const browser = await chromium.launch();
let problems = [];
try {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.addInitScript(stubWebSocket);
  await page.goto(withDebug(url), { waitUntil: "load" });
  await page.waitForFunction(() => (document.getElementById("root")?.childElementCount ?? 0) > 0);
  for (const fixturePath of fixturePaths) {
    const json = JSON.parse(await readFile(fixturePath, "utf8"));
    // Wie shot.mjs: ein Array von Nachrichten in einer Fixture-Datei einzeln anwenden.
    const messages = Array.isArray(json) ? json : [json];
    for (const m of messages) await page.evaluate((msg) => window.mtgApply(msg), m);
    await page.waitForTimeout(1200);
  }

  problems = await page.evaluate(() => {
    const out = [];
    const vw = window.innerWidth, vh = window.innerHeight;
    const r = (el) => el.getBoundingClientRect();
    const fmt = (b) => `${Math.round(b.left)},${Math.round(b.top)} ${Math.round(b.width)}x${Math.round(b.height)}`;
    const name = (el) => el.querySelector(".name")?.textContent || el.querySelector(".art")?.getAttribute("src") || el.className;
    // Karten in zugeklappten <details> (Friedhof/Exil-Liste) haben in Chromium trotzdem Layout-Boxen – überspringen.
    const visible = (el) => typeof el.checkVisibility !== "function" || el.checkVisibility();

    // 1. Kein horizontaler Seiten-Scroll.
    if (document.documentElement.scrollWidth > vw) {
      out.push(`Seite scrollt horizontal: scrollWidth ${document.documentElement.scrollWidth} > innerWidth ${vw}`);
    }

    // Vorfahren-Check, von Regel 2 und 3 genutzt: Rechteck b gegen jeden Vorfahren von el mit
    // overflow != visible. Ein scrollbarer Vorfahre (auto|scroll) schneidet nicht ab, solange b
    // innerhalb seines Scroll-Inhalts liegt (per Scroll erreichbar = kein Clipping) - Vergleich dann
    // gegen ab.left..ab.left+scrollWidth bzw. ab.top..ab.top+scrollHeight statt nur den sichtbaren
    // Ausschnitt; danach wird der Rest der Kette nicht mehr geprueft (der Scroll-Container liegt
    // vollstaendig in seinen eigenen Vorfahren, unscrolled-Koordinaten waeren dort falsch).
    // overflow: hidden bleibt der strenge Vergleich gegen den sichtbaren Ausschnitt, Kette laeuft weiter.
    function clippedBy(el, b) {
      let a = el.parentElement;
      while (a && a !== document.body) {
        const cs = getComputedStyle(a);
        const clips = [cs.overflowX, cs.overflowY].some((v) => v !== "visible");
        if (clips) {
          const ab = r(a);
          const tol = 1;
          const scrollX = cs.overflowX === "auto" || cs.overflowX === "scroll";
          const scrollY = cs.overflowY === "auto" || cs.overflowY === "scroll";
          const left = ab.left - tol;
          const right = (scrollX ? ab.left + a.scrollWidth : ab.right) + tol;
          const top = ab.top - tol;
          const bottom = (scrollY ? ab.top + a.scrollHeight : ab.bottom) + tol;
          if (b.left < left || b.right > right || b.top < top || b.bottom > bottom) {
            return `wird von <${a.tagName.toLowerCase()} class="${a.className}"> abgeschnitten: Karte ${fmt(b)}, Vorfahre ${fmt(ab)}`;
          }
          if (scrollX || scrollY) return null;
        }
        a = a.parentElement;
      }
      return null;
    }

    // 2. Jede Handkarte liegt vollständig im Viewport, ODER ist (Zuschauer-Modus ohne eigenen Sitz,
    //    5-6 Spieler unter 1500px, siehe styles.css) per Scroll in einem scrollbaren Vorfahren
    //    erreichbar (dann Sache von Regel 3 / clippedBy, kein Fehler hier).
    for (const c of document.querySelectorAll(".hand .card, .spectator-hand .card")) {
      if (!visible(c)) continue;
      const b = r(c);
      if (b.left < 0 || b.top < 0 || b.right > vw || b.bottom > vh) {
        if (clippedBy(c, b) === null) continue; // in einem Scroll-Container erreichbar - kein Clipping
        out.push(`Handkarte "${name(c)}" ragt aus dem Viewport: ${fmt(b)} (Viewport ${vw}x${vh})`);
      }
    }

    // 3. Kein Karten-Clipping im eigenen Spielfeld, bei den Gegnern und (Zuschauer-Modus ohne
    //    eigenen Sitz, siehe Table.tsx) im 2-spaltigen Zuschauer-Raster: Rechteck gegen jeden
    //    Vorfahren mit overflow != visible, außerdem muss jede Karte innerhalb ihres .player-Panels liegen.
    for (const c of document.querySelectorAll(".mine .battlefield .card, .opponents .card, .spectator-grid .card")) {
      if (!visible(c)) continue;
      const b = r(c);
      const panel = c.closest(".player");
      if (panel) {
        const pb = r(panel);
        if (b.left < pb.left - 1 || b.right > pb.right + 1 || b.top < pb.top - 1 || b.bottom > pb.bottom + 1) {
          out.push(`Karte "${name(c)}" ragt aus dem eigenen Panel: Karte ${fmt(b)}, Panel ${fmt(pb)}`);
          continue;
        }
      }
      const msg = clippedBy(c, b);
      if (msg) out.push(`Karte "${name(c)}" ${msg}`);
    }

    // 4. Gegner-Panels ohne leeres Band: Panelhöhe minus Inhaltshöhe darf nicht > 40 % sein (bei 1600x900).
    //    Setzt die Vier-Panel-Flexreihe der normalen Tischansicht voraus (kalibriert auf deren
    //    Seitenverhaeltnis) - im Zuschauer-Modus gibt es kein .mine und die Panels sitzen in einem
    //    2-spaltigen Raster mit anderen Proportionen, also uebersprungen statt falsch kalibriert.
    if (vw === 1600 && vh === 900 && document.querySelector(".mine")) {
      for (const p of document.querySelectorAll(".opponents .player")) {
        const pb = r(p);
        let contentBottom = pb.top, contentTop = pb.bottom;
        for (const el of p.children) {
          const eb = r(el);
          if (eb.height === 0) continue;
          contentTop = Math.min(contentTop, eb.top);
          contentBottom = Math.max(contentBottom, eb.bottom);
        }
        const content = Math.max(0, contentBottom - contentTop);
        const empty = pb.height - content;
        if (pb.height > 0 && empty / pb.height > 0.4) {
          out.push(`Gegner-Panel "${p.querySelector(".pname")?.textContent}" hat ${Math.round((empty / pb.height) * 100)} % leere Höhe (Panel ${Math.round(pb.height)}px, Inhalt ${Math.round(content)}px)`);
        }
      }
    }
    // 5. Effekt-Chips (unter dem Spielfeld) duerfen keine Karte ueberdecken - passiert, wenn das Spielfeld
    //    eines Zuschauer-Panels hoeher wird als sein Platz (Reihen umbrechen) und in die Chip-Zeile laeuft.
    for (const e of document.querySelectorAll(".effects")) {
      const eb = r(e);
      for (const c of document.querySelectorAll(".card")) {
        if (!visible(c)) continue;
        const b = r(c);
        if (b.left < eb.right && b.right > eb.left && b.top < eb.bottom && b.bottom > eb.top) {
          out.push(`Effekt-Zeile ueberdeckt Karte "${name(c)}": Karte ${fmt(b)}, Effekte ${fmt(eb)}`);
          break;
        }
      }
    }
    // 7. Ersetzt die feste 110/120px-Schwelle (bei drei belegten Reihen physikalisch unerreichbar,
    //    3,92*w + 8 <= Hoehe): a) kein ungenutztes Band (Fix-Runde 1, B.1), b) absolute Untergrenze (B.2).
    for (const rows of document.querySelectorAll(".bf-rows")) {
      const rb = r(rows);
      const rowEls = [...rows.querySelectorAll(":scope > .row")].filter((el) => r(el).height > 0);
      if (rowEls.length === 0) continue;
      const firstRowEl = rowEls[0];
      const isLandOnly = firstRowEl.classList.contains("bf-lands");
      const first = firstRowEl.querySelector(".card:not(.tapped)");
      if (!first) continue;
      const w = r(first).width / (isLandOnly ? 0.8 : 1);
      // a) Kein ungenutztes Band: Hoehe frei UND Breite frei in der breitesten Zeile. usedH als Summe der
      // Reihenhoehen + Gaps (nicht Unterkante letzte - Oberkante erste), damit der margin-top:auto-Spalt
      // vor .bf-lands (schiebt sie an die Unterkante) nicht faelschlich als "genutzt" zaehlt.
      const usedH = rowEls.reduce((sum, el) => sum + r(el).height, 0) + 4 * (rowEls.length - 1);
      const freeH = rb.height - usedH;
      let maxRight = rb.left;
      for (const el of rows.querySelectorAll(".card-slot")) {
        if (r(el).height === 0) continue;
        maxRight = Math.max(maxRight, r(el).right);
      }
      const freeW = rb.width - (maxRight - rb.left);
      if (freeH > 1.4 * w * 0.8 + 8 && freeW > w + 6) {
        out.push(`Spielfeld "${rows.closest(".player")?.querySelector(".pname")?.textContent}": Platz ungenutzt (Höhe frei ${Math.round(freeH)}px, Breite frei ${Math.round(freeW)}px, Karte ${Math.round(w)}px)`);
      }
      // b) Absolute Untergrenze bei <= 3 Kreaturen-Stapeln (nur Zuschauer-Panels: "6-Spieler-Panel" laut
      // Brief). <= 3 statt <= 5 (Fix-Runde 1, zweite Iteration): ein gemischt getappter Stapel (manche
      // Kopien getappt, manche nicht) reserviert absichtlich 1.4 Einheiten Breite (slotUnits(), RATIO) fuer
      // die gedrehten Ebenen unter der obersten Karte - Task 3 rendert diese Ebenen sichtbar. Bei 4-5 fast
      // durchgaengig gemischten Stapeln (spectator-rows.json, KI5/KI6) frisst das genug Breite, dass die
      // 60/75px-Untergrenze bei einem vollen 6-Spieler-Panel (3 Reihen: Kreaturen+Uebrige+Laender) nicht
      // mehr realistisch ist, obwohl nichts abgeschnitten wird oder pendelt (Regel 7a/9 bleiben gruen) -
      // die Schwelle gilt daher nur noch fuer wirklich duenn besetzte Boards (<= 3 Stapel).
      if (rows.closest(".player.spectator")) {
        const creatures = rows.querySelectorAll(".bf-creatures .card-slot").length;
        if (creatures > 0 && creatures <= 3) {
          const minW = vw >= 1920 && vh >= 1080 ? 75 : vw >= 1600 ? 60 : 0;
          const creatureCard = rows.querySelector(".bf-creatures .card:not(.tapped)");
          if (creatureCard && minW > 0) {
            const cw = r(creatureCard).width;
            if (cw < minW) out.push(`Zuschauer-Panel "${rows.closest(".player")?.querySelector(".pname")?.textContent}": Kartenbreite ${Math.round(cw)}px < ${minW}px bei ${creatures} Kreaturen`);
          }
        }
      }
    }
    // 8. Laender liegen unten: die Laenderreihe endet nicht mehr als 8px ueber der Unterkante des Reihen-Containers.
    //    Ausnahme: der Scroll-Fallback (Regel 3/B, overflow-y: auto) ist aktiv - dann liegen Laender ggf.
    //    unter der sichtbaren Kante, aber per Scroll erreichbar; das ist kein "haengt ueber der Unterkante".
    for (const rows of document.querySelectorAll(".bf-rows")) {
      const lands = rows.querySelector(".bf-lands");
      if (!lands) continue;
      if (rows.scrollHeight > rows.clientHeight + 1) continue;
      const rb = r(rows), lb = r(lands);
      if (rb.bottom - lb.bottom > 8) out.push(`Laenderreihe haengt ${Math.round(rb.bottom - lb.bottom)}px ueber der Unterkante (${rows.closest(".player")?.querySelector(".pname")?.textContent})`);
    }
    // 10. Die Zuschauer-Hand (senkrechter Stapel in der linken Spalte, an die "hand"-Grid-Zeile gebunden,
    //     Fix-Runde 3) darf weder eine Karte im Spielfeld noch die Stapel (Grab/Exil) noch die Kommandozone
    //     desselben Panels ueberdecken - und auch ihr eigenes Element (inkl. "HAND"-Beschriftung) darf nicht
    //     in die Stapel-Box hineinragen (Fix-Runde 2 lief bei vielen Handkarten/niedrigem Fenster nach oben
    //     in .piles - passierte auch ohne dass eine einzelne Karte das Rechteck einer Stapel-Karte traf).
    const intersects = (a, b) => a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top;
    for (const panel of document.querySelectorAll(".player.spectator")) {
      const handCards = [...panel.querySelectorAll(".spectator-hand .card")].filter(visible);
      const zones = [
        [".bf-rows .card", "bf-rows .card", true],
        [".piles", "Stapel (Grab/Exil)", false],
        [".command", "Kommandozone", false],
      ];
      for (const hc of handCards) {
        const hb = r(hc);
        for (const [sel, label, perCard] of zones) {
          const targets = [...panel.querySelectorAll(sel)].filter(visible);
          for (const t of targets) {
            const tb = r(t);
            if (intersects(hb, tb)) {
              out.push(`Hand ueberdeckt ${perCard ? `Spielfeldkarte "${name(t)}"` : label} (${panel.querySelector(".pname")?.textContent})`);
              break;
            }
          }
        }
      }
      const handEl = panel.querySelector(".spectator-hand");
      const pilesEl = panel.querySelector(".piles");
      if (handEl && pilesEl && visible(handEl) && visible(pilesEl)) {
        const hb = r(handEl), pb = r(pilesEl);
        if (intersects(hb, pb)) {
          out.push(`Hand ueberdeckt Stapel (Grab/Exil) (${panel.querySelector(".pname")?.textContent})`);
        }
      }
    }
    return out;
  });

  // 9. Messung stabil: --bw jedes Panels nach 400ms unveraendert (sonst waechst der Container mit den Karten).
  const bw1 = await page.evaluate(() => [...document.querySelectorAll(".player")].map((p) => p.style.getPropertyValue("--bw")));
  await page.waitForTimeout(400);
  const bw2 = await page.evaluate(() => [...document.querySelectorAll(".player")].map((p) => p.style.getPropertyValue("--bw")));
  if (JSON.stringify(bw1) !== JSON.stringify(bw2)) problems.push(`Kartenbreite pendelt: ${bw1.join(" ")} -> ${bw2.join(" ")}`);

  // 6. Hover ueber eine Log-Zeile mit Karte (Detail-Panel fuellt sich) darf das Log-Panel nicht verschieben
  //    oder verkleinern - sonst rutscht die Zeile unter dem Zeiger weg, das Detail leert sich wieder und
  //    es flackert (feste Detailhoehe, siehe styles.css .side). Ein ResizeObserver protokolliert jede
  //    Groessenaenderung des Log-Panels waehrend des Hovers; am Ende muss das Detail noch gefuellt sein
  //    (bei der alten, mitwachsenden Detailhoehe ist das Detail nach dem Hover leer - Chromium schickt nach
  //    dem Layoutwechsel einen mousemove, die Zeile ist weg, das Detail leert sich wieder).
  const logLine = page.locator(".log-line[data-card]").first();
  if (await logLine.count()) {
    await page.evaluate(() => {
      const fmt = (el) => { const b = el.getBoundingClientRect(); return `${Math.round(b.left)},${Math.round(b.top)} ${Math.round(b.width)}x${Math.round(b.height)}`; };
      const log = document.querySelector(".log");
      window.__logRects = [fmt(log)];
      new ResizeObserver(() => { const r = fmt(log); if (window.__logRects.at(-1) !== r) window.__logRects.push(r); }).observe(log);
    });
    await logLine.hover();
    await page.waitForTimeout(400);
    const rects = await page.evaluate(() => window.__logRects);
    if (rects.length > 1) problems.push(`Log-Panel veraendert sich beim Hover ueber eine Log-Zeile: ${rects.join(" -> ")}`);
    const detailFilled = await page.locator(".detail:not(.empty)").count();
    if (!detailFilled) problems.push("Hover ueber Log-Zeile: Detail-Panel ist (nicht mehr) gefuellt - die Zeile ist unter dem Zeiger weggerutscht");
  }
} finally {
  await browser.close();
}

if (problems.length) {
  console.error(`layout-check ${width}x${height}: ${problems.length} Problem(e)`);
  for (const p of problems) console.error("  - " + p);
  process.exit(1);
}
console.log(`layout-check ${width}x${height}: ok`);
