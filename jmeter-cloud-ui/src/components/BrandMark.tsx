/**
 * Chase octagon brand mark — the blue octagon with the white pinwheel.
 *
 * <p>Drawn inline as SVG so it ships zero image bytes and scales crisply
 * at every size (header 1.2em, Home byline 1.8rem, footer 1.2em). The
 * octagon fill tracks the `--brand` token, so a palette change recolors it.
 *
 * <p><strong>This is a hand-built RECREATION of the Chase mark, not the
 * official brand asset.</strong> For anything customer-facing, drop the
 * brand-approved Chase logo SVG into the body of this one component and it
 * propagates to the header, the Home byline, and the footer automatically.
 *
 * <p>Decorative by default (`aria-hidden`) because every placement sits
 * next to a visible text label ("jmeter-cloud", "CCB Card Performance"),
 * so announcing it again would be redundant for screen readers.
 */
export function BrandMark({ className = "" }: { className?: string }) {
  return (
    <svg
      className={`brandMark ${className}`.trim()}
      viewBox="0 0 100 100"
      aria-hidden="true"
      focusable="false"
    >
      {/* Blue octagon */}
      <polygon
        points="31,2 69,2 98,31 98,69 69,98 31,98 2,69 2,31"
        fill="var(--brand)"
      />
      {/* White pinwheel — four offset arms rotated 90° around the centre
          (the offset gives the spin) plus the small white square in the
          middle. Kept identical to public/favicon.svg so the mark is the
          same in the header, footer, and browser tab. */}
      <g fill="#ffffff">
        <rect x="50" y="51.5" width="48" height="5" transform="rotate(0 50 50)" />
        <rect x="50" y="51.5" width="48" height="5" transform="rotate(90 50 50)" />
        <rect x="50" y="51.5" width="48" height="5" transform="rotate(180 50 50)" />
        <rect x="50" y="51.5" width="48" height="5" transform="rotate(270 50 50)" />
        <rect x="42" y="42" width="16" height="16" />
      </g>
    </svg>
  );
}
