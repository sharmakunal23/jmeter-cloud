import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { axe } from "vitest-axe";

import { Modal } from "../Modal";

function renderModal(overrides: Partial<Parameters<typeof Modal>[0]> = {}) {
  const onClose = vi.fn();
  const utils = render(
    <Modal title="Test dialog" onClose={onClose} footer={<button type="button">Ok</button>} {...overrides}>
      <p>Body text</p>
    </Modal>,
  );
  return { onClose, ...utils };
}

describe("Modal", () => {
  it("names the dialog by its title", () => {
    renderModal();
    expect(screen.getByRole("dialog", { name: "Test dialog" })).toBeInTheDocument();
  });

  it("Escape and the overlay close it; a click inside does not", () => {
    const { onClose } = renderModal();
    fireEvent.click(screen.getByText("Body text"));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(document.querySelector(".modal__overlay")!);
    expect(onClose).toHaveBeenCalledTimes(1);
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it("closeDisabled blocks Escape, the overlay, and the × button", () => {
    const { onClose } = renderModal({ closeDisabled: true });
    fireEvent.keyDown(document.body, { key: "Escape" });
    fireEvent.click(document.querySelector(".modal__overlay")!);
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Close" })).toBeDisabled();
  });

  it("renders footer mode (children in modal__body, footer below)", () => {
    renderModal();
    expect(document.querySelector(".modal__body")).toHaveTextContent("Body text");
    expect(document.querySelector(".modal__footer")).toHaveTextContent("Ok");
  });

  it("renders raw children when footer is omitted (form dialogs own their body)", () => {
    render(
      <Modal title="Form dialog" onClose={vi.fn()}>
        <form className="modal__body" aria-label="the form">
          <Modal.Footer><button type="submit">Save</button></Modal.Footer>
        </form>
      </Modal>,
    );
    expect(screen.getByRole("form", { name: "the form" })).toBeInTheDocument();
    expect(document.querySelector(".modal__footer")).toHaveTextContent("Save");
  });

  it("wires the InfoTip as the dialog description", () => {
    renderModal({ infoTip: "What this dialog does." });
    const dialog = screen.getByRole("dialog", { name: "Test dialog" });
    expect(dialog).toHaveAccessibleDescription("What this dialog does.");
  });

  it("Escape with an open InfoTip closes the tip, not the modal", () => {
    const { onClose } = renderModal({ infoTip: "What this dialog does." });
    const trigger = screen.getByRole("button", { name: "About Test dialog" });
    fireEvent.click(trigger);
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(document.body, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("applies the width variant class", () => {
    renderModal({ width: "form" });
    expect(document.querySelector(".modal--form")).toBeInTheDocument();
  });

  it("focuses an [autofocus] element on mount and restores focus on unmount", () => {
    const outside = document.createElement("button");
    document.body.appendChild(outside);
    outside.focus();
    const { unmount } = render(
      <Modal title="Focus dialog" onClose={vi.fn()} footer={<button type="button" autoFocus>Go</button>}>
        <p>Body</p>
      </Modal>,
    );
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Go" }));
    unmount();
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it("stacked modals: only the top-most handles Tab and Escape", () => {
    const parentClose = vi.fn();
    const childClose = vi.fn();
    render(
      <>
        <Modal title="Parent" onClose={parentClose} footer={<button type="button">P-ok</button>}>
          <p>parent body</p>
        </Modal>
        <Modal title="Child" onClose={childClose} footer={<button type="button">C-ok</button>}>
          <p>child body</p>
        </Modal>
      </>,
    );
    // Tab at the child's last focusable loops to the child's first —
    // never into the parent behind the overlay.
    const childDialog = screen.getByRole("dialog", { name: "Child" });
    screen.getByRole("button", { name: "C-ok" }).focus();
    fireEvent.keyDown(window, { key: "Tab" });
    expect(childDialog.contains(document.activeElement)).toBe(true);
    // Escape reaches only the child.
    fireEvent.keyDown(window, { key: "Escape" });
    expect(childClose).toHaveBeenCalledTimes(1);
    expect(parentClose).not.toHaveBeenCalled();
  });

  it("has no axe violations", async () => {
    const { container } = renderModal({ infoTip: "What this dialog does." });
    expect(await axe(container)).toHaveNoViolations();
  });
});
