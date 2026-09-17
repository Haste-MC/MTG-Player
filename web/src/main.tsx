import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import { connect } from "./ws";
import { useStore } from "./store";
import "./styles.css";

connect((m) => useStore.getState().apply(m));
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
