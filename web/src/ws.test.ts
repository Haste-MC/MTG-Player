import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Inbound } from "./protocol";

/**
 * ws.ts spricht mit der Bridge ueber WebSocket oder, wenn der nicht geht, ueber EventSource + POST.
 * Hier laufen beide Wege gegen Attrappen: kein Netz, kein Browser. Jeder Test laedt das Modul frisch
 * (vi.resetModules), weil ws.ts Zustand auf Modulebene haelt (Socket, Strom, Token, Schlange).
 */

class FakeWebSocket {
  static OPEN = 1;
  static instances: FakeWebSocket[] = [];
  static wirft = false;
  readyState = 0;
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  gesendet: string[] = [];
  constructor(public url: string) {
    // Ein Browser, der keinen WebSocket herausbekommt, wirft schon im Konstruktor.
    if (FakeWebSocket.wirft) throw new Error("Verbindung verweigert");
    FakeWebSocket.instances.push(this);
  }
  send(s: string) { this.gesendet.push(s); }
  oeffnen() { this.readyState = FakeWebSocket.OPEN; this.onopen?.(); }
}

class FakeEventSource {
  static instances: FakeEventSource[] = [];
  onmessage: ((ev: { data: string }) => void) | null = null;
  geschlossen = false;
  constructor(public url: string) { FakeEventSource.instances.push(this); }
  close() { this.geschlossen = true; }
  hello(token: string) { this.onmessage?.({ data: JSON.stringify({ type: "hello", token }) }); }
}

interface Eingabe { token: string; body: string }

/** Antworten der Attrappen-Bridge; pro Test umstellbar. */
let ports: () => Promise<unknown>;
let eingabeAntwort: (e: Eingabe) => Promise<{ status: number; ok: boolean }>;
let aufrufe: string[];
let eingaben: Eingabe[];

function antwort(status: number) {
  return Promise.resolve({ status, ok: status >= 200 && status < 300 });
}

function stubFetch() {
  return vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
    aufrufe.push(url);
    if (url === "/ports") return ports();
    if (url === "/ereignisse") return { status: 405, ok: false };   // "Rueckfallweg ist angemeldet"
    if (url.startsWith("/eingabe?token=")) {
      const e = { token: decodeURIComponent(url.slice("/eingabe?token=".length)), body: init?.body ?? "" };
      eingaben.push(e);
      return eingabeAntwort(e);
    }
    throw new Error("unerwartete Anfrage " + url);
  });
}

function stubUmgebung(search = "") {
  // Die Testumgebung ist node; ws.ts erwartet einen Browser und nimmt window.setTimeout.
  vi.stubGlobal("window", globalThis);
  vi.stubGlobal("location", { hostname: "tisch.test", search });
  vi.stubGlobal("WebSocket", FakeWebSocket);
  vi.stubGlobal("EventSource", FakeEventSource);
  vi.stubGlobal("fetch", stubFetch());
}

async function laden(search = "") {
  stubUmgebung(search);
  vi.resetModules();
  return import("./ws");
}

/** Laesst alle schon faelligen Promise-Ketten und Zeitgeber laufen. */
const abwarten = (ms = 0) => vi.advanceTimersByTimeAsync(ms);

/** Bringt den Client auf den Rueckfallweg: der Socket scheitert sofort, /ereignisse meldet 405. */
async function aufStromUmgestellt(search = "") {
  FakeWebSocket.wirft = true;
  const ws = await laden(search);
  const empfangen: Inbound[] = [];
  ws.connect((m) => empfangen.push(m));
  await abwarten();
  expect(FakeEventSource.instances).toHaveLength(1);
  FakeEventSource.instances[0].hello("t1");
  await abwarten();
  return { ws, empfangen, strom: FakeEventSource.instances[0] };
}

const typen = () => eingaben.map((e) => (JSON.parse(e.body) as { type: string }).type);

