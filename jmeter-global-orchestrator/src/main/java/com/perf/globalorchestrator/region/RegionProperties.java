package com.perf.globalorchestrator.region;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The deployment's regions, from {@code REGIONS} — a comma-separated list of
 * {@code id} or {@code id=url}. An entry with a URL is <b>routed</b>: its
 * workers are created and reached through the {@code jmeter-regional-orchestrator}
 * at that URL. A bare id is <b>direct</b>: workers there are declared by the
 * operator and reached at their own {@code baseUrl}.
 *
 * <p>Empty means "no deployment override" — the UI keeps its own defaults and
 * every region is direct.
 */
@Component
public class RegionProperties {

    private final List<String> ids;
    private final Map<String, String> urls;

    public RegionProperties(@Value("${globalOrchestrator.regions:}") String raw) {
        Map<String, String> parsed = parse(raw);
        this.ids = List.copyOf(parsed.keySet());
        Map<String, String> routed = new LinkedHashMap<>();
        parsed.forEach((id, url) -> { if (url != null) routed.put(id, url); });
        this.urls = Collections.unmodifiableMap(routed);
    }

    /** Every region id, in declaration order. */
    public List<String> ids() {
        return ids;
    }

    /** Regional orchestrator URL for a routed region; empty for a direct or unknown one. */
    public Optional<String> urlOf(String region) {
        return Optional.ofNullable(urls.get(region));
    }

    /** Region id → URL for the routed regions only. */
    public Map<String, String> routed() {
        return urls;
    }

    static Map<String, String> parse(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        List<String> entries = new ArrayList<>();
        for (String s : raw.split(",")) {
            if (!s.isBlank()) entries.add(s.trim());
        }
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            String id = (eq < 0 ? entry : entry.substring(0, eq)).trim();
            String url = eq < 0 ? "" : entry.substring(eq + 1).trim();
            if (id.isEmpty()) {
                throw new IllegalArgumentException("REGIONS entry has no region id: '" + entry + "'");
            }
            while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            out.putIfAbsent(id, url.isEmpty() ? null : url);
        }
        return out;
    }
}
