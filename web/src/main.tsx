import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { connect } from "./ws";
import { useStore } from "./store";
import type { Inbound } from "./protocol";
import "./styles.css";

connect((m) => useStore.getState().apply(m));

// Debug-Hook für Screenshot-Skripte: erlaubt das Einspielen von Zuständen ohne WebSocket.
// Nur im Dev-Server oder mit ?debug in der URL aktiv – landet nicht ungeschützt in der Produktion.
if (import.meta.env.DEV || location.search.includes("debug")) {
  (window as unknown as { mtgApply: (m: unknown) => void }).mtgApply = (m: unknown) => useStore.getState().apply(m as Inbound);
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
