import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import "./styles.css";
// HM-2 — uPlot's own CSS for axes, legend, cursor crosshair. Loaded
// once globally; the bundled file is ~3 KB minified.
import "uplot/dist/uPlot.min.css";

const root = document.getElementById("root");
if (!root) throw new Error("missing #root mount point");

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
