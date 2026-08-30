import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";

import { GlobalPropertiesEditor } from "../GlobalPropertiesEditor";

function setup(opts: {
    value?: Record<string, string>;
    divergedCount?: number;
    onChange?: (next: Record<string, string>) => void;
} = {}) {
    const onChange = opts.onChange ?? vi.fn();
    const utils = render(
        <GlobalPropertiesEditor
            value={opts.value ?? {}}
            onChange={onChange}
            divergedCount={opts.divergedCount}
        />,
    );
    return { ...utils, onChange };
}

// queueMicrotask-driven onChange — flush microtasks before asserting.
async function flush() {
    await Promise.resolve();
    await Promise.resolve();
}

describe("GlobalPropertiesEditor — empty state", () => {
    it("shows the no-properties placeholder when value is empty", () => {
        setup();
        expect(screen.getByText(/No global properties for new workers/i)).toBeInTheDocument();
    });

    it("renders the +Add button even with no rows", () => {
        setup();
        expect(screen.getByRole("button", { name: /\+ Add property/i })).toBeInTheDocument();
    });
});

describe("GlobalPropertiesEditor — populated", () => {
    it("hydrates one row per entry in the initial value map", () => {
        setup({ value: { USER_OFFSET: "100", THREADS: "50" } });
        expect(screen.getByDisplayValue("USER_OFFSET")).toBeInTheDocument();
        expect(screen.getByDisplayValue("THREADS")).toBeInTheDocument();
        expect(screen.getByDisplayValue("100")).toBeInTheDocument();
        expect(screen.getByDisplayValue("50")).toBeInTheDocument();
    });

    it("typing into a row's key + value emits onChange with the normalized map", async () => {
        const onChange = vi.fn();
        setup({ onChange });
        fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
        const keyInput = screen.getByLabelText("global property 1 key");
        const valInput = screen.getByLabelText("global property 1 value");
        fireEvent.change(keyInput, { target: { value: "USER_OFFSET" } });
        fireEvent.change(valInput, { target: { value: "100" } });
        await flush();
        expect(onChange).toHaveBeenLastCalledWith({ USER_OFFSET: "100" });
    });

    it("removing a row drops its entry from onChange", async () => {
        const onChange = vi.fn();
        setup({ value: { USER_OFFSET: "100" }, onChange });
        fireEvent.click(screen.getByLabelText(/remove global property 1/i));
        await flush();
        expect(onChange).toHaveBeenLastCalledWith({});
    });
});

describe("GlobalPropertiesEditor — validation", () => {
    it("flags an invalid key", async () => {
        const onChange = vi.fn();
        setup({ onChange });
        fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
        fireEvent.change(screen.getByLabelText("global property 1 key"), {
            target: { value: "1startsWithDigit" },
        });
        fireEvent.change(screen.getByLabelText("global property 1 value"), {
            target: { value: "x" },
        });
        await flush();
        expect(screen.getByText(/key must match/i)).toBeInTheDocument();
        // onChange should NOT include the invalid row.
        expect(onChange).toHaveBeenLastCalledWith({});
    });

    it("flags a duplicate key", async () => {
        setup({ value: { K: "a" } });
        fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
        fireEvent.change(screen.getByLabelText("global property 2 key"), {
            target: { value: "K" },
        });
        await flush();
        expect(screen.getByText(/duplicate key: K/)).toBeInTheDocument();
    });
});

describe("GlobalPropertiesEditor — diverged-snapshot hint", () => {
    it("shows nothing when divergedCount is 0", () => {
        setup({ divergedCount: 0 });
        expect(document.querySelector('.globalProps__overrideHint')).toBeNull();
    });

    it("singular phrasing when divergedCount is 1", () => {
        setup({ divergedCount: 1 });
        const hint = document.querySelector('.globalProps__overrideHint');
        expect(hint).toHaveTextContent(/1 existing worker keep[s]? a different snapshot/);
    });

    it("plural phrasing when divergedCount > 1", () => {
        setup({ divergedCount: 4 });
        const hint = document.querySelector('.globalProps__overrideHint');
        expect(hint).toHaveTextContent(/4 existing workers keep a different snapshot/);
    });
});

describe("GlobalPropertiesEditor — accessibility", () => {
    it("is identified by its aria-label", () => {
        setup();
        expect(screen.getByRole("region", { name: "Global properties" })).toBeInTheDocument();
    });

    it("has no axe violations in the populated state", async () => {
        const { container } = setup({ value: { K: "v" } });
        const results = await axe(container);
        expect(results).toHaveNoViolations();
    });
});

describe("GlobalPropertiesEditor — external re-sync (template hydration)", () => {
    it("a late value change (template resolving after mount) populates the rows", () => {
        const onChange = vi.fn();
        const { rerender } = render(<GlobalPropertiesEditor value={{}} onChange={onChange} />);
        expect(screen.getByText(/No global properties/i)).toBeInTheDocument();
        rerender(<GlobalPropertiesEditor value={{ USER_OFFSET: "100" }} onChange={onChange} />);
        expect(screen.getByDisplayValue("USER_OFFSET")).toBeInTheDocument();
        expect(screen.getByDisplayValue("100")).toBeInTheDocument();
    });

    it("an equal value round-trip does not clobber an in-progress blank row", () => {
        const onChange = vi.fn();
        const { rerender } = render(<GlobalPropertiesEditor value={{ K: "v" }} onChange={onChange} />);
        fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
        // Parent re-renders with the committed-equal map (its state update from
        // our own onChange). The blank in-progress row must survive.
        rerender(<GlobalPropertiesEditor value={{ K: "v" }} onChange={onChange} />);
        expect(screen.getByLabelText("global property 2 key")).toBeInTheDocument();
    });

    it("editing after hydration emits the hydrated entries too (no wipe)", async () => {
        const onChange = vi.fn();
        const { rerender } = render(<GlobalPropertiesEditor value={{}} onChange={onChange} />);
        rerender(<GlobalPropertiesEditor value={{ A: "1" }} onChange={onChange} />);
        fireEvent.click(screen.getByRole("button", { name: /\+ Add property/i }));
        fireEvent.change(screen.getByLabelText("global property 2 key"), { target: { value: "B" } });
        fireEvent.change(screen.getByLabelText("global property 2 value"), { target: { value: "2" } });
        await flush();
        expect(onChange).toHaveBeenLastCalledWith({ A: "1", B: "2" });
    });
});
