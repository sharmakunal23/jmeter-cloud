import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";

import { useKeyboardToggle } from "../useKeyboardToggle";

function fireKey(key: string, init: Partial<KeyboardEventInit> = {}, target?: HTMLElement) {
  const event = new KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...init,
  });
  if (target) {
    target.dispatchEvent(event);
  } else {
    document.dispatchEvent(event);
  }
  return event;
}

beforeEach(() => {
  document.body.innerHTML = "";
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("useKeyboardToggle — fires when key matches outside form fields", () => {
  it("invokes the callback when the matching key fires on a non-form target", () => {
    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));

    act(() => { fireKey("["); });
    expect(cb).toHaveBeenCalledTimes(1);
  });

  it("uses the latest callback (no stale closure) on consumer re-render", () => {
    const first = vi.fn();
    const second = vi.fn();
    const { rerender } = renderHook(
      ({ cb }: { cb: () => void }) => useKeyboardToggle("[", cb),
      { initialProps: { cb: first } },
    );
    rerender({ cb: second });

    act(() => { fireKey("["); });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("preventDefault is called so the bracket isn't typed somewhere unintended", () => {
    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));

    let captured: KeyboardEvent | null = null;
    act(() => { captured = fireKey("["); });
    expect(captured!.defaultPrevented).toBe(true);
  });
});

describe("useKeyboardToggle — gating", () => {
  it("ignores the keypress when the target is an INPUT", () => {
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();

    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));
    act(() => { fireKey("[", {}, input); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("ignores the keypress when the target is a TEXTAREA", () => {
    const ta = document.createElement("textarea");
    document.body.appendChild(ta);

    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));
    act(() => { fireKey("[", {}, ta); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("ignores the keypress when the target is a SELECT", () => {
    const sel = document.createElement("select");
    document.body.appendChild(sel);

    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));
    act(() => { fireKey("[", {}, sel); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("ignores the keypress when the target is contenteditable", () => {
    const div = document.createElement("div");
    div.setAttribute("contenteditable", "true");
    document.body.appendChild(div);

    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));
    act(() => { fireKey("[", {}, div); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("ignores keys with modifiers (Ctrl, Meta, Alt) — those are reserved for OS shortcuts", () => {
    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));

    act(() => { fireKey("[", { ctrlKey: true }); });
    act(() => { fireKey("[", { metaKey: true }); });
    act(() => { fireKey("[", { altKey: true }); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("ignores other keys", () => {
    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb));
    act(() => { fireKey("]"); });
    act(() => { fireKey("a"); });
    act(() => { fireKey("Enter"); });
    expect(cb).not.toHaveBeenCalled();
  });

  it("does nothing when disabled=true", () => {
    const cb = vi.fn();
    renderHook(() => useKeyboardToggle("[", cb, { disabled: true }));
    act(() => { fireKey("["); });
    expect(cb).not.toHaveBeenCalled();
  });
});

describe("useKeyboardToggle — cleanup", () => {
  it("removes the document listener on unmount", () => {
    const cb = vi.fn();
    const { unmount } = renderHook(() => useKeyboardToggle("[", cb));
    unmount();
    act(() => { fireKey("["); });
    expect(cb).not.toHaveBeenCalled();
  });
});
