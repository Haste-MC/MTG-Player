import type { Inbound, Outbound } from "./protocol";

const url = `ws://${location.hostname}:8081`;
let socket: WebSocket | undefined;
let listener: ((m: Inbound) => void) | undefined;

export function connect(onMessage: (m: Inbound) => void): void {
  listener = onMessage;
  open();
}

function open(): void {
  socket = new WebSocket(url);
  socket.onmessage = (ev) => listener?.(JSON.parse(ev.data) as Inbound);
  socket.onclose = () => setTimeout(open, 1000);
}

export function send(msg: Outbound): void {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(msg));
  }
}
