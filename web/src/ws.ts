import type { Inbound, Outbound } from "./protocol";

const params = new URLSearchParams(location.search);
// Beide Werte kommen aus der URL (vom Nutzer oder einem Link geteilt) - ungueltige Werte fallen auf
// die Standardverbindung zurueck statt new WebSocket() mit Muell aufzurufen.
const wsPortParam = params.get("wsPort");
const wsPort = wsPortParam && /^\d{1,5}$/.test(wsPortParam) ? wsPortParam : "8081";
const wsParam = params.get("ws");
const url = wsParam && /^wss?:\/\//.test(wsParam) ? wsParam : `ws://${location.hostname}:${wsPort}`;

/** So lange bekommt der WebSocket Zeit, bevor der Rueckfallweg geprueft wird. */
const SOCKET_FRIST_MS = 3000;

let socket: WebSocket | undefined;
let listener: ((m: Inbound) => void) | undefined;
/** Auf dem Rueckfallweg: Token aus der hello-Nachricht, ohne das eine Eingabe niemandem gehoert. */
let token: string | undefined;
let strom: EventSource | undefined;

export function connect(onMessage: (m: Inbound) => void): void {
  listener = onMessage;
  open();
}

function melde(roh: string): void {
  const m = JSON.parse(roh) as Inbound | { type: "hello"; token: string };
  if (m.type === "hello") {
    // Erst mit dem Token lassen sich Eingaben schicken - und der Zustand holen.
    token = (m as { token: string }).token;
    send({ type: "requestState" });
    return;
  }
  listener?.(m as Inbound);
}

function open(): void {
  if (strom) return;                             // auf dem Rueckfallweg bleiben wir
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
  strom = new EventSource("/ereignisse");
  strom.onmessage = (ev) => melde(ev.data);
  // EventSource verbindet nach einem Fehler von selbst neu; die Bridge schickt dabei ein neues
  // hello, und damit stimmt auch das Token wieder. Nichts zu tun.
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
    const t = token;
    schlange = schlange.then(() => fetch("/eingabe?token=" + encodeURIComponent(t), {
      method: "POST", body: rumpf,
    }).catch(() => { /* eine verlorene Eingabe darf die Schlange nicht anhalten */ }));
    return;
  }
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}
