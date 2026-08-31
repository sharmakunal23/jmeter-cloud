import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TabPanel, TabStrip, type TabDefinition } from "../TabStrip";

type Id = "flow" | "metrics" | "tasks";
const TABS: ReadonlyArray<TabDefinition<Id>> = [
  { id: "flow", label: "Flow" },
  { id: "metrics", label: "Metrics" },
  { id: "tasks", label: "Tasks", badge: 15 },
];

describe("TabStrip", () => {
  it("wires each tab to its panel, so a screen reader can follow the pairing", () => {
    const onChange = vi.fn();
    render(
      <>
        <TabStrip tabs={TABS} active="metrics" onChange={onChange} idPrefix="x" ariaLabel="Sections" />
        <TabPanel id="metrics" idPrefix="x" active><p>the metrics</p></TabPanel>
      </>,
    );
    const tab = screen.getByRole("tab", { name: /Metrics/ });
    expect(tab).toHaveAttribute("aria-selected", "true");
    expect(tab).toHaveAttribute("aria-controls", "xPanel-metrics");
    expect(screen.getByRole("tabpanel")).toHaveAttribute("aria-labelledby", "xTab-metrics");
  });

  it("only the selected tab is in the tab order — arrows move between them, not Tab", () => {
    render(<TabStrip tabs={TABS} active="flow" onChange={vi.fn()} idPrefix="x" ariaLabel="Sections" />);
    expect(screen.getByRole("tab", { name: "Flow" })).toHaveAttribute("tabindex", "0");
    expect(screen.getByRole("tab", { name: /Metrics/ })).toHaveAttribute("tabindex", "-1");
  });

  it("arrow keys cycle and Home/End jump", () => {
    const onChange = vi.fn();
    render(<TabStrip tabs={TABS} active="flow" onChange={onChange} idPrefix="x" ariaLabel="Sections" />);
    const flow = screen.getByRole("tab", { name: "Flow" });

    fireEvent.keyDown(flow, { key: "ArrowRight" });
    expect(onChange).toHaveBeenLastCalledWith("metrics");

    fireEvent.keyDown(flow, { key: "ArrowLeft" });   // wraps to the end
    expect(onChange).toHaveBeenLastCalledWith("tasks");

    fireEvent.keyDown(flow, { key: "End" });
    expect(onChange).toHaveBeenLastCalledWith("tasks");
  });

  it("a badge rides with its label without becoming part of the accessible name's meaning", () => {
    render(<TabStrip tabs={TABS} active="flow" onChange={vi.fn()} idPrefix="x" ariaLabel="Sections" />);
    expect(screen.getByRole("tab", { name: /Tasks/ })).toHaveTextContent("Tasks15");
  });

  it("a hidden panel renders nothing, so nothing behind a closed tab fetches or mounts", () => {
    render(<TabPanel id="metrics" idPrefix="x" active={false}><p>expensive</p></TabPanel>);
    expect(screen.queryByText("expensive")).toBeNull();
  });

  it("a tab that disappears hands selection to the first one rather than showing nothing", () => {
    const onChange = vi.fn();
    render(
      <TabStrip
        tabs={[{ id: "flow", label: "Flow" }]}
        active={"metrics" as Id}
        onChange={onChange}
        idPrefix="x"
        ariaLabel="Sections"
      />,
    );
    expect(onChange).toHaveBeenCalledWith("flow");
  });
});
