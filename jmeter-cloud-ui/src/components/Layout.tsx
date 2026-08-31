import { useEffect, useState } from "react";
import { Link, NavLink, Outlet } from "react-router-dom";

import { ActorControl } from "./ActorControl";
import { BrandMark } from "./BrandMark";
import {
  ApplicationsIcon,
  AutomationIcon,
  CapacityIcon,
  DocumentsIcon,
  PluginsIcon,
  TemplatesIcon,
} from "./Icons";

/**
 * Top-level chrome — header + nav + Outlet for the active route.
 *
 * <p>The nav was reshaped from {@code Runs / New run / Blobs} to
 * {@code Home / Applications / Documents / Templates / Automation}.
 * Those legacy URLs ({@code /runs}, {@code /blobs}) were hard-removed with
 * no redirect; a hit on one lands on {@code <NotFoundPage>}, which surfaces
 * the new equivalent. Later moves within the current IA do redirect.
 *
 * <p>Every tab carries an icon so the bar is scannable by shape before the
 * label is read; the glyphs are decorative and inherit the link's colour.
 *
 * <p>CLUSTER-CAPACITY — Capacity is always visible (spun and declared
 * workers coexist per pool, so there is no deployment posture to gate on)
 * and the cluster registry moved under it as a tab: reservations and the
 * clusters they draw on are one surface, not two.
 *
 * <p>The footer adapts to the page: when the content plus the footer fit
 * inside the viewport it stays pinned visible (nothing to cover, no reason
 * to hide it); once the page scrolls it becomes a dock-style overlay —
 * hidden until the cursor nears the bottom edge (macOS-Dock style), with the
 * hide threshold well above the reveal threshold so it never flickers.
 */
const FOOTER_REVEAL_PX = 24;
const FOOTER_HIDE_PX = 96;

export function Layout() {
  // Short page → pinned; long page → revealed only while the cursor is near
  // the bottom. Two independent signals, footer shows when either is true.
  const [pinned, setPinned] = useState(false);
  const [nearBottom, setNearBottom] = useState(false);
  const footerVisible = pinned || nearBottom;

  useEffect(() => {
    function measure() {
      // .appShell stretches to the viewport (min-height: 100%), so a page
      // that doesn't scroll reports scrollHeight === innerHeight — the
      // footer then only overlays the shell's empty bottom band.
      setPinned(document.documentElement.scrollHeight <= window.innerHeight);
    }
    measure();
    window.addEventListener("resize", measure);
    // Content height changes with every route and data load — watch the body.
    const ro = typeof ResizeObserver !== "undefined" ? new ResizeObserver(measure) : null;
    ro?.observe(document.body);
    return () => {
      window.removeEventListener("resize", measure);
      ro?.disconnect();
    };
  }, []);

  useEffect(() => {
    function onMouseMove(e: MouseEvent) {
      const fromBottom = window.innerHeight - e.clientY;
      setNearBottom((v) => (v ? fromBottom <= FOOTER_HIDE_PX : fromBottom <= FOOTER_REVEAL_PX));
    }
    function onMouseLeave() { setNearBottom(false); }
    window.addEventListener("mousemove", onMouseMove);
    document.documentElement.addEventListener("mouseleave", onMouseLeave);
    return () => {
      window.removeEventListener("mousemove", onMouseMove);
      document.documentElement.removeEventListener("mouseleave", onMouseLeave);
    };
  }, []);

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
          <NavLink to="/applications"><ApplicationsIcon />Applications</NavLink>
          <NavLink to="/capacity"><CapacityIcon />Capacity</NavLink>
          <NavLink to="/documents"><DocumentsIcon />Documents</NavLink>
          <NavLink to="/plugins"><PluginsIcon />Plugins</NavLink>
          <NavLink to="/templates"><TemplatesIcon />Templates</NavLink>
          <NavLink to="/automation"><AutomationIcon />Automation</NavLink>
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
      <footer className={`appFooter${footerVisible ? " appFooter--visible" : ""}`}>
        <span className="appFooter__brand">
          <BrandMark />
          CCB Card Platform Services
        </span>
        <span className="appFooter__sep" aria-hidden="true">·</span>
        <span className="appFooter__org">JPMorganChase</span>
      </footer>
    </div>
  );
}
