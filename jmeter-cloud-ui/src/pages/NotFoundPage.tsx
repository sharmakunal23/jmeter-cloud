import { Link, useLocation } from "react-router-dom";

/**
 * Track UI-D1. Replaces the previous {@code Navigate to="/"} catchall —
 * the IA cutover hard-removed legacy URLs ({@code /runs}, {@code /blobs},
 * {@code /runs/new}), so a hit on one of those paths gets a clear
 * "this URL moved" page instead of a silent redirect that hides the
 * rename.
 */
export function NotFoundPage() {
  const location = useLocation();
  const path = location.pathname;
  const hint = legacyRedirectHint(path);

  return (
    <section className="stubPage stubPage--notFound">
      <h1>Page not found</h1>
      <p className="ink-soft">
        <span className="mono">{path}</span> doesn't match any route in this app.
      </p>
      {hint && (
        <p>
          Looking for the old surface? It moved to{" "}
          <Link to={hint.to}>
            <span className="mono">{hint.to}</span>
          </Link>{" "}
          in the UI-D1 IA rework.
        </p>
      )}
      <p>
        Try <Link to="/">Home</Link> · <Link to="/applications">Applications</Link> ·{" "}
        <Link to="/documents">Documents</Link> · <Link to="/templates">Templates</Link> ·{" "}
        <Link to="/automation">Automation</Link>.
      </p>
    </section>
  );
}

function legacyRedirectHint(path: string): { to: string } | null {
  if (path === "/runs" || path.startsWith("/runs?")) return { to: "/applications" };
  if (path === "/runs/new") return { to: "/applications" };
  if (path === "/templates/new") return { to: "/applications" };
  if (path.startsWith("/runs/")) {
    const rest = path.slice("/runs/".length);
    return { to: `/applications/_/runs/${rest}` };
  }
  if (path === "/blobs" || path.startsWith("/blobs?")) return { to: "/documents" };
  return null;
}
