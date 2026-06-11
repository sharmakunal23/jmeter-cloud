import { useEffect, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";

import { applicationsApi, type Application } from "../api/applications";
import { BlobsPage } from "./BlobsPage";
import type { BlobType } from "../api/blobs";

/**
 * Phase IA-Documents (2026-05-12) — per-application documents drill-in.
 * Reached via `/documents/{appName}` (the click target on every row of
 * `<DocumentsListPage>`). The body is the existing 4-tab strip
 * (Test Plans / Data Files / Results / Other) but the listing AND the
 * upload form are scoped to one application via
 * {@link BlobsPage#pinnedApplication}.
 *
 * <p>URL shape: `/documents/:appName/:type?` — when `:type` is absent
 * we default to `testPlan`. Each tab is a deep-link so the operator
 * can paste the URL straight at "data files for checkout".
 *
 * <p>Backwards-compat: the legacy route `/documents/:type` (where
 * `:type ∈ {testPlan, dataFiles, result, other}`) is preserved by
 * detecting those segments in `:appName` and redirecting to the new
 * list root. Deep-link bookmarks the operator may have to the old
 * type-only URLs land on the apps list rather than 404.
 */

const TABS: Array<{ urlSegment: string; type: BlobType; label: string }> = [
  { urlSegment: "testPlan",  type: "testPlan",  label: "Test Plans" },
  { urlSegment: "dataFiles", type: "dataFiles", label: "Data Files" },
  { urlSegment: "result",    type: "result",    label: "Results" },
  { urlSegment: "other",     type: "other",     label: "Other" },
];
const VALID_TYPE_SEGMENTS = new Set(TABS.map((t) => t.urlSegment));

export function DocumentsDetailPage() {
  const { appName: appNameParam = "", type: typeParam } = useParams<{ appName: string; type?: string }>();

  // Legacy route shim — `/documents/testPlan` etc. land here because of
  // the route shape; redirect to the list so old bookmarks find a useful
  // page rather than "application not found".
  if (VALID_TYPE_SEGMENTS.has(appNameParam)) {
    return <Navigate to="/documents" replace />;
  }

  const [appLookup, setAppLookup] = useState<
    | { status: "loading" }
    | { status: "ok"; app: Application }
    | { status: "notFound" }
    | { status: "error"; message: string }
  >({ status: "loading" });

  useEffect(() => {
    const ctl = new AbortController();
    applicationsApi.list(ctl.signal)
      .then((apps) => {
        const app = apps.find((a) => a.name === appNameParam);
        setAppLookup(app ? { status: "ok", app } : { status: "notFound" });
      })
      .catch((err: unknown) => {
        if (ctl.signal.aborted) return;
        setAppLookup({ status: "error", message: err instanceof Error ? err.message : String(err) });
      });
    return () => ctl.abort();
  }, [appNameParam]);

  // Tab fallback — unknown / missing :type → testPlan.
  const activeTab = TABS.find((t) => t.urlSegment === typeParam) ?? TABS[0];
  if (typeParam && !VALID_TYPE_SEGMENTS.has(typeParam)) {
    return <Navigate to={`/documents/${encodeURIComponent(appNameParam)}`} replace />;
  }

  if (appLookup.status === "loading") return <p className="ink-soft">Loading documents for {appNameParam}…</p>;
  if (appLookup.status === "error")   return <p className="text--error">{appLookup.message}</p>;
  if (appLookup.status === "notFound") {
    return (
      <section className="capacityPage">
        <p className="text--error">
          Application <span className="mono">{appNameParam}</span> not found.
        </p>
        <p><Link to="/documents" className="btn btn--ghost">← Back to Documents</Link></p>
      </section>
    );
  }

  const { app } = appLookup;

  return (
    <section className="capacityPage capacityDetail documentsDetailPage">
      <header className="pageHeader">
        <div className="pageHeader__titleGroup">
          <Link to="/documents" className="ink-soft" style={{ fontSize: "0.85rem" }}>← Documents</Link>
          <h1 className="capacityDetail__title"><span className="mono">{app.name}</span></h1>
        </div>
        {/* Same nav-continuity buttons as the Capacity detail page. */}
        <div className="capacityDetail__nav">
          <Link to={`/applications/${encodeURIComponent(app.name)}`} className="btn btn--ghost">
            Open Application →
          </Link>
          <Link
            to={`/applications/${encodeURIComponent(app.name)}/runs/new`}
            className="btn btn--primary"
          >
            Launch a Run →
          </Link>
        </div>
      </header>

      <nav className="documentsTabs" role="tablist" aria-label="Document type">
        {/* Compute the active state ourselves rather than relying on
            NavLink's URL match — the canonical URL `/documents/:appName`
            (no :type segment) deliberately omits the type, so a NavLink
            with `to=/documents/:appName/testPlan` would not match and
            no tab would highlight even though the body shows Test Plans.
            We already resolved `activeTab` above (defaults to TABS[0]
            when :type is absent), so use it as the source of truth. */}
        {TABS.map((tab) => {
          const isActive = tab.urlSegment === activeTab.urlSegment;
          return (
            <Link
              key={tab.urlSegment}
              to={`/documents/${encodeURIComponent(app.name)}/${tab.urlSegment}`}
              className={`documentsTabs__tab ${isActive ? "documentsTabs__tab--active" : ""}`}
              role="tab"
              aria-selected={isActive}
            >
              {tab.label}
            </Link>
          );
        })}
      </nav>

      {/* Re-mount BlobsPage when (app, tab) changes so the internal
          list / pagination state resets cleanly. */}
      <BlobsPage
        key={`${app.name}::${activeTab.type}`}
        pinnedApplication={app.name}
        pinnedType={activeTab.type}
        hideHeader
      />
    </section>
  );
}
