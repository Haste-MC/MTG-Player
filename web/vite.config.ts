import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { "/img": "http://localhost:8080" } },
  // css.include: matchListStyles.test.ts liest styles.css als Text (?raw); vitest leert CSS sonst.
  test: { environment: "node", css: { include: /styles\.css/ } },
});
