import { describe, expect, it } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";

import { InfoTip } from "../InfoTip";

describe("InfoTip", () => {
  it("keeps the content in the DOM while closed (hidden — out of tab order, still a valid aria-describedby target)", () => {
    render(<InfoTip label="About widgets" id="tip1">One sentence.</InfoTip>);
    const content = document.getElementById("tip1")!;
    expect(content).toHaveTextContent("One sentence.");
    expect(content).toHaveAttribute("hidden");
    expect(screen.getByRole("button", { name: "About widgets" })).toHaveAttribute("aria-expanded", "false");
  });

  it("click toggles the popover open and closed", () => {
    render(<InfoTip label="About widgets" id="tip1">One sentence.</InfoTip>);
    const trigger = screen.getByRole("button", { name: "About widgets" });
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById("tip1")).not.toHaveAttribute("hidden");
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(document.getElementById("tip1")).toHaveAttribute("hidden");
  });

  it("Escape closes the open popover", () => {
    render(<InfoTip label="About widgets" id="tip1">One sentence.</InfoTip>);
    fireEvent.click(screen.getByRole("button", { name: "About widgets" }));
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(screen.getByRole("button", { name: "About widgets" })).toHaveAttribute("aria-expanded", "false");
  });

  it("a click outside closes the open popover", () => {
    render(
      <div>
        <button type="button">elsewhere</button>
        <InfoTip label="About widgets" id="tip1">One sentence.</InfoTip>
      </div>,
    );
    fireEvent.click(screen.getByRole("button", { name: "About widgets" }));
    fireEvent.mouseDown(screen.getByRole("button", { name: "elsewhere" }));
    expect(screen.getByRole("button", { name: "About widgets" })).toHaveAttribute("aria-expanded", "false");
  });

  it("function children receive close — interactive content can dismiss the tip", () => {
    render(
      <InfoTip label="About widgets" id="tip1">
        {(close) => <button type="button" onClick={close}>pick me</button>}
      </InfoTip>,
    );
    fireEvent.click(screen.getByRole("button", { name: "About widgets" }));
    fireEvent.click(screen.getByRole("button", { name: "pick me" }));
    expect(screen.getByRole("button", { name: "About widgets" })).toHaveAttribute("aria-expanded", "false");
  });

  it("renders the optional example line", () => {
    render(<InfoTip label="About widgets" example="widget=42">One sentence.</InfoTip>);
    expect(screen.getByText("widget=42")).toHaveClass("infoTip__example");
  });

  it("has no axe violations, open or closed", async () => {
    const { container } = render(<InfoTip label="About widgets">One sentence.</InfoTip>);
    expect(await axe(container)).toHaveNoViolations();
    fireEvent.click(screen.getByRole("button", { name: "About widgets" }));
    expect(await axe(container)).toHaveNoViolations();
  });
});
