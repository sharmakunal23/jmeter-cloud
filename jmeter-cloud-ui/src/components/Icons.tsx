import type { ReactNode } from "react";

/**
 * The UI's icon set — inline stroke SVGs on a shared 24×24 grid, sized in
 * `em` so they scale with whatever text they sit beside (nav tabs, section
 * tabs) and coloured by `currentColor` so the active/hover states of the
 * label carry the glyph for free.
 *
 * <p>Every icon is decorative (`aria-hidden`): each one sits next to its
 * visible label, so announcing it again would only add noise. That also
 * keeps the accessible name of a nav link exactly its text.
 *
 * <p>Drawn by hand rather than pulled from an icon package — six glyphs cost
 * about a kilobyte inline and keep the 500 KB bundle budget untouched.
 */
function Icon({ children }: { children: ReactNode }) {
  return (
    <svg
      className="icon"
      viewBox="0 0 24 24"
      width="16"
      height="16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

/** Applications — a four-pane layout grid, the registry of things under test. */
export function ApplicationsIcon() {
  return (
    <Icon>
      <rect x="3" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5" />
      <rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5" />
      <rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5" />
    </Icon>
  );
}

/** Capacity — a gauge: how much of the reserved fleet is in use. */
export function CapacityIcon() {
  return (
    <Icon>
      <path d="M3.5 18a9 9 0 1 1 17 0" />
      <path d="M12 18l4-5" />
      <circle cx="12" cy="18" r="1.4" />
    </Icon>
  );
}

/**
 * Reservations — allocation bars, one per group's slice of a cluster. Drawn
 * heavier than the shared stroke so they read as bars rather than as the
 * list/sort glyph three hairlines would suggest.
 */
export function ReservationsIcon() {
  return (
    <Icon>
      <g strokeWidth="3">
        <path d="M4.5 6.5h15" />
        <path d="M4.5 12h9.5" />
        <path d="M4.5 17.5h5.5" />
      </g>
    </Icon>
  );
}

/** Clusters — a server rack: the registered data centers. */
export function ClustersIcon() {
  return (
    <Icon>
      <rect x="3" y="4" width="18" height="7" rx="1.5" />
      <rect x="3" y="13" width="18" height="7" rx="1.5" />
      <path d="M7 7.5h.01" />
      <path d="M7 16.5h.01" />
    </Icon>
  );
}

/** Documents — a page with a folded corner. */
export function DocumentsIcon() {
  return (
    <Icon>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" />
      <path d="M14 3v5h5" />
      <path d="M9 13h6" />
      <path d="M9 17h4" />
    </Icon>
  );
}

/** Plugins — a block with a second block clipped onto it (an extension). */
export function PluginsIcon() {
  return (
    <Icon>
      <rect x="3" y="9" width="12" height="12" rx="2" />
      <path d="M15 3h4a2 2 0 0 1 2 2v4h-6z" />
    </Icon>
  );
}

/** Templates — one sheet copied onto another. */
export function TemplatesIcon() {
  return (
    <Icon>
      <rect x="8" y="8" width="13" height="13" rx="2" />
      <path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3" />
    </Icon>
  );
}

/** Automation — a clock: everything here fires on a schedule. */
export function AutomationIcon() {
  return (
    <Icon>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5.2l3.2 2" />
    </Icon>
  );
}

/** Workflows — three nodes wired into a branch: the shape of a task graph. */
export function WorkflowsIcon() {
  return (
    <Icon>
      <rect x="2.5" y="9" width="6" height="6" rx="1.5" />
      <rect x="15.5" y="3" width="6" height="6" rx="1.5" />
      <rect x="15.5" y="15" width="6" height="6" rx="1.5" />
      <path d="M8.5 12h3.5V6h3.5" />
      <path d="M12 12v6h3.5" />
    </Icon>
  );
}
