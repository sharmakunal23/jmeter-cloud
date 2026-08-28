import type { RunFleetMember } from "./api/runs";

/**
 * Browser-side configuration. In local-dev these are hardcoded to the
 * docker-compose port layout.
 */

/**
 * Best-effort URL that opens the local-orchestrator's
 * {@code /api/v1/logs?stream=jmeter&tail=200} endpoint for a given
 * fleet member. The {@code podBaseUrl} the global stores points at the
 * pod's in-cluster service name (e.g.,
 * {@code http://orchestrator-1:8080}); for a browser that lives on
 * the docker host, we rewrite container hosts to {@code localhost} +
 * the published port.
 *
 * <p>Local-dev convenience only. Production deployments front per-pod logs
 * through a centralized aggregator; the in-app tail is `LogTailPanel`.
 */
export function podLogTailUrl(member: RunFleetMember): string | null {
  if (!member.podBaseUrl) return null;
  let url = member.podBaseUrl;
  // Map docker-network hostnames → localhost host-ports.
  const replacements: Record<string, string> = {
    "http://orchestrator-1:8080": "http://localhost:8080",
    "http://orchestrator-2:8080": "http://localhost:8090",
  };
  if (replacements[url]) url = replacements[url];
  return `${url.replace(/\/$/, "")}/api/v1/logs?stream=jmeter&tail=200`;
}
