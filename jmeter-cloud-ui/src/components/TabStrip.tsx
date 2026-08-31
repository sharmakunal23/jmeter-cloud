import { useEffect, useRef, type KeyboardEvent, type ReactNode } from "react";

/**
 * An accessible tab strip: roving tabindex, arrow/Home/End navigation, and the
 * aria wiring that pairs each tab with its panel.
 *
 * <p>Extracted from the run-detail page so the workflow surfaces do not grow a
 * second, subtly different implementation of the same keyboard contract.
 * Panels are the caller's; this owns only the strip and the selection.
 */
export interface TabDefinition<Id extends string> {
  id: Id;
  label: string;
  /** Rendered after the label — a count, a state chip. */
  badge?: ReactNode;
}

export interface TabStripProps<Id extends string> {
  tabs: ReadonlyArray<TabDefinition<Id>>;
  active: Id;
  onChange: (id: Id) => void;
  /** Prefixes the tab/panel ids so two strips on one page never collide. */
  idPrefix: string;
  ariaLabel: string;
}

export function TabStrip<Id extends string>({
  tabs, active, onChange, idPrefix, ariaLabel,
}: TabStripProps<Id>) {
  const refs = useRef<Map<Id, HTMLButtonElement | null>>(new Map());

  // A tab that disappears (a live-only panel on a finished run) must not leave
  // the strip pointing at nothing.
  useEffect(() => {
    if (tabs.length > 0 && !tabs.some((t) => t.id === active)) onChange(tabs[0]!.id);
  }, [tabs, active, onChange]);

  function onKeyDown(e: KeyboardEvent<HTMLButtonElement>) {
    const idx = tabs.findIndex((t) => t.id === active);
    if (idx === -1) return;
    let next = idx;
    switch (e.key) {
      case "ArrowRight": next = (idx + 1) % tabs.length; break;
      case "ArrowLeft": next = (idx - 1 + tabs.length) % tabs.length; break;
      case "Home": next = 0; break;
      case "End": next = tabs.length - 1; break;
      default: return;
    }
    e.preventDefault();
    const target = tabs[next];
    if (!target) return;
    onChange(target.id);
    refs.current.get(target.id)?.focus();
  }

  return (
    <div className="tabStrip" role="tablist" aria-label={ariaLabel}>
      {tabs.map((t) => {
        const selected = t.id === active;
        return (
          <button
            key={t.id}
            ref={(el) => { refs.current.set(t.id, el); }}
            type="button"
            role="tab"
            id={`${idPrefix}Tab-${t.id}`}
            aria-controls={`${idPrefix}Panel-${t.id}`}
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            className={selected ? "tabStrip__tab tabStrip__tab--active" : "tabStrip__tab"}
            onClick={() => onChange(t.id)}
            onKeyDown={onKeyDown}
          >
            {t.label}
            {t.badge != null && <span className="tabStrip__badge">{t.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}

/** The panel half of the pairing; keeps the aria ids in one place. */
export function TabPanel({
  id, idPrefix, active, children,
}: {
  id: string;
  idPrefix: string;
  active: boolean;
  children: ReactNode;
}) {
  if (!active) return null;
  return (
    <div role="tabpanel" id={`${idPrefix}Panel-${id}`} aria-labelledby={`${idPrefix}Tab-${id}`}>
      {children}
    </div>
  );
}

/**
 * Keeps the active tab in the URL's `tab` parameter, so a tab is a place you
 * can link to, bookmark and come back to after a refresh — the same rule the
 * run's metrics view already follows for its own state.
 *
 * <p>An unknown or missing value falls back to the first tab rather than
 * rendering nothing, because a stale bookmark should still open the page.
 */
export function useTabInUrl<Id extends string>(
  tabs: ReadonlyArray<TabDefinition<Id>>,
  searchParams: URLSearchParams,
  setSearchParams: (next: URLSearchParams, opts?: { replace?: boolean }) => void,
): [Id, (id: Id) => void] {
  const raw = searchParams.get("tab");
  const active = tabs.some((t) => t.id === raw) ? (raw as Id) : tabs[0]!.id;
  const setActive = (id: Id) => {
    const next = new URLSearchParams(searchParams);
    if (id === tabs[0]!.id) next.delete("tab");   // the default tab needs no parameter
    else next.set("tab", id);
    setSearchParams(next, { replace: true });
  };
  return [active, setActive];
}
