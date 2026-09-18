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
    await page.evaluate((m) => window.mtgApply(m), json);
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

    // 2. Jede Handkarte liegt vollständig im Viewport (eigene Hand oder, im Zuschauer-Modus ohne
    //    eigenen Sitz, die sichtbaren Haende aller Spieler unter ihrem Spielfeld, siehe PlayerZone).
    for (const c of document.querySelectorAll(".hand .card, .spectator-hand .card")) {
      if (!visible(c)) continue;
      const b = r(c);
      if (b.left < 0 || b.top < 0 || b.right > vw || b.bottom > vh) {
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
      let a = c.parentElement;
      while (a && a !== document.body) {
        const cs = getComputedStyle(a);
        const clips = [cs.overflowX, cs.overflowY].some((v) => v !== "visible");
        if (clips) {
          const ab = r(a);
          const tol = 1;
          if (b.left < ab.left - tol || b.right > ab.right + tol || b.top < ab.top - tol || b.bottom > ab.bottom + tol) {
            out.push(`Karte "${name(c)}" wird von <${a.tagName.toLowerCase()} class="${a.className}"> abgeschnitten: Karte ${fmt(b)}, Vorfahre ${fmt(ab)}`);
            break;
          }
        }
        a = a.parentElement;
      }
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
    return out;
  });
} finally {
  await browser.close();
}

if (problems.length) {
  console.error(`layout-check ${width}x${height}: ${problems.length} Problem(e)`);
  for (const p of problems) console.error("  - " + p);
  process.exit(1);
}
console.log(`layout-check ${width}x${height}: ok`);
