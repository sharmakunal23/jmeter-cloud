import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { ScheduleBuilder, type ScheduleValue } from "../ScheduleBuilder";

function setup(defaultTime = "02:00") {
  const onChange = vi.fn();
  render(
    <ScheduleBuilder
      value={{ cronExpression: "0 2 * * *", timeZone: "UTC" } as ScheduleValue}
      onChange={onChange}
      idPrefix="t"
      defaultTime={defaultTime}
    />,
  );
  return { onChange };
}

const latest = (onChange: ReturnType<typeof vi.fn>): ScheduleValue =>
  onChange.mock.calls.at(-1)?.[0];

describe("ScheduleBuilder", () => {
  it("emits the default daily preset + a timezone on mount", () => {
    const { onChange } = setup("02:00");
    expect(latest(onChange).cronExpression).toBe("0 2 * * *");
    expect(typeof latest(onChange).timeZone).toBe("string");
    expect(latest(onChange).timeZone.length).toBeGreaterThan(0);
  });

  it("generates the weekdays preset", () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText("Repeat"), { target: { value: "weekdays" } });
    expect(latest(onChange).cronExpression).toBe("0 2 * * 1-5");
  });

  it("weekly reveals a day-of-week picker and uses it", () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText("Repeat"), { target: { value: "weekly" } });
    fireEvent.change(screen.getByLabelText("Day of week"), { target: { value: "5" } }); // Friday
    expect(latest(onChange).cronExpression).toBe("0 2 * * 5");
  });

  it("monthly reveals a day-of-month input and uses it", () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText("Repeat"), { target: { value: "monthly" } });
    fireEvent.change(screen.getByLabelText("Day of month"), { target: { value: "15" } });
    expect(latest(onChange).cronExpression).toBe("0 2 15 * *");
  });

  it("renders a plain-language summary of the current schedule", () => {
    setup();
    expect(screen.getByText("Every day at 2:00 AM")).toBeInTheDocument();
  });

  it("Advanced mode shows the raw cron seeded from the simple selection, and examples fill it", () => {
    const { onChange } = setup();
    fireEvent.click(screen.getByRole("tab", { name: "Advanced" }));
    const cronInput = screen.getByLabelText("CRON expression") as HTMLInputElement;
    expect(cronInput.value).toBe("0 2 * * *"); // seeded

    fireEvent.click(screen.getByRole("button", { name: /cron examples/i }));
    fireEvent.click(screen.getByText("*/15 * * * *"));
    expect(latest(onChange).cronExpression).toBe("*/15 * * * *");
  });

  it("lets the operator pick a different timezone", () => {
    const { onChange } = setup();
    fireEvent.change(screen.getByLabelText("Time zone"), { target: { value: "Asia/Kolkata" } });
    expect(latest(onChange).timeZone).toBe("Asia/Kolkata");
  });
});
