package com.perf.globalorchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perf.globalorchestrator.domain.Actor;
import com.perf.globalorchestrator.domain.RunEvent;
import com.perf.globalorchestrator.domain.RunEventType;
import com.perf.globalorchestrator.domain.Ulid;
import com.perf.globalorchestrator.repo.RunEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * AUDIT-TRAIL — the single place that appends a {@code runEvent}. Shared by
 * {@link RunService} (operator-initiated run mutations + run-terminal events)
 * and {@link com.perf.globalorchestrator.provision.PodRecycler} (system-driven
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
     * {@link com.perf.globalorchestrator.domain.RunEventPayloads}) to the JSONB
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
        Map<String, Object> payload = json.convertValue(payloadRecord, MAP_TYPE);
        repo.insert(new RunEvent(
                Ulid.generate(), runId, type, actor.name(), actor.source(),
                payload, result, Instant.now()));
    }
}
