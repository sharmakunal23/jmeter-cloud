import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import type { HTMLAttributes } from "react";

/**
 * Props that make a whole table row behave like a link: click to open, Enter or
 * Space from the keyboard, and a `role`/`aria-label` so assistive tech
 * announces it as one target rather than a bag of cells.
 *
 * <p>Shared because every list that drills in needs the same four behaviours,
 * and the keyboard half is the half that gets forgotten. Cells with their own
 * controls must still stop propagation — the row's click would otherwise fire
 * behind a button.
 */
export function useRowLink() {
  const navigate = useNavigate();
  return useCallback(
    (href: string, ariaLabel: string): HTMLAttributes<HTMLTableRowElement> & { tabIndex: number } => ({
      className: "capacityListRow capacityListRow--clickable",
      role: "link",
      "aria-label": ariaLabel,
      tabIndex: 0,
      onClick: () => navigate(href),
      onKeyDown: (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          navigate(href);
        }
      },
    }),
    [navigate],
  );
}
