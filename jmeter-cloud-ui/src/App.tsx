import { BrowserRouter, Navigate, Route, Routes, useParams } from "react-router-dom";

import { Layout } from "./components/Layout";
import { ApplicationsListPage } from "./pages/ApplicationsListPage";
import { ApplicationDetailPage } from "./pages/ApplicationDetailPage";
import { AutomationPage } from "./pages/AutomationPage";
import { CapacityListPage } from "./pages/CapacityListPage";
import { CapacityDetailPage } from "./pages/CapacityDetailPage";
import { CapacitySection } from "./pages/CapacitySection";
import { ClustersPage } from "./pages/ClustersPage";
import { DocumentsListPage } from "./pages/DocumentsListPage";
import { DocumentsDetailPage } from "./pages/DocumentsDetailPage";
import { HomePage } from "./pages/HomePage";
import { NewRunPage } from "./pages/NewRunPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { PluginsPage } from "./pages/PluginsPage";
import { RunDetailPage } from "./pages/RunDetailPage";
import { TemplatesListPage } from "./pages/TemplatesListPage";
import { WorkflowGroupsPage } from "./pages/WorkflowGroupsPage";
import { WorkflowListPage } from "./pages/WorkflowListPage";
import { WorkflowBuilderPage } from "./pages/WorkflowBuilderPage";
import { WorkflowDetailPage } from "./pages/WorkflowDetailPage";
import { WorkflowExecutionPage } from "./pages/WorkflowExecutionPage";
import { TemplatesDetailPage } from "./pages/TemplatesDetailPage";

/**
 * Routes reshaped from
 * {@code /runs · /runs/new · /blobs} to the application-centric IA:
 *
 * <ul>
 *   <li><code>/</code> — Home (platform health + quick links).</li>
 *   <li><code>/applications</code> — list (D3 will replace stub).</li>
 *   <li><code>/applications/:appName</code> — per-app detail (D3).</li>
 *   <li><code>/applications/:appName/runs/:runId</code> — run detail.</li>
 *   <li><code>/documents</code> + <code>/documents/:type</code> — Blobs renamed +
 *       per-type tabs (D2 will rebuild).</li>
 *   <li><code>/templates</code> + <code>/templates/new</code> — Save-as-template
 *       feature (D5); New Run form lives at the latter.</li>
 *   <li><code>/automation</code> — design-only stub for D6.</li>
 * </ul>
 *
 * <p>The legacy patterns ({@code /runs}, {@code /runs/new}, {@code /blobs},
 * {@code /runs/:runId}) were deleted in this hard cutover; hits land on
 * {@code <NotFoundPage>} with a hint pointing at the new equivalent.
 */
export function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<HomePage />} />

          <Route path="applications" element={<ApplicationsListPage />} />
          <Route path="applications/:appName" element={<ApplicationDetailPage />} />
          <Route path="applications/:appName/runs/new" element={<NewRunPage />} />
          <Route path="applications/:appName/runs/:runId" element={<RunDetailPage />} />

          {/* Workflows — group first, because a workflow's load tests draw on
              its group's reserved capacity: the group is what scopes it and
              what caps how much it can run at once. `groups/` keeps the group
              drill-in out of the workflowId namespace. */}
          <Route path="workflows" element={<WorkflowGroupsPage />} />
          <Route path="workflows/groups/:groupId" element={<WorkflowListPage />} />
          <Route path="workflows/groups/:groupId/new" element={<WorkflowBuilderPage />} />
          <Route path="workflows/executions/:executionId" element={<WorkflowExecutionPage />} />
          <Route path="workflows/:workflowId" element={<WorkflowDetailPage />} />
          <Route path="workflows/:workflowId/edit" element={<WorkflowBuilderPage />} />

          {/* Capacity — one section, two tabs (2026-08-31): Reservations, one
              row per application group (the worker pool is the group's;
              GROUP-CAPACITY 2026-08-30), and Clusters, the runtime cluster
              registry those reservations draw on (CLUSTER-CAPACITY). Always
              visible: spun and declared workers coexist, so there is no
              posture to gate on. */}
          <Route path="capacity" element={<CapacitySection />}>
            <Route index element={<CapacityListPage />} />
            <Route path="clusters" element={<ClustersPage />} />
          </Route>
          {/* The group drill-in is a detail page, not a third tab, so it sits
              outside the tabbed shell. `groups/` keeps it out of the tab
              namespace — a groupId of "clusters" is legal
              ([a-z][a-z0-9_]{0,29}) and would otherwise shadow the tab. */}
          <Route path="capacity/groups/:groupId" element={<CapacityDetailPage />} />

          {/* Old locations, kept as redirects so operator bookmarks survive
              the reshuffle: /clusters is now a Capacity tab, and the group
              drill-in gained its `groups/` segment. */}
          <Route path="clusters" element={<Navigate to="/capacity/clusters" replace />} />
          <Route path="capacity/:groupId" element={<LegacyGroupCapacityRedirect />} />

          {/* Phase IA-Documents (2026-05-12) — list-then-drill-in IA
              matching `/capacity`. /documents shows apps with per-app
              counts; /documents/:appName drills into the app's docs
              with the existing tabbed type strip. The legacy
              /documents/{type} URL is handled inside the detail page
              via a redirect (operator's old bookmark lands on the list). */}
          <Route path="documents"               element={<DocumentsListPage />} />
          <Route path="documents/:appName"       element={<DocumentsDetailPage />} />
          <Route path="documents/:appName/:type" element={<DocumentsDetailPage />} />

          {/* UX-DYNAMICS T3 — the global plugin library. Flat page (plugins
              are application-agnostic); selected per run in the launcher. */}
          <Route path="plugins" element={<PluginsPage />} />

          {/* Phase IA-Templates (2026-05-13) — list-then-drill-in IA
              matching `/capacity` and `/documents`. /templates shows
              apps with per-app template counts; /templates/:appName
              drills into one app's templates with the existing
              grid/list toggle preserved from the old flat page. */}
          <Route path="templates"           element={<TemplatesListPage />} />
          <Route path="templates/:appName"  element={<TemplatesDetailPage />} />
          {/* D-RunLauncher rework — `/templates/new` is gone; the launcher
              now lives at `/applications/:appName/runs/new`. The Templates
              detail page surfaces "Launch a Run" in the header. */}

          {/* AUTOMATION-3 (2026-08-31) — one page, three sections (workflow
              automation, platform reports, platform infrastructure). The
              per-application drill-in is gone: schedules are scoped to an
              application GROUP, so there was nothing per-application left to
              show. Old bookmarks land on the page rather than a 404. */}
          <Route path="automation" element={<AutomationPage />} />
          <Route path="automation/:appName" element={<Navigate to="/automation" replace />} />

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}

/** `/capacity/{groupId}` → `/capacity/groups/{groupId}` (2026-08-31 move). */
function LegacyGroupCapacityRedirect() {
  const { groupId } = useParams();
  return <Navigate to={`/capacity/groups/${encodeURIComponent(groupId ?? "")}`} replace />;
}
