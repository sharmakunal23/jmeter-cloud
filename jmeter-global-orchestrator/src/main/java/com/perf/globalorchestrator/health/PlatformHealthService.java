package com.perf.globalorchestrator.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.RegionCapacity;
import com.perf.globalorchestrator.health.PlatformHealth.Component;
import com.perf.globalorchestrator.region.RegionCapabilities;
import com.perf.globalorchestrator.region.RegionRegistry;
import com.perf.globalorchestrator.region.RegionStatus;
import com.perf.globalorchestrator.repo.PodRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Builds the {@link PlatformHealth} tree: this service's own contributors, the
 * metrics-consumer and document-service actuators, and every region (the
 * regional's last probe verdict + the worker registry counts). Probes run in
 * parallel on virtual threads with a bounded timeout, on a schedule — a
 * request never waits on a probe; it reads the last snapshot.
 */
@org.springframework.stereotype.Component
public class PlatformHealthService {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformHealthService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<HealthComponent> ownHealth;
    private final RegionRegistry regions;
    private final PodRepository pods;
    private final String metricsConsumerUrl;
    private final String documentServiceUrl;
    private final Duration probeTimeout;
    private final HttpClient http;
    private final AtomicReference<PlatformHealth> snapshot =
            new AtomicReference<>(new PlatformHealth(PlatformHealth.UNKNOWN, Instant.now(), List.of()));

    @org.springframework.beans.factory.annotation.Autowired   // two constructors: this is the one Spring uses
    public PlatformHealthService(HealthEndpoint healthEndpoint, RegionRegistry regions, PodRepository pods,
                                 @Value("${metricsConsumer.baseUrl:http://metrics-consumer:8083}") String metricsConsumerUrl,
                                 @Value("${documentService.baseUrl:http://document-service:8084}") String documentServiceUrl,
                                 @Value("${globalOrchestrator.platformHealth.probeTimeoutMs:3000}") long probeTimeoutMs) {
        this(healthEndpoint::health, regions, pods, metricsConsumerUrl, documentServiceUrl,
                Duration.ofMillis(probeTimeoutMs));
    }

    PlatformHealthService(Supplier<HealthComponent> ownHealth, RegionRegistry regions, PodRepository pods,
                          String metricsConsumerUrl, String documentServiceUrl,
                          Duration probeTimeout) {
        this.ownHealth = ownHealth;
        this.regions = regions;
        this.pods = pods;
        this.metricsConsumerUrl = stripSlash(metricsConsumerUrl);
        this.documentServiceUrl = stripSlash(documentServiceUrl);
        this.probeTimeout = probeTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(probeTimeout).build();
    }

    /** The last snapshot — never blocks on a probe. */
    public PlatformHealth snapshot() {
        return snapshot.get();
    }

    @Scheduled(fixedDelayString = "${globalOrchestrator.platformHealth.intervalMs:60000}",
               initialDelayString = "${globalOrchestrator.platformHealth.initialDelayMs:1500}")
    public void refresh() {
        try {
            snapshot.set(probeAll());
        } catch (RuntimeException e) {
            LOG.warn("platform health refresh failed: {}", e.toString());
        }
    }

    /** A synchronous refresh (bounded by the probe timeout) — the UI's manual refresh. */
    public PlatformHealth refreshNow() {
        refresh();
        return snapshot.get();
    }

    PlatformHealth probeAll() {
        Instant now = Instant.now();
        List<CompletableFuture<Component>> probes;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            probes = List.of(
                    CompletableFuture.supplyAsync(this::global, pool),
                    CompletableFuture.supplyAsync(() -> actuator("metrics-consumer", "Metrics consumer",
                            metricsConsumerUrl, "/actuator/health", this::describeConsumer), pool),
                    CompletableFuture.supplyAsync(() -> actuator("document-service", "Document service",
                            documentServiceUrl, "/actuator/health/readiness", this::describeDocumentService), pool));
            List<Component> top = new ArrayList<>();
            for (CompletableFuture<Component> f : probes) {
                try {
                    top.add(f.get(probeTimeout.toMillis() + 500, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    top.add(Component.leaf("probe", "probe", "service", PlatformHealth.UNKNOWN, "probe did not answer: " + e.getMessage()));
                }
            }
            top.add(dataCenters(now));
            return new PlatformHealth(PlatformHealth.worst(top), now, top);
        }
    }

    // ── this service ──────────────────────────────────────────────────

    private Component global() {
        Instant now = Instant.now();
        List<Component> children = new ArrayList<>();
        try {
            HealthComponent root = ownHealth.get();
            collect("", root, children);
        } catch (RuntimeException e) {
            children.add(Component.leaf("health", "Health endpoint", "dependency", PlatformHealth.UNKNOWN, "probe failed: " + e.getMessage()));
        }
        // The database pools are load-bearing; the cache is not (a cold cache costs one re-read).
        String status = PlatformHealth.UP;
        for (Component c : children) {
            boolean optional = isOptional(c.id());
            String s = c.status();
            if (!PlatformHealth.UP.equals(s)) {
                status = PlatformHealth.worse(status, optional && PlatformHealth.DOWN.equals(s) ? PlatformHealth.DEGRADED : s);
            }
        }
        String detail = PlatformHealth.UP.equals(status)
                ? regions.ids().size() + " cluster(s) registered"
                : children.stream().filter(c -> !PlatformHealth.UP.equals(c.status())).map(Component::name).findFirst()
                        .map(n -> n + " is not healthy").orElse(null);
        return new Component("global-orchestrator", "Global orchestrator", "service", status, detail, null, now, null,
                Map.of("registeredClusters", regions.ids().size()), children);
    }

    private static void collect(String prefix, HealthComponent node, List<Component> out) {
        if (node instanceof CompositeHealth composite) {
            for (Map.Entry<String, HealthComponent> e : composite.getComponents().entrySet()) {
                String id = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                if (e.getValue() instanceof CompositeHealth) {
                    collect(id, e.getValue(), out);
                } else if (isDependency(id)) {
                    out.add(leaf(id, e.getValue()));
                }
            }
        } else if (node != null && isDependency(prefix)) {
            out.add(leaf(prefix, node));
        }
    }

    /**
     * Only the dependencies an operator can act on: the Oracle pools, the cache,
     * mail. Spring's process contributors (ping, liveness/readiness state, ssl,
     * disk) are the kubelet's business and would only add noise here.
     */
    private static boolean isDependency(String id) {
        return id.startsWith("db.") || id.equals("db") || id.equals("redis") || id.equals("mail");
    }

    /** The cache and mail are optional: DOWN only degrades the hub. */
    private static boolean isOptional(String id) {
        return id.startsWith("redis") || id.equals("mail");
    }

    private static Component leaf(String id, HealthComponent h) {
        String status = mapStatus(h.getStatus().getCode());
        String detail = null;
        if (h instanceof Health health) {
            Object db = health.getDetails().get("database");
            Object err = health.getDetails().get("error");
            detail = err != null ? String.valueOf(err) : db != null ? String.valueOf(db) : null;
        }
        return Component.leaf(id, friendly(id), "dependency", status, detail);
    }

    private static String friendly(String id) {
        return switch (id) {
            case "db.runStateDataSource", "db.globalrunDataSource" -> "Oracle · run state";
            case "db.metricsDataSource" -> "Oracle · metrics (reader)";
            case "db.metricsPurgeDataSource" -> "Oracle · metrics (purge)";
            case "db" -> "Oracle";
            case "redis" -> "Cache";
            case "mail" -> "Mail";
            default -> id;
        };
    }

    // ── the data-plane services ───────────────────────────────────────

    private interface Describer {
        Component describe(JsonNode body, long latencyMs, String url, Instant now);
    }

    private Component actuator(String id, String name, String base, String path, Describer describer) {
        Instant now = Instant.now();
        String url = base + path;
        long started = System.nanoTime();
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(probeTimeout)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - started) / 1_000_000;
            JsonNode body = resp.body() == null || resp.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(resp.body());
            return describer.describe(body, latency, base, now);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Component(id, name, "service", PlatformHealth.DOWN, "unreachable: " + e.getMessage(), base, now, null, null, null);
        }
    }

    /** The consumer's aggregate: {@code db} is load-bearing; {@code ingestProgress} is a fact (idle between runs is normal). */
    private Component describeConsumer(JsonNode body, long latencyMs, String base, Instant now) {
        JsonNode comps = body.path("components");
        List<Component> children = new ArrayList<>();
        String db = mapStatus(comps.path("db").path("status").asText("UNKNOWN"));
        children.add(Component.leaf("metrics-consumer.db", "Oracle · metrics", "dependency", db,
                comps.path("db").path("details").path("database").asText(null)));
        JsonNode ingest = comps.path("ingestProgress");
        Map<String, Object> facts = new LinkedHashMap<>();
        String ingestDetail = "no envelopes yet";
        if (!ingest.isMissingNode()) {
            long age = ingest.path("details").path("lastBatchAgeSeconds").asLong(-1);
            long batches = ingest.path("details").path("totalBatchesProcessed").asLong(0);
            facts.put("lastBatchAgeSeconds", age);
            facts.put("totalBatchesProcessed", batches);
            ingestDetail = batches == 0 ? "no envelopes since start"
                    : age >= 0 && age < 60 ? "receiving — last envelope " + age + " s ago"
                    : "idle — last envelope " + (age / 60) + " min ago (normal between runs)";
        }
        children.add(Component.leaf("metrics-consumer.ingest", "Ingest", "dependency", PlatformHealth.UP, ingestDetail));
        String status = db;
        String detail = PlatformHealth.UP.equals(status) ? ingestDetail : "database " + db.toLowerCase();
        return new Component("metrics-consumer", "Metrics consumer", "service", status, detail, base, now, latencyMs, facts, children);
    }

    /** The document-service readiness group: {@code storage} carries the blob mount's free space. */
    private Component describeDocumentService(JsonNode body, long latencyMs, String base, Instant now) {
        JsonNode comps = body.path("components");
        List<Component> children = new ArrayList<>();
        String status = mapStatus(body.path("status").asText("UNKNOWN"));
        JsonNode storage = comps.path("storage");
        Map<String, Object> facts = new LinkedHashMap<>();
        String storageDetail = null;
        if (!storage.isMissingNode()) {
            long usable = storage.path("details").path("usableBytes").asLong(-1);
            String reason = storage.path("details").path("reason").asText(null);
            if (usable >= 0) {
                facts.put("usableBytes", usable);
                storageDetail = humanBytes(usable) + " free";
            }
            if (reason != null) storageDetail = reason;
            children.add(Component.leaf("document-service.storage", "Blob storage", "dependency",
                    mapStatus(storage.path("status").asText("UNKNOWN")), storageDetail));
        }
        String detail = PlatformHealth.UP.equals(status) ? storageDetail
                : children.stream().filter(c -> !PlatformHealth.UP.equals(c.status())).map(c -> c.name() + ": " + c.detail()).findFirst()
                        .orElse("readiness " + status.toLowerCase());
        return new Component("document-service", "Document service", "service", status, detail, base, now, latencyMs, facts, children);
    }

    // ── the data centers ──────────────────────────────────────────────

    private Component dataCenters(Instant now) {
        Map<String, RegionCapacity> counts = new LinkedHashMap<>();
        try {
            for (RegionCapacity rc : pods.regionCapacities()) counts.put(rc.region(), rc);
        } catch (RuntimeException e) {
            LOG.debug("region capacities unavailable: {}", e.toString());
        }
        List<Component> regionsOut = new ArrayList<>();
        for (RegionStatus r : regions.all()) {
            List<Component> children = new ArrayList<>();
            String regionStatus = PlatformHealth.UP;
            String regionDetail;
            {
                String s = r.reachable() == null ? PlatformHealth.UNKNOWN : r.reachable() ? PlatformHealth.UP : PlatformHealth.DOWN;
                RegionCapabilities caps = r.capabilities();
                String d = PlatformHealth.DOWN.equals(s) ? (r.lastError() == null ? "unreachable" : r.lastError())
                        : PlatformHealth.UNKNOWN.equals(s) ? "not probed yet"
                        : "version " + (caps == null ? "?" : caps.version())
                            + (caps != null && caps.workersFree() != null ? " · room for " + caps.workersFree() + " more worker(s)" : "");
                Map<String, Object> facts = new LinkedHashMap<>();
                if (caps != null) {
                    facts.put("version", caps.version());
                    facts.put("image", caps.image());
                    if (caps.workersFree() != null) facts.put("workersFree", caps.workersFree());
                }
                children.add(new Component("region." + r.region() + ".regional-orchestrator", "Regional orchestrator", "regional-orchestrator",
                        s, d, r.url(), r.lastSeenAt(), null, facts.isEmpty() ? null : facts, null));
                regionStatus = s;
                regionDetail = PlatformHealth.DOWN.equals(s) ? "regional orchestrator unreachable" : null;
            }
            RegionCapacity rc = counts.get(r.region());
            long total = rc == null ? 0 : rc.totalPods(), idle = rc == null ? 0 : rc.idlePods(), lost = rc == null ? 0 : rc.lostPods();
            long busy = Math.max(0, total - idle - lost);
            String w = lost > 0 && idle + busy == 0 ? PlatformHealth.DOWN : lost > 0 ? PlatformHealth.DEGRADED : PlatformHealth.UP;
            String wd = total == 0 ? "no workers" : idle + " idle · " + busy + " busy" + (lost > 0 ? " · " + lost + " lost" : "");
            children.add(new Component("region." + r.region() + ".workers", "Workers", "workers", w, wd, null, now, null,
                    Map.of("total", total, "idle", idle, "busy", busy, "lost", lost), null));
            if (total > 0 || !PlatformHealth.UP.equals(w)) {
                regionStatus = PlatformHealth.worse(regionStatus, lost > 0 && !PlatformHealth.DOWN.equals(regionStatus) ? PlatformHealth.DEGRADED : PlatformHealth.UP);
            }
            if (regionDetail == null) regionDetail = wd;
            regionsOut.add(new Component("region." + r.region(), r.region(), "region", regionStatus, regionDetail, null, now, null, null, children));
        }
        String status;
        if (regionsOut.isEmpty()) {
            status = PlatformHealth.UNKNOWN;
        } else if (regionsOut.stream().allMatch(c -> PlatformHealth.UNKNOWN.equals(c.status()))) {
            status = PlatformHealth.UNKNOWN;          // right after a hub start: not probed yet is not degraded
        } else if (regionsOut.stream().allMatch(c -> PlatformHealth.DOWN.equals(c.status()))) {
            status = PlatformHealth.DOWN;
        } else if (regionsOut.stream().anyMatch(c -> !PlatformHealth.UP.equals(c.status()))) {
            status = PlatformHealth.DEGRADED;
        } else {
            status = PlatformHealth.UP;
        }
        long down = regionsOut.stream().filter(c -> PlatformHealth.DOWN.equals(c.status())).count();
        String detail = regionsOut.isEmpty() ? "no clusters registered"
                : PlatformHealth.UNKNOWN.equals(status) ? "waiting for the first cluster probe"
                : down == 0 ? regionsOut.size() + " cluster(s) serving" : down + " of " + regionsOut.size() + " cluster(s) down";
        return new Component("regions", "Clusters", "regions", status, detail, null, now, null, null, regionsOut);
    }

    // ── helpers ───────────────────────────────────────────────────────

    static String mapStatus(String actuator) {
        return switch (actuator == null ? "" : actuator) {
            case "UP" -> PlatformHealth.UP;
            case "DOWN", "OUT_OF_SERVICE" -> PlatformHealth.DOWN;
            case "UNKNOWN", "" -> PlatformHealth.UNKNOWN;
            default -> PlatformHealth.DEGRADED;
        };
    }

    static String humanBytes(long bytes) {
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return i == 0 ? bytes + " B" : String.format("%.0f %s", v, units[i]);
    }

    private static String stripSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
