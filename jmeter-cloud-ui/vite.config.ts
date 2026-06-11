/// <reference types="vitest" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Build target: nginx serves the static bundle on port 80 inside the
// container; the host port maps to ${UI_HTTP_PORT:-8086}. The dev server
// (npm run dev) listens on 5173 — only used when running outside docker.
export default defineConfig({
  plugins: [react()],
  server: {
    host: "0.0.0.0",
    port: 5173,
  },
  build: {
    outDir: "dist",
    sourcemap: true,
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/testSetup.ts"],
    css: false,
    // Project keeps .js siblings alongside every .ts/.tsx source from
    // earlier `tsc -b` runs — restrict vitest's pickup to the TS sources
    // so test counts don't double up.
    include: ["src/**/*.test.{ts,tsx}"],
  },
});
