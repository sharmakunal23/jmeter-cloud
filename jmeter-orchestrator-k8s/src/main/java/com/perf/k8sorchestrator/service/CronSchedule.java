package com.perf.k8sorchestrator.service;

import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron parsing + next-fire math, built on Spring's
 * {@link CronExpression} so we add no scheduler dependency (no Quartz).
 *
 * <p>Operators write either the familiar <b>5-field unix</b> cron
 * ({@code "0 2 * * *"} = 02:00 daily) or Spring's <b>6-field</b> form with a
 * leading seconds field ({@code "0 0 2 * * *"}); the {@code @macro} shorthands
 * ({@code @daily}, {@code @hourly}, …) also pass through. A 5-field expression
 * is normalised to 6 fields by prepending {@code "0 "} (fire at second 0).
 *
 * <p>All next-fire computation is done in the schedule's IANA timezone so
 * "02:00 in America/New_York" survives daylight-saving transitions correctly.
 * Stateless — pure functions, unit-tested directly.
 */
public final class CronSchedule {

    private CronSchedule() {}

    /**
     * Validate a cron expression + timezone. Throws {@link InvalidCronException}
     * with an operator-readable message on any problem (bad fields, unknown
     * zone, an expression that can never fire). Called by the controller at
     * create/update so a bad schedule is a 400, never a runtime surprise.
     */
    public static void validate(String cronExpression, String timeZone) {
        ZoneId zone = resolveZone(timeZone);
        CronExpression parsed = parse(cronExpression);
        // An expression like "0 0 0 30 2 *" (Feb 30) parses but never fires —
        // reject it now rather than persisting a schedule that does nothing.
        if (parsed.next(ZonedDateTime.now(zone)) == null) {
            throw new InvalidCronException(
                    "cron expression '" + cronExpression + "' yields no future fire time");
        }
    }

    /**
     * The next fire time strictly after {@code from}, computed in {@code zone}.
     * Returns null only when the expression can never fire again (callers that
     * validated up front won't see null). Used both to seed {@code nextFireAt}
     * at create and to advance it catch-up-once at claim time.
     */
    public static Instant nextFireAfter(String cronExpression, String timeZone, Instant from) {
        ZoneId zone = resolveZone(timeZone);
        CronExpression parsed = parse(cronExpression);
        ZonedDateTime next = parsed.next(ZonedDateTime.ofInstant(from, zone));
        return next == null ? null : next.toInstant();
    }

    /**
     * The next {@code count} fire times after {@code from}. Server-side mirror
     * of the UI's "next 5 fires" preview (kept here too so the contract has one
     * source of truth and tests can pin DST behaviour).
     */
    public static List<Instant> nextFires(String cronExpression, String timeZone, Instant from, int count) {
        ZoneId zone = resolveZone(timeZone);
        CronExpression parsed = parse(cronExpression);
        List<Instant> out = new ArrayList<>(Math.max(0, count));
        ZonedDateTime cursor = ZonedDateTime.ofInstant(from, zone);
        for (int i = 0; i < count; i++) {
            ZonedDateTime next = parsed.next(cursor);
            if (next == null) break;
            out.add(next.toInstant());
            cursor = next;
        }
        return out;
    }

    /** Parse + normalise to a {@link CronExpression}, mapping any failure to
     *  {@link InvalidCronException}. */
    static CronExpression parse(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new InvalidCronException("cron expression is required");
        }
        String normalised = normalise(cronExpression.trim());
        try {
            return CronExpression.parse(normalised);
        } catch (IllegalArgumentException e) {
            throw new InvalidCronException(
                    "invalid cron expression '" + cronExpression + "': " + e.getMessage());
        }
    }

    /** 5-field unix → 6-field (prepend seconds); 6-field + {@code @macro} pass
     *  through; anything else is rejected. */
    private static String normalise(String expr) {
        if (expr.startsWith("@")) {
            return expr; // @daily / @hourly / … handled natively by CronExpression
        }
        String[] fields = expr.split("\\s+");
        if (fields.length == 5) {
            return "0 " + expr;
        }
        if (fields.length == 6) {
            return expr;
        }
        throw new InvalidCronException(
                "cron expression must have 5 (unix) or 6 (with seconds) fields, or be an @macro; got "
                        + fields.length + " fields in '" + expr + "'");
    }

    static ZoneId resolveZone(String timeZone) {
        String tz = (timeZone == null || timeZone.isBlank()) ? "UTC" : timeZone.trim();
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw new InvalidCronException("unknown timeZone '" + timeZone + "'");
        }
    }

    /** Operator-facing validation error; controller maps to 400 INVALID_CRON. */
    public static class InvalidCronException extends RuntimeException {
        public InvalidCronException(String message) { super(message); }
    }
}
