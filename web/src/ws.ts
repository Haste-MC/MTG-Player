import type { Inbound, Outbound } from "./protocol";

const params = new URLSearchParams(location.search);
// Beide Werte kommen aus der URL (vom Nutzer oder einem Link geteilt) - ungueltige Werte fallen auf
// die Standardverbindung zurueck statt new WebSocket() mit Muell aufzurufen.
const wsPortParam = params.get("wsPort");
const wsPort = wsPortParam && /^\d{1,5}$/.test(wsPortParam) ? wsPortParam : "8081";
const wsParam = params.get("ws");
const url = wsParam && /^wss?:\/\//.test(wsParam) ? wsParam : `ws://${location.hostname}:${wsPort}`;
let socket: WebSocket | undefined;
let listener: ((m: Inbound) => void) | undefined;

export function connect(onMessage: (m: Inbound) => void): void {
  listener = onMessage;
  open();
}

function open(): void {
  try {
    socket = new WebSocket(url);
  } catch {
    // z. B. ungueltige URL trotz obiger Validierung, oder der Browser verweigert die Verbindung sofort -
    // wie ein normales onclose behandeln und es spaeter erneut versuchen.
    setTimeout(open, 1000);
    return;
  }
  socket.onopen = () => send({ type: "requestState" });
  socket.onmessage = (ev) => listener?.(JSON.parse(ev.data) as Inbound);
  socket.onclose = () => setTimeout(open, 1000);
}

export function send(msg: Outbound): void {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}
