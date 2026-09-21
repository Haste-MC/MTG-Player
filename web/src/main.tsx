import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { connect } from "./ws";
import { useStore } from "./store";
import type { Inbound } from "./protocol";
import "./styles.css";

connect((m) => useStore.getState().apply(m));

declare global {
  interface Window {
    /** Debug: eingehende Nachricht in den Store einspielen (wie vom WebSocket). */
    mtgApply?: (m: unknown) => void;
    /** Debug: der Zustand-Store selbst (fuer Screenshot-Skripte, z. B. noteStart/setBestOf). */
    mtgStore?: typeof useStore;
  }
}

// Debug-Hooks für Screenshot-Skripte: erlauben das Einspielen von Zuständen ohne WebSocket.
// Nur im Dev-Server oder mit ?debug in der URL aktiv – landen nicht ungeschützt in der Produktion.
if (import.meta.env.DEV || location.search.includes("debug")) {
  window.mtgApply = (m: unknown) => useStore.getState().apply(m as Inbound);
  window.mtgStore = useStore;
}
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
