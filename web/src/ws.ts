import type { Inbound, Outbound } from "./protocol";

/** Rueckfall, wenn weder die URL noch /ports einen Port nennen: der Standard einer von Hand gestarteten Bridge. */
const STANDARD_WS_PORT = "8081";

/**
 * Wohin der WebSocket geht. Reihenfolge: ausdruecklich (URL) vor entdeckt (/ports) vor geraten (8081).
 *
 * <p>Die URL-Werte kommen vom Nutzer oder aus einem geteilten Link - ungueltige Werte zaehlen als nicht
 * gegeben, statt new WebSocket() mit Muell aufzurufen.</p>
 *
 * <p>Warum /ports: die gepackte App waehlt freie Ports, 8081 ist dort falsch - und im Netz landet ein
 * geratener 8081 leicht an einer FREMDEN Bridge (so lief einmal das Spiel eines Kollegen auf dem Rechner
 * des Entwicklers). Die Bridge liefert die Seite aus, also nennt sie auf demselben Ursprung ihren
 * eigenen Port. Eine aeltere Bridge kennt /ports nicht; dann bleibt es beim bisherigen 8081.</p>
 *
 * <p>Wird bei jedem Verbindungsversuch neu gefragt und nichts gemerkt: waehrend die Bridge hochfaehrt
 * (Forge liest die Karten), liefert /ports noch die index.html - das waere sonst fuer immer als
 * "aeltere Bridge" im Gedaechtnis.</p>
 */
