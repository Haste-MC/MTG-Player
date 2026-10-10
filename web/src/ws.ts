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
 * eigenen Port.</p>
 *
 * <p>Auf 8081 zurueck faellt es NUR, wenn /ports nachweislich nicht existiert (eine aeltere Bridge):
 * HTTP 404, oder 200 mit etwas, das kein JSON ist (der Datei-Server liefert dort die index.html). Ein
 * Netzfehler, ein 5xx oder ein abgebrochenes Lesen beweisen das nicht - /ports antwortet ab dem ersten
 * Byte der Bridge. Dann liefert diese Funktion {@code undefined}, und der Aufrufer fragt spaeter erneut.
 * Ein geratener 8081 nach einem einzigen Aussetzer waere genau der Fehler, der an einer fremden Bridge
 * landete.</p>
 *
 * <p>Wird bei jedem Verbindungsversuch neu gefragt und nichts gemerkt: waehrend die Bridge hochfaehrt
 * (Forge liest die Karten), liefert /ports noch die index.html - das waere sonst fuer immer als
 * "aeltere Bridge" im Gedaechtnis.</p>
 */
async function socketUrl(): Promise<string | undefined> {
  const params = new URLSearchParams(location.search);
  const wsParam = params.get("ws");
  if (wsParam && /^wss?:\/\//.test(wsParam)) return wsParam;
  const wsPortParam = params.get("wsPort");
  const port = wsPortParam && /^\d{1,5}$/.test(wsPortParam) ? wsPortParam : await entdeckterPort();
  if (port === undefined) return undefined;
  return `ws://${location.hostname}:${port}`;
}

async function entdeckterPort(): Promise<string | undefined> {
  let res: Response;
  try {
    res = await fetch("/ports");
  } catch {
    return undefined;                              // Netzfehler: keine Antwort, also kein Beweis
  }
  if (res.status === 404) return STANDARD_WS_PORT; // die Bridge kennt /ports nicht: aeltere Bridge
  if (!res.ok) return undefined;                   // 5xx u. a.: die Bridge ist da, aber gerade nicht in Form
  try {
    const ws = ((await res.json()) as { ws?: unknown } | null)?.ws;
    return typeof ws === "number" && Number.isInteger(ws) && ws > 0 && ws < 65536 ? String(ws) : STANDARD_WS_PORT;
  } catch (e) {
    // Ein SyntaxError heisst: es kam etwas an, das kein JSON ist - die index.html des Datei-Servers, also
    // eine aeltere Bridge. Jeder andere Fehler (Abbruch beim Lesen des Bodys) ist ein Aussetzer.
    return e instanceof SyntaxError ? STANDARD_WS_PORT : undefined;
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
    // Das requestState gehoert nicht dem Nutzer und baut den Strom nie neu auf: sonst loeste jedes hello
    // bei einer Bridge, die dauernd 404 sagt, den naechsten Neuaufbau aus - eine Endlosschleife.
    schicke({ type: "requestState" }, false);
    return;
  }
  listener?.(m as Inbound);
}

async function open(): Promise<void> {
  if (strom) return;                             // auf dem Rueckfallweg bleiben wir
  const url = await socketUrl();
  if (strom) return;                             // waehrend der Frage nach dem Port umgeschaltet
  if (url === undefined) {
    // /ports hat nicht geantwortet. Nicht raten (siehe socketUrl), sondern mit demselben Zeitgeber wie
    // nach einem gescheiterten Socket noch einmal fragen.
    setTimeout(open, 1000);
    return;
  }
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
  const hello = warteAufHello();
  oeffneStrom();
  return hello;
}

/** Das naechste Token, oder {@code undefined}, wenn binnen {@link HELLO_FRIST_MS} keins kommt. */
function warteAufHello(): Promise<string | undefined> {
  return new Promise<string | undefined>((fertig) => {
    const frist = setTimeout(() => { aufHello = undefined; fertig(undefined); }, HELLO_FRIST_MS);
    aufHello = (t) => { clearTimeout(frist); aufHello = undefined; fertig(t); };
  });
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
  schicke(msg, true);
}

/**
 * @param darfNeuBauen darf ein 404 auf diese Eingabe den Strom neu aufbauen? Je Eingabe hoechstens einmal
 *        (siehe {@link liefere}); das vom hello selbst ausgeloeste requestState gar nicht.
 */
function schicke(msg: Outbound, darfNeuBauen: boolean): void {
  // Zuschauer schicken nichts: die Bridge wuerde es ohnehin verwerfen, und so bleibt die Leitung
  // ruhig. takeControl ist die eine Ausnahme - sonst kaeme man nie an die Steuerung.
  if (!steuert && msg.type !== "takeControl") {
    return;
  }
  if (strom) {
    // Auch ohne Token (vor dem ersten hello, oder mitten im Neuaufbau) wird eingereiht, nicht verworfen:
    // ein Klick in diesem Fenster waere sonst genau die stille Eingabe, die es nicht geben soll. liefere()
    // liest das Token erst, wenn die Eingabe an der Reihe ist, und wartet notfalls auf das hello.
    const rumpf = JSON.stringify(msg);
    schlange = schlange.then(() => liefere(msg.type, rumpf, darfNeuBauen));
    return;
  }
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}

/**
 * Eine Eingabe auf dem Rueckfallweg zustellen. Das Token wird erst hier gelesen, nicht beim send():
 * ein Neuaufbau weiter vorn in der Schlange hat es womoeglich erneuert - oder es fehlt noch ganz, dann
 * wartet die Eingabe auf das hello.
 *
 * <p>Nichts davon darf still enden - eine verschluckte Eingabe sieht fuer den Nutzer aus wie ein toter
 * Knopf ("Spiel starten" tut nichts, fuer immer). Also: bei 404 einmal neu aufbauen und erneut senden,
 * jeder andere Misserfolg wird als Fehlerzeile gemeldet (wie ein error der Bridge, siehe store).</p>
 *
 * <p>"Einmal" gilt je Eingabe (der lokale Zustand dieses Aufrufs), nicht global: eine globale Sperre, die
 * nur ein Erfolg loest, schaltete die Heilung fuer immer ab, sobald das erneute Senden aus einem anderen
 * Grund scheitert. Gegen die Endlosschleife reicht es, dass eine Eingabe nur einmal neu baut.</p>
 */
async function liefere(art: string, rumpf: string, darfNeuBauen: boolean): Promise<void> {
  const erstes = token ?? await warteAufHello();
  if (!erstes) {
    meldeFehler(`Eingabe „${art}“ nicht zugestellt: die Bridge hat sich noch nicht gemeldet (kein hello). Seite neu laden.`);
    return;
  }
  let res = await sende(erstes, rumpf);
  if (res === 404 && darfNeuBauen) {
    const frisch = await baueStromNeu(erstes);
    if (!frisch) {
      meldeFehler(`Eingabe „${art}“ nicht zugestellt: die Bridge kennt diese Verbindung nicht mehr und meldet sich nicht neu. Seite neu laden.`);
      return;
    }
    res = await sende(frisch, rumpf);
  }
  if (typeof res === "number" && res >= 200 && res < 300) {
    return;
  } else if (res === "netz") {
    meldeFehler(`Eingabe „${art}“ nicht zugestellt: keine Verbindung zur Bridge.`);
  } else {
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
