// Screenshot-Loop für Styling-Iterationen: öffnet die App, spielt Fixtures über
// window.mtgApply ein (kein WebSocket nötig) und schreibt einen Full-Page-Screenshot.
// Aufruf (aus web/): node scripts/shot.mjs <url> <out.png> [fixture.json ...]
// Viewport per Env: VIEWPORT=1280x720 (Standard 1600x900; SHOT_VIEWPORT wird weiter akzeptiert).
// Mit Fixtures wird der WebSocket stillgelegt, damit ein laufendes Spiel auf 8081 den Zustand nicht überschreibt.
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

/** Ersetzt window.WebSocket durch eine Attrappe, die nie verbindet (ws.ts wartet dann einfach). */
function stubWebSocket() {
  window.WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() {} };
}

const [widthArg, heightArg] = (process.env.VIEWPORT ?? process.env.SHOT_VIEWPORT ?? "1600x900").split("x").map(Number);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: widthArg, height: heightArg }, deviceScaleFactor: 1 });
  if (fixturePaths.length > 0) await page.addInitScript(stubWebSocket);
  await page.goto(withDebug(url), { waitUntil: "load" });
  await page.waitForFunction(() => (document.getElementById("root")?.childElementCount ?? 0) > 0);

  for (const fixturePath of fixturePaths) {
    const json = JSON.parse(await readFile(fixturePath, "utf8"));
    // Eine Fixture-Datei kann ein Array von Nachrichten sein (z. B. fixtures/log.json) - dann jede
    // einzeln anwenden statt des Arrays selbst, das window.mtgApply nicht kennt.
    const messages = Array.isArray(json) ? json : [json];
    for (const m of messages) await page.evaluate((msg) => window.mtgApply(msg), m);
    await page.waitForTimeout(1500); // Kartenbilder nachladen lassen
  }

  await page.screenshot({ path: out, fullPage: true });
  console.log(`geschrieben: ${out}`);
} finally {
  await browser.close();
}