async function socketUrl(): Promise<string> {
  const params = new URLSearchParams(location.search);
  const wsParam = params.get("ws");
  if (wsParam && /^wss?:\/\//.test(wsParam)) return wsParam;
  const wsPortParam = params.get("wsPort");
  const port = wsPortParam && /^\d{1,5}$/.test(wsPortParam) ? wsPortParam : await entdeckterPort();
  return `ws://${location.hostname}:${port}`;
}

async function entdeckterPort(): Promise<string> {
  try {
    const res = await fetch("/ports");
    if (!res.ok) return STANDARD_WS_PORT;
    const ws = ((await res.json()) as { ws?: unknown }).ws;
    return typeof ws === "number" && Number.isInteger(ws) && ws > 0 && ws < 65536 ? String(ws) : STANDARD_WS_PORT;
  } catch {
    // Netzfehler, oder die Antwort war die index.html des Datei-Servers (kein JSON): aeltere Bridge.
    return STANDARD_WS_PORT;
  }
}

/** So lange bekommt der WebSocket Zeit, bevor der Rueckfallweg geprueft wird. */
const SOCKET_FRIST_MS = 3000;

let socket: WebSocket | undefined;
let listener: ((m: Inbound) => void) | undefined;
/** Auf dem Rueckfallweg: Token aus der hello-Nachricht, ohne das eine Eingabe niemandem gehoert. */
let token: string | undefined;
let strom: EventSource | undefined;
/** Wartet jemand (der Neuaufbau nach einem 404) auf das naechste hello? Dann bekommt er dessen Token. */
let aufHello: ((t: string) => void) | undefined;

/**
 * Wurde der Strom neu gebaut, und seither ging noch keine Eingabe durch? Dann baut ein weiteres 404 ihn
 * NICHT noch einmal: sonst loeste jedes hello (es schickt selbst ein requestState) bei einer Bridge, die
 * dauernd 404 sagt, den naechsten Neuaufbau aus - eine Endlosschleife statt einer Fehlermeldung.
 */
let stromFrisch = false;

/** So lange wartet ein Neuaufbau des Stroms auf das hello, bevor die Eingabe als verloren gilt. */
const HELLO_FRIST_MS = 8000;

export function connect(onMessage: (m: Inbound) => void): void {
  listener = onMessage;
  open();
}

function melde(roh: string): void {
  const m = JSON.parse(roh) as Inbound | { type: "hello"; token: string };
  if (m.type === "hello") {
    // Erst mit dem Token lassen sich Eingaben schicken - und der Zustand holen.
    token = (m as { token: string }).token;
    aufHello?.(token);
    send({ type: "requestState" });
    return;
  }
  listener?.(m as Inbound);
}

async function open(): Promise<void> {
  if (strom) return;                             // auf dem Rueckfallweg bleiben wir
  const url = await socketUrl();
  if (strom) return;                             // waehrend der Frage nach dem Port umgeschaltet
  try {
    socket = new WebSocket(url);
  } catch {
    // z. B. ungueltige URL trotz obiger Validierung, oder der Browser verweigert die Verbindung sofort -
    // wie ein normales onclose behandeln und es spaeter erneut versuchen.
    spaeterNochmal();
    return;
  }
  const frist = window.setTimeout(() => {
    if (socket?.readyState !== WebSocket.OPEN) spaeterNochmal();
  }, SOCKET_FRIST_MS);
  socket.onopen = () => { window.clearTimeout(frist); send({ type: "requestState" }); };
  socket.onmessage = (ev) => melde(ev.data as string);
  socket.onclose = () => { window.clearTimeout(frist); spaeterNochmal(); };
}

/**
 * Der Socket kam nicht zustande. Zwei Gruende sind moeglich, und sie brauchen Gegenteiliges:
 *
 * - Die Bridge faehrt noch hoch (Forge liest ~30.000 Karten, der WebSocket horcht erst danach).
 *   Dann waere ein Umschalten falsch: gleich geht der bessere Weg.
 * - Der Browser bekommt gar keinen WebSocket heraus (eingebaute Browser mit einer Freigabe fuer
 *   genau Name und Port der Seite). Dann hilft nur der Rueckfallweg.
 *
 * Unterschieden wird an der Bridge selbst: ist /ereignisse angemeldet, antwortet es auf POST mit
 * 405. Ist es das nicht, liefert der Datei-Server die index.html mit 200 - die Bridge ist also noch
 * nicht so weit.
 */
async function spaeterNochmal(): Promise<void> {
  if (strom) return;
  socket = undefined;
  if (await rueckfallBereit()) {
    starteStrom();
    return;
  }
  setTimeout(open, 1000);
}

async function rueckfallBereit(): Promise<boolean> {
  try {
    const res = await fetch("/ereignisse", { method: "POST" });
    return res.status === 405;
  } catch {
    return false;
  }
}

function starteStrom(): void {
  if (strom) return;
  oeffneStrom();
  // EventSource verbindet nach einem Fehler von selbst neu; die Bridge schickt dabei ein neues
  // hello, und damit stimmt auch das Token wieder. Nur wenn die Bridge das Token zwischendurch
  // vergessen hat (siehe baueStromNeu), hilft das Selbstheilen nicht - dort greift der Neuaufbau.
}

function oeffneStrom(): void {
  strom = new EventSource("/ereignisse");
  strom.onmessage = (ev) => melde(ev.data);
}

/**
 * Die Bridge hat das Token abgewiesen (404): sie kennt diesen Strom nicht mehr, etwa weil er
 * abgerissen ist und der EventSource noch nicht neu verbunden hat, oder weil die Bridge neu gestartet
 * wurde. Ein frischer Strom bringt ein frisches Token. Liefert es, oder {@code undefined}, wenn kein
 * hello kam.
 *
 * <p>Hat schon ein frueherer Neuaufbau (dieselbe Warteschlange, also nacheinander) das Token erneuert,
 * wird nichts mehr gebaut: {@code altesToken} ist dann nicht mehr das aktuelle.</p>
 */
async function baueStromNeu(altesToken: string): Promise<string | undefined> {
  if (token !== undefined && token !== altesToken) return token;
  strom?.close();
  token = undefined;
  stromFrisch = true;
  const hello = new Promise<string | undefined>((fertig) => {
    const frist = setTimeout(() => { aufHello = undefined; fertig(undefined); }, HELLO_FRIST_MS);
    aufHello = (t) => { clearTimeout(frist); aufHello = undefined; fertig(t); };
  });
  oeffneStrom();
  return hello;
}

/**
 * Steuert dieser Browser? Wird aus der role-Nachricht gesetzt (siehe store). Vor der ersten Antwort
 * der Bridge true, damit nichts haengt, falls eine aeltere Bridge gar keine role-Nachricht schickt.
 */
let steuert = true;

export function setzeSteuerung(wert: boolean): void {
  steuert = wert;
}

/** Eingaben auf dem Rueckfallweg gehen nacheinander raus, damit die Reihenfolge stimmt. */
let schlange: Promise<unknown> = Promise.resolve();

export function send(msg: Outbound): void {
  // Zuschauer schicken nichts: die Bridge wuerde es ohnehin verwerfen, und so bleibt die Leitung
  // ruhig. takeControl ist die eine Ausnahme - sonst kaeme man nie an die Steuerung.
  if (!steuert && msg.type !== "takeControl") {
    return;
  }
  if (strom) {
    if (!token) return;                          // vor dem hello gibt es niemanden, dem das gehoert
    const rumpf = JSON.stringify(msg);
    schlange = schlange.then(() => liefere(msg.type, rumpf));
    return;
  }
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}

/**
 * Eine Eingabe auf dem Rueckfallweg zustellen. Das Token wird erst hier gelesen, nicht beim send():
 * ein Neuaufbau weiter vorn in der Schlange hat es womoeglich erneuert.
 *
 * <p>Nichts davon darf still enden - eine verschluckte Eingabe sieht fuer den Nutzer aus wie ein toter
 * Knopf ("Spiel starten" tut nichts, fuer immer). Also: bei 404 einmal neu aufbauen und erneut senden,
 * jeder andere Misserfolg wird als Fehlerzeile gemeldet (wie ein error der Bridge, siehe store).</p>
 */
async function liefere(art: string, rumpf: string): Promise<void> {
  const erstes = token;
  if (!erstes) return;
  let res = await sende(erstes, rumpf);
  if (res === 404 && !stromFrisch) {
    const frisch = await baueStromNeu(erstes);
    if (!frisch) {
      meldeFehler(`Eingabe „${art}“ nicht zugestellt: die Bridge kennt diese Verbindung nicht mehr und meldet sich nicht neu. Seite neu laden.`);
      return;
    }
    res = await sende(frisch, rumpf);
  }
  if (typeof res === "number" && res >= 200 && res < 300) {
    stromFrisch = false;
  } else if (res === "netz") {
    meldeFehler(`Eingabe „${art}“ nicht zugestellt: keine Verbindung zur Bridge.`);
  } else if (typeof res === "number") {
    meldeFehler(`Eingabe „${art}“ von der Bridge abgewiesen (HTTP ${res}).`);
  }
}

/** Statuscode der Antwort, oder "netz", wenn gar keine kam. */
async function sende(t: string, rumpf: string): Promise<number | "netz"> {
  try {
    const res = await fetch("/eingabe?token=" + encodeURIComponent(t), { method: "POST", body: rumpf });
    return res.status;
  } catch {
    return "netz";
  }
}

function meldeFehler(text: string): void {
  listener?.({ type: "error", text });
}
