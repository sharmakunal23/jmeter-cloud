import { createContext, useContext, useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";

import { ClustersIcon, ReservationsIcon } from "../components/Icons";

/**
 * Capacity section shell — owns the `<h1>` and the two-tab strip, and hands
 * the body to whichever tab is active: Reservations (`/capacity`, what each
 * group holds on each cluster) and Clusters (`/capacity/clusters`, the
 * registry those reservations draw on). Both read the same ceiling, so they
 * belong under one heading rather than two top-level tabs.
 *
 * <p>The group drill-in (`/capacity/groups/:groupId`) deliberately sits
 * outside this shell: it is a detail page with its own title and a back
 * link, not a third tab.
 */
/**
 * The active tab's status line, published up to the section heading.
 *
 * <p>It is a <b>string</b>, not a node, on purpose: the setter runs from an
 * effect, and a node would be a fresh object every render, so the effect would
 * fire every render and re-render forever.
 */
const StatusSlot = createContext<(text: string | null) => void>(() => {});

/** Called by the active tab; the text appears beside the section's `<h1>`. */
export function useCapacitySectionStatus(text: string | null): void {
  const setStatus = useContext(StatusSlot);
  useEffect(() => {
    setStatus(text);
    return () => setStatus(null);
  }, [setStatus, text]);
}

export function CapacitySection() {
  const [status, setStatus] = useState<string | null>(null);
  return (
    <section className="capacitySection">
      <header className="pageHeader pageHeader--section">
        <div className="pageHeader__titleGroup">
          <h1>Capacity</h1>
          {/* The tab's status reads as part of the title, not as a line the
              body has to make room for. */}
          {status && <small className="ink-soft" aria-live="polite">{status}</small>}
        </div>
      </header>

      <nav className="sectionTabs" aria-label="Capacity views">
        {/* `end` so Reservations (the index route) stops matching once the
            URL moves on to /capacity/clusters. */}
        <NavLink to="/capacity" end className="sectionTabs__tab">
          <ReservationsIcon />
          Reservations
        </NavLink>
        <NavLink to="/capacity/clusters" className="sectionTabs__tab">
          <ClustersIcon />
          Clusters
        </NavLink>
      </nav>

      <StatusSlot.Provider value={setStatus}>
        <Outlet />
      </StatusSlot.Provider>
    </section>
  );
}
