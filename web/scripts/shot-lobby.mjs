// Screenshot der Lobby mit Klicks (Deck-Panel, Reiter, Kachel-Auswahl, Checkbox, Texteingabe) statt nur
// Fixture-Zustand - window.mtgApply liefert nur "lobby"/"state" etc., aber keine UI-Interaktion.
// Aufruf (aus web/): node scripts/shot-lobby.mjs <url> <out.png> [--open-panel] [--tab=saved] [--pick="Tinker Time (TDC)"]
//   [--url-value="https://…"] [--spectate]
// --open-panel oeffnet das Panel der ersten Deck-Kachel ("Dein Deck", im Zuschauer-Modus "KI 1"); --tab wechselt
// darin den Reiter; --pick klickt die Deck-Karte mit diesem Namen (schliesst das Panel, die Kachel zeigt das Deck).
import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const [, , url, out, ...rest] = process.argv;

if (!url || !out) {
  console.error("Usage: node scripts/shot-lobby.mjs <url> <out.png> [--open-panel] [--tab=<precons|saved|import>] [--pick=<Deckname>] [--url-value=<Text fürs erste Import-Eingabefeld>] [--spectate]");
  process.exit(1);
}

function flag(name) {
  const hit = rest.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : undefined;
}

const openPanel = rest.includes("--open-panel");
const tab = flag("tab");
const pickName = flag("pick");
const urlValue = flag("url-value");
const spectate = rest.includes("--spectate");
const TAB_LABEL = { precons: "Precons", saved: "Eigene Decks", import: "Import" };
if (tab && !TAB_LABEL[tab]) {
  console.error(`unbekannter Reiter: ${tab} (precons|saved|import)`);
  process.exit(1);
}

function withDebug(u) {
  return /[?&]debug(=|&|$)/.test(u) ? u : u + (u.includes("?") ? "&" : "?") + "debug=1";
}

const [widthArg, heightArg] = (process.env.VIEWPORT ?? process.env.SHOT_VIEWPORT ?? "1600x900").split("x").map(Number);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width: widthArg, height: heightArg }, deviceScaleFactor: 1 });
  // Attrappe, die nie verbindet (ws.ts wartet dann einfach) - die Lobby-Fixture ersetzt den Server.
  await page.addInitScript(() => {
    window.WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() {} };
  });
  await page.goto(withDebug(url), { waitUntil: "load" });
  await page.waitForFunction(() => (document.getElementById("root")?.childElementCount ?? 0) > 0);

  const json = JSON.parse(await readFile("fixtures/lobby.json", "utf8"));
  await page.evaluate((m) => window.mtgApply(m), json);
  await page.waitForTimeout(300);

  if (spectate) {
    await page.click("text=Nur KI – zuschauen");
    await page.waitForTimeout(300);
  }
  if (openPanel || tab || pickName) {
    // Erste Kachel ("Dein Deck" ohne Zuschauer-Modus, sonst "KI 1") - genügt für Screenshots mit einer Auswahl.
    await page.locator(".deck-tile").first().click();
    await page.locator(".deck-panel").waitFor();
    await page.waitForTimeout(200);
  }
  if (tab) {
    await page.locator(".deck-tabs .tab", { hasText: TAB_LABEL[tab] }).click();
    await page.waitForTimeout(200);
  }
  if (pickName) {
    await page.locator(".deck-card", { has: page.locator(".deck-card-name", { hasText: pickName }) }).first().click();
    await page.locator(".deck-panel").waitFor({ state: "detached" });
    await page.waitForTimeout(200);
  }
  if (urlValue) {
    // Erstes Eingabefeld des Import-Reiters (Textliste: Name, Archidekt: URL) - setzt --tab=import voraus.
    await page.locator(".deck-import .textdeck input").first().fill(urlValue);
    await page.waitForTimeout(200);
  }

  await page.screenshot({ path: out, fullPage: true });
  console.log(`geschrieben: ${out}`);
} finally {
  await browser.close();
}
