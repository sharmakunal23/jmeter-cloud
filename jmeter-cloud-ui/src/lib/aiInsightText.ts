import type { CompareInsights, RunInsights } from "../api/ai";

/**
 * The AI panels' analysis as Markdown, for pasting into a ticket, a chat or an
 * email. Markdown because it renders in Jira/GitHub/Slack and still reads
 * cleanly as plain text where it does not.
 *
 * <p>The provenance footer is not decoration: once the text leaves the panel it
 * loses the disclaimer, the model that wrote it and the scope it covered, and a
 * pasted finding reads as fact. Every exporter here emits all three.
 */

/** Whole run, every label — the scope the digest is always built at. */
const RUN_SCOPE = "Whole run, every label.";
const COMPARE_SCOPE = "Whole run on both sides.";
const DISCLAIMER =
  "Advisory only — Claude can be wrong; check each figure against the charts.";

/** One run's insight. `evidence` is indented under its finding so the claim and its figure travel together. */
export function runInsightsMarkdown(data: RunInsights): string {
  const lines: string[] = [
    `# AI insights — run ${data.runId}`,
    "",
    data.summary,
  ];
  if (data.findings.length > 0) {
    lines.push("", "## Findings", "");
    data.findings.forEach((f, i) => {
      lines.push(`${i + 1}. **[${f.severity}] ${f.title}**`);
      if (f.detail) lines.push(`   ${f.detail}`);
      if (f.evidence) lines.push(`   Evidence: ${f.evidence}`);
      lines.push("");
    });
  }
  lines.push(...footer(RUN_SCOPE, data));
  return lines.join("\n");
}

/** A two-run comparison. Run order is the operator's submitted A, B — the narrative reads B against A. */
export function compareInsightsMarkdown(data: CompareInsights): string {
  const [idA = "?", idB = "?"] = data.runIds;
  const lines: string[] = [
    `# AI comparison — run A ${idA} vs run B ${idB}`,
    "",
    data.summary,
  ];
  if (data.findings.length > 0) {
    lines.push("", "## Findings", "");
    data.findings.forEach((f, i) => {
      const delta = f.delta ? ` (${f.delta})` : "";
      lines.push(`${i + 1}. **${f.metric} — ${f.verdict}**${delta}`);
      if (f.detail) lines.push(`   ${f.detail}`);
      if (f.evidence) lines.push(`   Evidence: ${f.evidence}`);
      lines.push("");
    });
  }
  lines.push(...footer(COMPARE_SCOPE, data));
  return lines.join("\n");
}

/**
 * Scope, provenance and the disclaimer. `cachedAt` is emitted as the raw ISO
 * instant rather than a locale string: pasted text outlives the session that
 * copied it and is read in other timezones.
 */
function footer(
  scope: string,
  data: Pick<RunInsights, "model" | "promptVersion" | "cachedAt" | "tokensIn" | "tokensOut">,
): string[] {
  return [
    "---",
    `${scope} Generated ${data.cachedAt} by ${data.model} (prompt ${data.promptVersion}), `
      + `${data.tokensIn}+${data.tokensOut} tokens.`,
    DISCLAIMER,
    "",
  ];
}
