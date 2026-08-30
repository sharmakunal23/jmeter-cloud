import { BrowserRouter, Route, Routes } from "react-router-dom";

import { DynamicScalingRoute } from "./components/DynamicScalingRoute";
import { Layout } from "./components/Layout";
import { ApplicationsListPage } from "./pages/ApplicationsListPage";
import { ApplicationDetailPage } from "./pages/ApplicationDetailPage";
import { AutomationListPage } from "./pages/AutomationListPage";
import { AutomationDetailPage } from "./pages/AutomationDetailPage";
import { CapacityListPage } from "./pages/CapacityListPage";
import { CapacityDetailPage } from "./pages/CapacityDetailPage";
import { DocumentsListPage } from "./pages/DocumentsListPage";
import { DocumentsDetailPage } from "./pages/DocumentsDetailPage";
import { HomePage } from "./pages/HomePage";
import { NewRunPage } from "./pages/NewRunPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { PluginsPage } from "./pages/PluginsPage";
import { RunDetailPage } from "./pages/RunDetailPage";
import { TemplatesListPage } from "./pages/TemplatesListPage";
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

          {/* Capacity — one row per application group (the worker pool is the
              group's; GROUP-CAPACITY 2026-08-30), drill-in per group.
              STATIC-FLEET Phase 7 — both routes redirect to /applications on
              a deployment that does not provision its own workers; the whole
              surface is spin / restart / drain, which don't apply there. */}
          <Route
            path="capacity"
            element={
              <DynamicScalingRoute>
                <CapacityListPage />
              </DynamicScalingRoute>
            }
          />
          <Route
            path="capacity/:groupId"
            element={
              <DynamicScalingRoute>
                <CapacityDetailPage />
              </DynamicScalingRoute>
            }
          />

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

          {/* Phase IA-Automation (2026-05-13) — list-then-drill-in IA
              matching `/capacity`, `/documents`, `/templates`. The page
              is shipped against a stub `cronJobsApi.list()` returning
              [] until the D6-A backend (Quartz scheduler) lands; see
              src/api/automation.ts header for the one-line swap. */}
          <Route path="automation"           element={<AutomationListPage />} />
          <Route path="automation/:appName"  element={<AutomationDetailPage />} />

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