beforeEach(() => {
  vi.useFakeTimers();
  FakeWebSocket.instances = [];
  FakeWebSocket.wirft = false;
  FakeEventSource.instances = [];
  aufrufe = [];
  eingaben = [];
  ports = async () => ({ ok: true, json: async () => ({ ws: 9123 }) });
  eingabeAntwort = () => antwort(204);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("WebSocket-Port", () => {
  it("kommt von /ports: die gepackte App waehlt freie Ports, 8081 waere geraten", async () => {
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:9123"]);
  });

  it("?wsPort= schlaegt /ports und fragt gar nicht erst: ausdruecklich vor entdeckt", async () => {
    const ws = await laden("?wsPort=7000");
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:7000"]);
    expect(aufrufe).not.toContain("/ports");
  });

  it("?ws= schlaegt /ports und ?wsPort=", async () => {
    const ws = await laden("?ws=wss://anders.test/socket&wsPort=7000");
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["wss://anders.test/socket"]);
    expect(aufrufe).not.toContain("/ports");
  });

  it("ein ungueltiges ?wsPort= zaehlt nicht als ausdruecklich, /ports gilt", async () => {
    const ws = await laden("?wsPort=abc");
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:9123"]);
  });

  it.each([
    ["index.html statt JSON (aeltere Bridge, Datei-Server antwortet mit 200)",
      () => Promise.resolve({ ok: true, status: 200, json: async () => { throw new SyntaxError("<html>"); } })],
    ["HTTP 404 (die Bridge kennt /ports nicht)", () => Promise.resolve({ ok: false, status: 404, json: async () => ({}) })],
    ["Antwort ohne brauchbaren Port", () => Promise.resolve({ ok: true, status: 200, json: async () => ({ ws: "abc" }) })],
  ])("faellt bei %s auf 8081 zurueck: nur hier steht fest, dass es /ports nicht gibt", async (_name, antw) => {
    ports = antw as () => Promise<unknown>;
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:8081"]);
  });

  /**
   * /ports gibt es ab dem ersten Byte der Bridge. Ein Fehler HEISST also nicht "aeltere Bridge" - er heisst
   * "gerade nicht erreichbar". Wer dann 8081 raet, landet im Netz an der Bridge eines fremden Rechners
   * (das war der Fehler im Feld). Also fragen wir wieder, bis eine Antwort da ist.
   */
  it.each([
    ["Netzfehler", () => Promise.reject(new TypeError("Failed to fetch"))],
    ["HTTP 503", () => Promise.resolve({ ok: false, status: 503, json: async () => ({}) })],
    ["HTTP 500", () => Promise.resolve({ ok: false, status: 500, json: async () => ({}) })],
    ["abgebrochenem Lesen des Bodys (kein JSON-Fehler, sondern ein Abbruch)",
      () => Promise.resolve({ ok: true, status: 200, json: async () => { throw new TypeError("network error"); } })],
  ])("raet bei %s NICHT 8081, sondern fragt erneut und nimmt dann den echten Port", async (_name, antw) => {
    let versuche = 0;
    ports = () => (++versuche < 3 ? (antw as () => Promise<unknown>)() : Promise.resolve({ ok: true, status: 200, json: async () => ({ ws: 9123 }) }));
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances).toHaveLength(0);       // kein geratener Socket
    await abwarten(1000);
    expect(FakeWebSocket.instances).toHaveLength(0);       // zweiter Fehlschlag, immer noch nichts geraten
    await abwarten(1000);
    expect(versuche).toBe(3);
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:9123"]);
  });

  it("ein dauerhaft ausbleibendes /ports fuehrt nie zu einem Socket auf 8081", async () => {
    ports = () => Promise.reject(new TypeError("Failed to fetch"));
    const ws = await laden();
    ws.connect(() => { });
    await abwarten(30000);
    expect(FakeWebSocket.instances).toHaveLength(0);
    expect(aufrufe.filter((u) => u === "/ports").length).toBeGreaterThan(5);
  });

  it("der Socket wird erst NACH der Antwort von /ports geoeffnet", async () => {
    let freigeben!: () => void;
    ports = () => new Promise((res) => { freigeben = () => res({ ok: true, json: async () => ({ ws: 9123 }) }); });
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    expect(FakeWebSocket.instances).toHaveLength(0);
    freigeben();
    await abwarten();
    expect(FakeWebSocket.instances.map((s) => s.url)).toEqual(["ws://tisch.test:9123"]);
  });

  it("eine Eingabe geht ueber den offenen Socket raus", async () => {
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    FakeWebSocket.instances[0].oeffnen();
    ws.send({ type: "startGame" } as never);
    expect(FakeWebSocket.instances[0].gesendet.map((s) => JSON.parse(s).type)).toEqual(["requestState", "startGame"]);
  });
});

