package com.perf.globalorchestrator.report;

/**
 * AUTOMATION — shared, lightweight HTML shell for the report emails. Keeps both
 * composers ({@link InfraReadinessComposer}, {@link DailyReportComposer}) visually
 * consistent and light: a narrow, system-font container, a small subtitle, an
 * optional operator intro note, and inline styles (email clients ignore {@code
 * <style>} blocks). Light tables use subtle bottom-borders instead of the old
 * heavy {@code border="1"} grid.
 */
final class EmailLayout {

    private EmailLayout() {}

    static final String TABLE = "width:100%;border-collapse:collapse;font-size:13px";
    static final String TH = "text-align:left;padding:6px 8px;border-bottom:2px solid #e5e7eb;"
            + "color:#6b7280;font-size:12px;font-weight:600";
    static final String TD = "padding:6px 8px;border-bottom:1px solid #f0f1f3";

    /** Wrap a body fragment in the light shell. {@code customIntro} (when non-blank)
     *  renders as a subtle note above the body. */
    static String shell(String title, String subtitle, String customIntro, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,"
                + "Helvetica,Arial,sans-serif;max-width:640px;margin:0 auto;color:#1f2328;"
                + "font-size:14px;line-height:1.5\">");
        sb.append("<h1 style=\"font-size:18px;margin:0 0 2px\">").append(escape(title)).append("</h1>");
        if (subtitle != null && !subtitle.isBlank()) {
            sb.append("<p style=\"margin:0 0 16px;color:#6b7280;font-size:12px\">")
              .append(escape(subtitle)).append("</p>");
        }
        if (customIntro != null && !customIntro.isBlank()) {
            sb.append("<div style=\"background:#f6f8fa;border-left:3px solid #2563eb;"
                    + "padding:10px 12px;border-radius:4px;margin:0 0 16px;white-space:pre-wrap\">")
              .append(escape(customIntro)).append("</div>");
        }
        sb.append(body);
        sb.append("</div>");
        return sb.toString();
    }

    /** Section heading inside a report body. */
    static String h2(String text) {
        return "<h2 style=\"font-size:14px;margin:18px 0 6px;color:#374151\">" + escape(text) + "</h2>";
    }

    /** A status word coloured green (ok) / amber (unknown) / red (down). */
    static String statusChip(String status) {
        boolean ok = "UP".equals(status) || "HEALTHY".equals(status);
        boolean unknown = "UNKNOWN".equals(status);
        String color = ok ? "#137333" : unknown ? "#8a6d00" : "#b00020";
        return "<span style=\"color:" + color + ";font-weight:600\">" + escape(status) + "</span>";
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
