import { describe, expect, it } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";

import { Layout } from "../components/Layout";
import { ApplicationsListPage } from "../pages/ApplicationsListPage";
import { ApplicationDetailPage } from "../pages/ApplicationDetailPage";
import { AutomationListPage } from "../pages/AutomationListPage";
import { DocumentsListPage } from "../pages/DocumentsListPage";
import { DocumentsDetailPage } from "../pages/DocumentsDetailPage";
import { HomePage } from "../pages/HomePage";
import { NotFoundPage } from "../pages/NotFoundPage";
import { TemplatesListPage } from "../pages/TemplatesListPage";

/**
 * IA cutover. Asserts the new route table:
 *   - new patterns resolve to their new pages
 *   - legacy patterns 404 (no silent redirect)
 *   - the 404 page surfaces a hint pointing at the new equivalent
 */

function renderAt(initialPath: string) {
    return render(
        <MemoryRouter initialEntries={[initialPath]}>
            <Routes>
                <Route element={<Layout />}>
                    <Route index element={<HomePage />} />
                    <Route path="applications" element={<ApplicationsListPage />} />
                    <Route path="applications/:appName" element={<ApplicationDetailPage />} />
                    {/* Phase IA-Documents (2026-05-12) — list-then-drill-in. */}
                    <Route path="documents"               element={<DocumentsListPage />} />
                    <Route path="documents/:appName"       element={<DocumentsDetailPage />} />
                    <Route path="documents/:appName/:type" element={<DocumentsDetailPage />} />
                    {/* Phase IA-Templates (2026-05-13) — list-then-drill-in. */}
                    <Route path="templates" element={<TemplatesListPage />} />
                    {/* Phase IA-Automation (2026-05-13) — list-then-drill-in. */}
                    <Route path="automation" element={<AutomationListPage />} />
                    <Route path="*" element={<NotFoundPage />} />
                </Route>
            </Routes>
        </MemoryRouter>,
    );
}

describe("UI-D1 routing — new IA paths resolve", () => {
    it("/ → HomePage (h1 is 'jmeter-cloud' post D-HomeRebuild polish; Home tab dropped)", async () => {
        renderAt("/");
        // The page is async (HomePage fetches on mount); accept the
        // loading state OR the rendered heading.
        await waitFor(() => {
            const h1 = document.querySelector('h1');
            expect(h1?.textContent === "jmeter-cloud" || screen.queryByText(/Loading dashboard/i) != null).toBe(true);
        });
    });

    it("/applications/:appName → ApplicationDetailPage", () => {
        renderAt("/applications/checkout-svc");
        expect(screen.getByRole("heading", { name: "checkout-svc", level: 1 })).toBeInTheDocument();
    });

    it("/templates → TemplatesListPage (Phase IA-Templates list view)", () => {
        renderAt("/templates");
        expect(screen.getByRole("heading", { name: "Templates", level: 1 })).toBeInTheDocument();
    });

    it("/automation → AutomationListPage (Phase IA-Automation list view)", () => {
        renderAt("/automation");
        expect(screen.getByRole("heading", { name: "Automation", level: 1 })).toBeInTheDocument();
    });
});

describe("UI-D1 routing — legacy URLs 404 (no redirect)", () => {
    it("/runs lands on NotFoundPage with a hint to /applications", () => {
        renderAt("/runs");
        expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
        const hintLink = screen.getByRole("link", { name: "/applications" });
        expect(hintLink).toHaveAttribute("href", "/applications");
    });

    it("/runs/new lands on 404 with a hint to /applications (post D-RunLauncher rework)", () => {
        renderAt("/runs/new");
        expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
        const hintLink = screen.getByRole("link", { name: "/applications" });
        expect(hintLink).toHaveAttribute("href", "/applications");
    });

    it("/runs/abc123 lands on 404 with a hint to /applications/_/runs/abc123", () => {
        renderAt("/runs/abc123");
        const hintLink = screen.getByRole("link", { name: "/applications/_/runs/abc123" });
        expect(hintLink).toHaveAttribute("href", "/applications/_/runs/abc123");
    });

    it("/blobs lands on 404 with a hint to /documents", () => {
        renderAt("/blobs");
        const hintLink = screen.getByRole("link", { name: "/documents" });
        expect(hintLink).toHaveAttribute("href", "/documents");
    });

    it("/totally-unknown lands on 404 without a legacy hint", () => {
        renderAt("/totally-unknown");
        expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
        // No legacy-redirect hint paragraph for an unknown URL.
        expect(screen.queryByText(/Looking for the old surface/i)).toBeNull();
    });
});

describe("UI-D1 routing — top nav", () => {
    it("renders the 5 primary tabs in standardized order", () => {
        renderAt("/");
        const nav = screen.getByRole("navigation", { name: "primary" });
        expect(nav).toBeInTheDocument();
        // Profiling was removed (2026-05-28, out of scope) — only the 5 core tabs remain.
        const order = ["Applications", "Capacity", "Documents", "Templates", "Automation"];
        for (const label of order) {
            expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
        }
        const links = nav.querySelectorAll("a[href]");
        const labels = Array.from(links).map((l) => l.textContent);
        expect(labels).toEqual(order);
    });

    it("Home tab is no longer in the nav (jmeter-cloud brand link is the Home affordance)", () => {
        renderAt("/");
        const nav = screen.getByRole("navigation", { name: "primary" });
        expect(nav.querySelector('a[href="/"]')).toBeNull();
    });

    it("legacy Runs / New run / Blobs tabs are gone", () => {
        renderAt("/");
        const nav = screen.getByRole("navigation", { name: "primary" });
        expect(nav.querySelector('a[href="/runs"]')).toBeNull();
        expect(nav.querySelector('a[href="/runs/new"]')).toBeNull();
        expect(nav.querySelector('a[href="/blobs"]')).toBeNull();
    });
});
