import { Link, NavLink, Outlet } from "react-router-dom";

import { usePlatformCapabilities } from "../hooks/usePlatformCapabilities";
import { ActorControl } from "./ActorControl";
import { BrandMark } from "./BrandMark";

/**
 * Top-level chrome — header + nav + Outlet for the active route.
 *
 * <p>The nav was reshaped from {@code Runs / New run / Blobs} to
 * {@code Home / Applications / Documents / Templates / Automation}.
 * The legacy URLs were hard-removed (no redirects); a hit on one
 * lands on {@code <NotFoundPage>} which surfaces the new equivalent.
 *
 * <p>STATIC-FLEET Phase 7 — the Capacity tab is hidden when the deployment
 * does not provision its own workers. Capacity is entirely built around
 * spin / restart / drain, none of which apply to an operator-managed fleet;
 * there the equivalent surface is the Data centers section on each application
 * (the group's pool). Capacity itself lists application groups — the pool is the group's.
 * Exactly one of the two is live at a time.
 */
export function Layout() {
  const { dynamicScalingEnabled } = usePlatformCapabilities();
  return (
    <div className="appShell">
      <header className="appHeader">
        <Link to="/" className="appBrand">
          <BrandMark />
          jmeter-cloud
        </Link>
        <nav className="appNav" role="navigation" aria-label="primary">
          {/* Home tab removed — the jmeter-cloud brand link above is the
              Home affordance. NavLinks below match React Router's active
              detection so the brand stays "selected-looking" via styling
              but isn't a duplicate tab. */}
          <NavLink to="/applications">Applications</NavLink>
          {dynamicScalingEnabled && <NavLink to="/capacity">Capacity</NavLink>}
          <NavLink to="/documents">Documents</NavLink>
          <NavLink to="/plugins">Plugins</NavLink>
          <NavLink to="/templates">Templates</NavLink>
          <NavLink to="/automation">Automation</NavLink>
        </nav>
        <div className="appHeader__spacer" />
        <ActorControl />
        <a className="appHeader__link" href="/swagger-ui.html" target="_blank" rel="noreferrer">
          API
        </a>
      </header>
      <main className="appMain">
        <Outlet />
      </main>
      <footer className="appFooter">
        <span className="appFooter__brand">
          <BrandMark />
          CCB Card Performance
        </span>
        <span className="appFooter__sep" aria-hidden="true">·</span>
        <span className="appFooter__org">JPMorganChase</span>
      </footer>
    </div>
  );
}
