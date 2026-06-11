// SECURITY S-7 — regression guard pinning the "no raw-HTML sink" invariant.
//
// The UI relies entirely on React's auto-escaping for output encoding (the S-7
// audit found ZERO raw-HTML sinks). This script fails the build if one
// reappears, so a future change can't silently reintroduce an XSS vector.
//
// Dependency-free on purpose: the repo's lint is `tsc --noEmit` (no ESLint
// toolchain), so a tiny node scan keeps that minimalism rather than pulling
// eslint + eslint-plugin-react just for `react/no-danger`. Run via `npm run lint`.
//
// If you genuinely need raw HTML (e.g. a sanitized markdown renderer), don't
// delete this guard — route the HTML through a vetted sanitizer and add a
// narrow, reviewed exception here.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const SRC = join(fileURLToPath(new URL(".", import.meta.url)), "..", "src");

// Each: [regex, human reason]. Word boundaries avoid false hits (e.g. "eval"
// inside "retrieval"). `.innerHTML =` targets the assignment sink, not reads.
const FORBIDDEN = [
  [/dangerouslySetInnerHTML/, "dangerouslySetInnerHTML — render via React text instead"],
  [/\.innerHTML\s*=/, ".innerHTML assignment — set textContent or render via React"],
  [/insertAdjacentHTML/, "insertAdjacentHTML — raw HTML sink"],
  [/document\.write\s*\(/, "document.write — raw HTML sink"],
  [/\beval\s*\(/, "eval() — arbitrary code execution"],
  [/\bnew\s+Function\s*\(/, "new Function() — arbitrary code execution"],
];

// Skip tests (e.g. `document.body.innerHTML = ""` cleanup is harmless there).
const isTest = (p) => /(__tests__|\.test\.|\.spec\.)/.test(p);

function* sources(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) {
      yield* sources(p);
    } else if (/\.(ts|tsx)$/.test(p) && !isTest(p)) {
      yield p;
    }
  }
}

const violations = [];
for (const file of sources(SRC)) {
  const lines = readFileSync(file, "utf8").split("\n");
  lines.forEach((line, i) => {
    for (const [re, reason] of FORBIDDEN) {
      if (re.test(line)) violations.push(`${file}:${i + 1}  ${reason}`);
    }
  });
}

if (violations.length > 0) {
  console.error("SECURITY S-7 no-danger guard FAILED — raw-HTML / code-exec sink(s) found:\n");
  for (const v of violations) console.error("  " + v);
  console.error("\nSee scripts/checkNoDanger.mjs. Render operator/external text through React, never raw HTML.");
  process.exit(1);
}
console.log("S-7 no-danger guard: clean (no raw-HTML / code-exec sinks in src).");
