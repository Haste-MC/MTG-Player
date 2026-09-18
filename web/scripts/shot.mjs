// Screenshot-Loop für Styling-Iterationen: öffnet die App, spielt Fixtures über
// window.mtgApply ein (kein WebSocket nötig) und schreibt einen Full-Page-Screenshot.
// Aufruf (aus web/): node scripts/shot.mjs <url> <out.png> [fixture.json ...]
import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const [, , url, out, ...fixturePaths] = process.argv;

if (!url || !out) {
  console.error("Usage: node scripts/shot.mjs <url> <out.png> [fixture.json ...]");
  process.exit(1);
}

// Der Debug-Hook (window.mtgApply) ist im Produktions-Build nur mit ?debug aktiv.
function withDebug(u) {
  return /[?&]debug(=|&|$)/.test(u) ? u : u + (u.includes("?") ? "&" : "?") + "debug=1";
}

const [widthArg, heightArg] = (process.env.SHOT_VIEWPORT ?? "1600x900").split("x").map(Number);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: widthArg, height: heightArg }, deviceScaleFactor: 1 });
  await page.goto(withDebug(url), { waitUntil: "load" });
  await page.waitForFunction(() => (document.getElementById("root")?.childElementCount ?? 0) > 0);

  for (const fixturePath of fixturePaths) {
    const json = JSON.parse(await readFile(fixturePath, "utf8"));
    await page.evaluate((m) => window.mtgApply(m), json);
    await page.waitForTimeout(1500); // Kartenbilder nachladen lassen
  }

  await page.screenshot({ path: out, fullPage: true });
  console.log(`geschrieben: ${out}`);
} finally {
  await browser.close();
}
