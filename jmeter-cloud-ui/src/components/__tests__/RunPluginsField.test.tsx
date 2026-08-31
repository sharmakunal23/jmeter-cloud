import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import type { PluginSummary } from "../../api/plugins";
import { RunPluginsField } from "../RunPluginsField";

const LIB: PluginSummary[] = [
  { pluginId: "p1", name: "jpgc-casutg", version: "3.1", sizeBytes: 2048, sha256: "a", fileName: "casutg.jar", createdAt: "2026-08-30T00:00:00Z" },
  { pluginId: "p2", name: "bzm-parallel", version: "0.11", sizeBytes: 1024, sha256: "b", fileName: "parallel.jar", createdAt: "2026-08-30T00:00:00Z" },
];

function setup(overrides: Partial<Parameters<typeof RunPluginsField>[0]> = {}) {
  const onChange = vi.fn();
  const utils = render(
    <MemoryRouter>
      <RunPluginsField
        plugins={LIB}
        loading={false}
        error={null}
        value={[]}
        onChange={onChange}
        unknownIds={[]}
        {...overrides}
      />
    </MemoryRouter>,
  );
  return { onChange, ...utils };
}

describe("RunPluginsField", () => {
  it("picking from the dropdown appends the plugin id", () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText("Plugins"), { target: { value: "p1" } });
    expect(onChange).toHaveBeenCalledWith(["p1"]);
  });

  it("selected plugins render as chips with an accessible remove", () => {
    const { onChange } = setup({ value: ["p1"] });
    expect(screen.getByText("jpgc-casutg@3.1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /remove plugin jpgc-casutg/i }));
    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("while the library is unavailable (null), hydrated ids render neutrally — never 'removed'", () => {
    setup({ plugins: null, value: ["p1"], unknownIds: [] });
    expect(screen.queryByText(/removed from library/)).toBeNull();
    expect(screen.getByText(/p1/)).toBeInTheDocument();
  });

  it("an unknown hydrated id renders a warn chip and an exclusion notice", () => {
    setup({ value: ["p1", "01GONE00000000000000000000"], unknownIds: ["01GONE00000000000000000000"] });
    expect(screen.getByText(/removed from library/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(/no longer in the library — excluded/);
  });

  it("the InfoTip lists the library as name@version", () => {
    setup();
    fireEvent.click(screen.getByRole("button", { name: "About run plugins" }));
    expect(screen.getByText(/jpgc-casutg@3\.1 · bzm-parallel@0\.11/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Manage plugins/ })).toHaveAttribute("href", "/plugins");
  });

  it("an already-selected plugin leaves the dropdown", () => {
    setup({ value: ["p1"] });
    const options = Array.from(screen.getByLabelText("Plugins").querySelectorAll("option"));
    expect(options.map((o) => o.getAttribute("value"))).not.toContain("p1");
  });
});
