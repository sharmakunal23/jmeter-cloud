import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import {
  ViewModeToggle,
  readPersistedViewMode,
  persistViewMode,
} from "../ViewModeToggle";

describe("ViewModeToggle", () => {
  beforeEach(() => { try { localStorage.clear(); } catch { /* ignore */ } });

  it("renders both List and Grid buttons with the active one selected", () => {
    render(<ViewModeToggle viewMode="grid" onChange={() => {}} />);
    expect(screen.getByRole("tab", { name: "Grid" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "List" })).toHaveAttribute("aria-selected", "false");
  });

  it("invokes onChange with the new mode when clicked", () => {
    const onChange = vi.fn();
    render(<ViewModeToggle viewMode="list" onChange={onChange} />);
    fireEvent.click(screen.getByRole("tab", { name: "Grid" }));
    expect(onChange).toHaveBeenCalledWith("grid");
  });

  it("readPersistedViewMode returns 'list' as the safe default when storage is empty", () => {
    expect(readPersistedViewMode("nonexistent.key")).toBe("list");
  });

  it("readPersistedViewMode round-trips a 'grid' write", () => {
    persistViewMode("test.viewMode", "grid");
    expect(readPersistedViewMode("test.viewMode")).toBe("grid");
  });

  it("readPersistedViewMode coerces unknown values to 'list'", () => {
    try { localStorage.setItem("test.bogus", "not-a-mode"); } catch { /* ignore */ }
    expect(readPersistedViewMode("test.bogus")).toBe("list");
  });
});