describe("Eingaben auf dem Rueckfallweg", () => {
  it("ein mit 404 abgewiesenes Token baut den Strom neu auf und schickt die Eingabe erneut", async () => {
    const { ws, strom } = await aufStromUmgestellt();
    eingabeAntwort = (e) => antwort(e.token === "t1" ? 404 : 204);

    ws.send({ type: "startGame" } as never);
    await abwarten();
    // Der Strom wurde ersetzt, die Eingabe wartet auf das neue Token.
    expect(strom.geschlossen).toBe(true);
    expect(FakeEventSource.instances).toHaveLength(2);
    FakeEventSource.instances[1].hello("t2");
    await abwarten();

    const start = eingaben.filter((e) => JSON.parse(e.body).type === "startGame");
    expect(start.map((e) => e.token)).toEqual(["t1", "t2"]);
  });

  it("Reihenfolge bleibt: die erneut gesendete Eingabe kommt vor der, die hinter ihr wartete", async () => {
    const { ws } = await aufStromUmgestellt();
    eingaben.length = 0;
    eingabeAntwort = (e) => antwort(e.token === "t1" ? 404 : 204);

    ws.send({ type: "startGame" } as never);
    ws.send({ type: "sparringCancel" } as never);
    await abwarten();
    FakeEventSource.instances[1].hello("t2");
    await abwarten();

    expect(typen().filter((t) => t !== "requestState")).toEqual(["startGame", "startGame", "sparringCancel"]);
    expect(eingaben.filter((e) => e.token === "t1")).toHaveLength(1);   // die zweite ging gleich mit t2 raus
  });

  it("nur EIN neuer Versuch: lehnt auch das frische Token ab, meldet der Client den Fehler statt zu kreisen", async () => {
    const { ws, empfangen } = await aufStromUmgestellt();
    eingabeAntwort = () => antwort(404);

    ws.send({ type: "startGame" } as never);
    await abwarten();
    FakeEventSource.instances[1].hello("t2");
    await abwarten(10000);

    expect(typen().filter((t) => t === "startGame")).toHaveLength(2);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(empfangen.some((m) => m.type === "error")).toBe(true);
  });

  it("kommt nach dem Neuaufbau kein hello, ist das eine Fehlermeldung und kein stilles Verschwinden", async () => {
    const { ws, empfangen } = await aufStromUmgestellt();
    eingabeAntwort = () => antwort(404);

    ws.send({ type: "startGame" } as never);
    await abwarten(30000);

    const fehler = empfangen.find((m) => m.type === "error") as { text: string } | undefined;
    expect(fehler?.text).toMatch(/startGame|Eingabe/);
    expect(typen().filter((t) => t === "startGame")).toHaveLength(1);
  });

  it("andere Fehlerstatus (400) werden gemeldet, ohne Neuaufbau und ohne zweiten Versuch", async () => {
    const { ws, empfangen } = await aufStromUmgestellt();
    eingabeAntwort = () => antwort(400);

    ws.send({ type: "startGame" } as never);
    await abwarten();

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(typen().filter((t) => t === "startGame")).toHaveLength(1);
    const fehler = empfangen.find((m) => m.type === "error") as { text: string } | undefined;
    expect(fehler?.text).toContain("400");
  });

  it("eine unterwegs verlorene Eingabe wird gemeldet, und die naechste geht trotzdem raus", async () => {
    const { ws, empfangen } = await aufStromUmgestellt();
    eingabeAntwort = (e) => JSON.parse(e.body).type === "startGame"
      ? Promise.reject(new TypeError("Failed to fetch")) : antwort(204);

    ws.send({ type: "startGame" } as never);
    ws.send({ type: "sparringCancel" } as never);
    await abwarten();

    expect(empfangen.some((m) => m.type === "error")).toBe(true);
    expect(typen()).toContain("sparringCancel");
  });

  /**
   * Waehrend des Neuaufbaus (bis zu mehrere Sekunden, bis das neue hello kommt) hat der Client kein Token.
   * Ein Klick in diesem Fenster darf nicht stillschweigend verschwinden - das ist genau die Fehlerklasse,
   * wegen der es den Neuaufbau gibt. Er wartet hinter dem Neuaufbau und geht mit dem neuen Token raus.
   */
  it("eine Eingabe WAEHREND des Neuaufbaus geht nicht verloren, sondern kommt mit dem neuen Token, und zwar in Reihenfolge", async () => {
    const { ws, empfangen } = await aufStromUmgestellt();
    eingaben.length = 0;
    eingabeAntwort = (e) => antwort(e.token === "t1" ? 404 : 204);

    ws.send({ type: "startGame" } as never);
    await abwarten();
    expect(FakeEventSource.instances).toHaveLength(2);       // Neuaufbau laeuft, das hello fehlt noch
    ws.send({ type: "sparringCancel" } as never);            // der Klick im Fenster ohne Token
    await abwarten();
    FakeEventSource.instances[1].hello("t2");
    await abwarten();

    const echt = eingaben.filter((e) => JSON.parse(e.body).type !== "requestState");
    expect(echt.map((e) => [JSON.parse(e.body).type, e.token])).toEqual([
      ["startGame", "t1"], ["startGame", "t2"], ["sparringCancel", "t2"],
    ]);
    expect(empfangen.some((m) => m.type === "error")).toBe(false);
  });

  it("eine Eingabe VOR dem ersten hello wartet darauf, statt zu verschwinden", async () => {
    FakeWebSocket.wirft = true;
    const ws = await laden();
    ws.connect(() => { });
    await abwarten();
    expect(FakeEventSource.instances).toHaveLength(1);       // Strom steht, hello noch nicht da

    ws.send({ type: "startGame" } as never);
    await abwarten();
    expect(eingaben).toEqual([]);                            // nichts ohne Token auf die Leitung
    FakeEventSource.instances[0].hello("t1");
    await abwarten();

    expect(eingaben.map((e) => [JSON.parse(e.body).type, e.token])).toContainEqual(["startGame", "t1"]);
  });

  it("kommt das hello nie, wird die wartende Eingabe als Fehler gemeldet statt verschluckt", async () => {
    FakeWebSocket.wirft = true;
    const ws = await laden();
    const empfangen: Inbound[] = [];
    ws.connect((m) => empfangen.push(m));
    await abwarten();

    ws.send({ type: "startGame" } as never);
    await abwarten(30000);

    expect(eingaben).toEqual([]);
    const fehler = empfangen.find((m) => m.type === "error") as { text: string } | undefined;
    expect(fehler?.text).toMatch(/startGame/);
  });

  /**
   * Die Sperre gegen Endlosschleifen darf sich nicht selbst festschalten: scheitert das erneute Senden
   * nach einem Neuaufbau aus einem ANDEREN Grund (hier 500), muss ein spaeteres echtes 404 die Seite
   * wieder heilen koennen - sonst hilft nur noch Neuladen.
   */
  it("scheitert das erneute Senden mit 500, baut ein spaeteres 404 den Strom trotzdem wieder neu auf", async () => {
    const { ws } = await aufStromUmgestellt();
    eingabeAntwort = (e) => antwort(e.token === "t1" ? 404 : 500);
    ws.send({ type: "startGame" } as never);
    await abwarten();
    FakeEventSource.instances[1].hello("t2");
    await abwarten();
    expect(FakeEventSource.instances).toHaveLength(2);

    eingabeAntwort = (e) => antwort(e.token === "t3" ? 204 : 404);   // die Bridge vergisst t2
    eingaben.length = 0;
    ws.send({ type: "sparringCancel" } as never);
    await abwarten();
    expect(FakeEventSource.instances).toHaveLength(3);
    FakeEventSource.instances[2].hello("t3");
    await abwarten();

    const abbruch = eingaben.filter((e) => JSON.parse(e.body).type === "sparringCancel");
    expect(abbruch.map((e) => e.token)).toEqual(["t2", "t3"]);
  });

  it("die Sperre gilt je Eingabe: dieselbe Eingabe baut nie zweimal neu, zwei verschiedene je einmal", async () => {
    const { ws } = await aufStromUmgestellt();
    eingabeAntwort = () => antwort(404);                      // die Bridge lehnt ausnahmslos alles ab
    ws.send({ type: "startGame" } as never);
    await abwarten();
    FakeEventSource.instances[1].hello("t2");
    await abwarten(10000);
    // Auch das requestState, das jedes hello selbst ausloest, darf keinen weiteren Neuaufbau anstossen.
    expect(FakeEventSource.instances).toHaveLength(2);

    ws.send({ type: "sparringCancel" } as never);             // eine NEUE Eingabe darf einmal neu bauen
    await abwarten();
    expect(FakeEventSource.instances).toHaveLength(3);
    FakeEventSource.instances[2].hello("t3");
    await abwarten(10000);
    expect(FakeEventSource.instances).toHaveLength(3);
  });

  it("ein Zuschauer schickt nichts", async () => {
    const { ws } = await aufStromUmgestellt();
    eingaben.length = 0;
    ws.setzeSteuerung(false);
    ws.send({ type: "startGame" } as never);
    await abwarten();
    expect(eingaben).toEqual([]);
  });
});
