// Screenshot des Statistik-Boards (Stats.tsx). Der Screen braucht mehr als einen Fixture-Zustand:
// erst Lobby + Partienliste einspielen, dann den Screen oeffnen, dann - wie die Bridge es taete - die
// Deckanalysen nachliefern und beim Aufklappen einer Partie deren Zeitachse.
// Aufruf (aus web/):
//   node scripts/shot-stats.mjs <url> <out.png> [--deck="Krenko Goblins"] [--format=all|duel|pod]
//                               [--no-explain] [--expand] [--no-analysis] [--suggest]
// --suggest klickt "Vorschläge laden" im Abschnitt Kartenvorschläge (Stueck 2, Suggestions.tsx) und
// spielt danach fixtures/statboard-suggestions.json ein, wie es die Bridge-Antwort auf suggestCards
// täte - der Socket ist stillgelegt, ohne den Klick bliebe der Abschnitt beim Knopf stehen. Das Fixture
// trägt den Decknamen "Krenko Goblins"; --suggest ergibt ohne --deck="Krenko Goblins" also den falschen
// (leeren) Abschnitt.
// Viewport per Env: VIEWPORT=1280x720 (Standard 1600x900).
// Der WebSocket wird stillgelegt (die Fixtures ersetzen die Bridge); Kartenbilder kommen weiter ueber
// /img von der laufenden Bridge, deshalb die Wartezeit vor dem Bild.
import { chromium } from "playwright";
import { readFile } from "node:fs/promises";

const [, , url, out, ...rest] = process.argv;
if (!url || !out) {
  console.error('Usage: node scripts/shot-stats.mjs <url> <out.png> [--deck=<Name>] [--format=all|duel|pod] [--no-explain] [--expand] [--no-analysis] [--suggest]');
  process.exit(1);
}

const flag = (name) => {
  const hit = rest.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.slice(name.length + 3) : undefined;
};
const deck = flag("deck");
const format = flag("format") ?? "all";
const explain = !rest.includes("--no-explain");
const expand = rest.includes("--expand");
const analysis = !rest.includes("--no-analysis");
const suggest = rest.includes("--suggest");
const FORMAT_LABEL = { all: "Alle", duel: "1 vs 1", pod: "Pod (3+)" };
if (!FORMAT_LABEL[format]) {
  console.error(`unbekanntes Format: ${format} (all|duel|pod)`);
  process.exit(1);
}

const withDebug = (u) => (/[?&]debug(=|&|$)/.test(u) ? u : u + (u.includes("?") ? "&" : "?") + "debug=1");
const json = async (path) => JSON.parse(await readFile(path, "utf8"));
const [width, height] = (process.env.VIEWPORT ?? process.env.SHOT_VIEWPORT ?? "1600x900").split("x").map(Number);

const browser = await chromium.launch();
try {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.addInitScript(() => {
    window.WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() {} };
  });
  await page.goto(withDebug(url), { waitUntil: "load" });
  await page.waitForFunction(() => (document.getElementById("root")?.childElementCount ?? 0) > 0);

  // Lobby (fuer die Commander-Bilder) und Partienliste - die Liste traegt wie bei der echten Bridge
  // KEINE Zeitachse.
  for (const m of await json("fixtures/statboard.json")) await page.evaluate((msg) => window.mtgApply(msg), m);
  await page.waitForTimeout(200);
  await page.click("text=Statistik");
  await page.locator(".statboard").waitFor();

  // Antwort der Bridge auf analyzeDeck (der Screen fragt beim Deckwechsel selbst, der Socket ist aber
  // stillgelegt). --no-analysis zeigt den Zustand ohne Deckanalyse.
  if (analysis) {
    for (const m of await json("fixtures/statboard-analysis.json")) await page.evaluate((msg) => window.mtgApply(msg), m);
  }
  if (format !== "all") {
    await page.locator(".sb-chip", { hasText: FORMAT_LABEL[format] }).click();
    await page.waitForTimeout(200);
  }
  if (deck) {
    await page.locator(".sb-deck", { hasText: deck }).first().click();
    await page.waitForTimeout(200);
  }
  if (suggest) {
    // Wie ein echter Klick: erst der Knopf, dann - weil der Socket stillgelegt ist - die Antwort von
    // Hand einspielen, genau wie die Bridge es auf suggestCards täte.
    await page.locator(".suggestions button", { hasText: "Vorschläge laden" }).click();
    await page.waitForTimeout(150);
    for (const m of await json("fixtures/statboard-suggestions.json")) await page.evaluate((msg) => window.mtgApply(msg), m);
    await page.waitForTimeout(200);
  }
  if (!explain) {
    await page.locator(".sb-explain input").uncheck();
    await page.waitForTimeout(200);
  }
  if (expand) {
    // Genau die Partie aufklappen, zu der das Detail-Fixture gehoert - danach das Detail einspielen,
    // wie es die Bridge auf matchDetail tun wuerde.
    const detail = await json("fixtures/statboard-detail.json");
    const entry = page.locator(`.match-entry[data-match="${detail.match.id}"]`);
    await entry.scrollIntoViewIfNeeded();
    await entry.locator(".match-toggle").click();
    await page.waitForTimeout(200);
    await page.evaluate((msg) => window.mtgApply(msg), detail);
    await page.waitForTimeout(300);
  }
  // Zurueck nach oben, damit jeder Vollbild-Screenshot denselben Ausgangszustand zeigt (--expand
  // scrollt vorher zu seiner Zeile).
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(1800);   // Kartenbilder der Bridge nachladen lassen
  await page.screenshot({ path: out, fullPage: true });
  console.log(`geschrieben: ${out}`);
} finally {
  await browser.close();
}
