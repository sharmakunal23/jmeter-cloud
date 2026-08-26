package com.perf.k8sorchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.k8sorchestrator.domain.Actor;
import com.perf.k8sorchestrator.domain.RunEvent;
import com.perf.k8sorchestrator.domain.RunEventType;
import com.perf.k8sorchestrator.domain.Ulid;
import com.perf.k8sorchestrator.repo.RunEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * The single place that appends a {@code runEvent}. Shared by
 * {@link RunService} (operator-initiated run mutations + run-terminal events)
 * and {@link com.perf.k8sorchestrator.provision.PodRecycler} (system-driven
 * worker recycle), so the serialise-payload-then-insert primitive lives once.
 */
@Component
public class RunAuditWriter {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final RunEventRepository repo;
    private final ObjectMapper json;

    public RunAuditWriter(RunEventRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /**
     * Serialise a type-safe payload record (one of
     * {@link com.perf.k8sorchestrator.domain.RunEventPayloads}) to the JSONB
     * map and append one event. {@code eventId} is a fresh ULID; the
     * repository's {@code ON CONFLICT DO NOTHING} makes a same-id retry a no-op.
     *
     * <p>When called from inside a {@code @Transactional} run mutation the
     * insert commits/rolls back atomically with the mutation; when called from
     * a non-transactional path (terminal-state detection, recycle sweep) it
     * auto-commits on its own.
     */
    public void record(String runId, RunEventType type, Actor actor,
                       Object payloadRecord, String result) {
        record(Ulid.generate(), runId, type, actor, payloadRecord, result);
    }

    /**
     * Same as
     * {@link #record(String, RunEventType, Actor, Object, String)} but with a
     * caller-supplied <b>deterministic</b> eventId. Use for singleton-per-fact
     * system events (e.g. one {@code RESULTS_SAVED} per (run, worker)): every
     * replica computes the same id, so the PK's {@code ON CONFLICT DO NOTHING}
     * dedups across instances, not just same-id retries.
     */
    public void record(String eventId, String runId, RunEventType type, Actor actor,
                       Object payloadRecord, String result) {
        Map<String, Object> payload = json.convertValue(payloadRecord, MAP_TYPE);
        repo.insert(new RunEvent(
                eventId, runId, type, actor.name(), actor.source(),
                payload, result, Instant.now()));
    }
}
