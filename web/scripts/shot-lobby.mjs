// Screenshot der Lobby mit Klicks (Picker-Auswahl, Checkbox, Texteingabe) statt nur Fixture-Zustand -
// window.mtgApply liefert nur "lobby"/"state" etc., aber keine UI-Interaktion (Select-Wert, Eingabefeld).
// Aufruf (aus web/): node scripts/shot-lobby.mjs <url> <out.png> [--select=archidekt] [--url-value="https://…"]
import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const [, , url, out, ...rest] = process.argv;

if (!url || !out) {
  console.error("Usage: node scripts/shot-lobby.mjs <url> <out.png> [--select=<option value für \"Dein Deck\">] [--url-value=<Text fürs erste Eingabefeld>] [--spectate]");
  process.exit(1);
}

function flag(name) {
  const hit = rest.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : undefined;
}

const selectValue = flag("select");
const urlValue = flag("url-value");
const spectate = rest.includes("--spectate");

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
  if (selectValue) {
    // Erster Picker ("Dein Deck" ohne Zuschauer-Modus, sonst "KI 1") - genügt für Screenshots mit einer Auswahl.
    await page.locator(".lobby-section .pick select").first().selectOption(selectValue);
    await page.waitForTimeout(200);
  }
  if (urlValue) {
    await page.locator(".lobby-section .textdeck input").first().fill(urlValue);
    await page.waitForTimeout(200);
  }

  await page.screenshot({ path: out, fullPage: true });
  console.log(`geschrieben: ${out}`);
} finally {
  await browser.close();
}
