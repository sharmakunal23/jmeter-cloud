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
export function CapacitySection() {
  return (
    <section className="capacitySection">
      <header className="pageHeader pageHeader--section">
        <div className="pageHeader__titleGroup">
          <h1>Capacity</h1>
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

      <Outlet />
    </section>
  );
}
