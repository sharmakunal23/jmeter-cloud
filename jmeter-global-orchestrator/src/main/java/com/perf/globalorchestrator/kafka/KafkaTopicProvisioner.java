package com.perf.globalorchestrator.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * KAFKA-PER-APP Phase B — owns the Kafka-side lifecycle of a registered
 * application's per-app topics. Wraps Apache Kafka's {@link AdminClient};
 * exposes idempotent create / delete / exists primitives keyed on the
 * application name (which is also the topic-name suffix per the rule
 * pinned in {@code kafka/README.md}: {@code jmeter.metrics.<applicationName>}
 * for the main topic, {@code .DLT} for the dead-letter).
 *
 * <p>Wired into {@code ApplicationController} so {@code POST /applications}
 * creates both topics after the row insert + {@code DELETE /applications/{id}}
 * removes them (gated by {@code globalOrchestrator.kafka.deleteTopicsOnAppDelete}
 * — default true local, false cloud, since audit policy may require retention).
 *
 * <p>All public methods are idempotent:
 * <ul>
 *   <li>{@link #createForApplication} — {@code TopicExistsException} is
 *       swallowed; second invocation is a no-op.</li>
 *   <li>{@link #deleteForApplication} — {@code UnknownTopicOrPartition} is
 *       swallowed; deleting an absent topic is a no-op.</li>
 *   <li>{@link #topicExists} — pure read.</li>
 * </ul>
 *
 * <p>The single shared {@link AdminClient} bean is short-lived per call —
 * {@code AdminClient} instances are heavy-weight (per-instance NetworkClient
 * thread + metadata cache). One singleton injected here suits the
 * once-per-app-lifecycle workload; we don't pool.
 */
@Component
public class KafkaTopicProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicProvisioner.class);

    /** Topic-name prefix locked in by {@code kafka/README.md}. */
    public static final String TOPIC_PREFIX = "jmeter.metrics.";
    /** DLT suffix matches Spring Kafka's default {@code DeadLetterPublishingRecoverer}. */
    public static final String DLT_SUFFIX = ".DLT";

    private final AdminClient admin;
    private final int partitions;
    private final short replicationFactor;
    private final long retentionMs;
    private final long adminTimeoutMs;

    public KafkaTopicProvisioner(
            // @Lazy inserts a CGLIB proxy at the injection point so the
            // underlying AdminClient is only constructed on first method
            // call. Lets the global-orch boot even when the broker URL is
            // unresolvable (test contexts that mock this provisioner; or
            // a misconfigured cloud where boot must succeed before the
            // operator can fix the broker).
            @Lazy AdminClient admin,
            @Value("${globalOrchestrator.kafka.topicPartitions:3}") int partitions,
            @Value("${globalOrchestrator.kafka.topicReplicationFactor:1}") short replicationFactor,
            @Value("${globalOrchestrator.kafka.topicRetentionMs:604800000}") long retentionMs,
            @Value("${globalOrchestrator.kafka.adminTimeoutMs:15000}") long adminTimeoutMs) {
        this.admin = admin;
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
        this.retentionMs = retentionMs;
        this.adminTimeoutMs = adminTimeoutMs;
    }

    /**
     * Creates the main topic + DLT for {@code applicationName}. Idempotent —
     * already-existing topics are silently accepted (the operator may have
     * pre-provisioned via {@code kafka-topics.sh} or this method may be
     * re-run after a partial failure).
     *
     * @throws KafkaTopicProvisionException on any non-recoverable error
     *         (broker unreachable, auth failure, partition-count mismatch).
     *         The caller is expected to surface this as a 500 + roll back
     *         the application row.
     */
    public void createForApplication(String applicationName) {
        String main = mainTopic(applicationName);
        String dlt = dltTopic(applicationName);
        Map<String, String> configs = new HashMap<>();
        configs.put(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(retentionMs));
        configs.put(TopicConfig.COMPRESSION_TYPE_CONFIG, "zstd");
        try {
            List<NewTopic> topics = List.of(
                    new NewTopic(main, partitions, replicationFactor).configs(configs),
                    new NewTopic(dlt,  partitions, replicationFactor).configs(configs));
            admin.createTopics(topics).all()
                    .get(adminTimeoutMs, TimeUnit.MILLISECONDS);
            LOG.info("Created Kafka topics for application '{}': {}, {}", applicationName, main, dlt);
        } catch (ExecutionException e) {
            // createTopics().all() rejects the future as a single ExecutionException
            // even if only one of the two topics already existed. Drill in to
            // the per-topic futures to distinguish "all already existed" from
            // "real failure". Idempotent path: TopicExistsException on every
            // not-yet-created topic ⇒ silently ok.
            if (allCausesAreTopicExists(e)) {
                LOG.debug("Topics already exist for application '{}': {}, {}", applicationName, main, dlt);
                return;
            }
            throw new KafkaTopicProvisionException(
                    "createTopics failed for application '" + applicationName + "': " + e.getCause(),
                    e.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KafkaTopicProvisionException("interrupted while creating topics", ie);
        } catch (TimeoutException te) {
            throw new KafkaTopicProvisionException(
                    "timed out (" + adminTimeoutMs + " ms) creating topics for '" + applicationName + "'", te);
        }
    }

    /**
     * Deletes the main topic + DLT for {@code applicationName}. Idempotent —
     * absent topics are silently accepted.
     */
    public void deleteForApplication(String applicationName) {
        String main = mainTopic(applicationName);
        String dlt = dltTopic(applicationName);
        try {
            admin.deleteTopics(List.of(main, dlt)).all()
                    .get(adminTimeoutMs, TimeUnit.MILLISECONDS);
            LOG.info("Deleted Kafka topics for application '{}': {}, {}", applicationName, main, dlt);
        } catch (ExecutionException e) {
            if (allCausesAreUnknownTopic(e)) {
                LOG.debug("Topics already absent for application '{}': {}, {}", applicationName, main, dlt);
                return;
            }
            throw new KafkaTopicProvisionException(
                    "deleteTopics failed for application '" + applicationName + "': " + e.getCause(),
                    e.getCause());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KafkaTopicProvisionException("interrupted while deleting topics", ie);
        } catch (TimeoutException te) {
            throw new KafkaTopicProvisionException(
                    "timed out (" + adminTimeoutMs + " ms) deleting topics for '" + applicationName + "'", te);
        }
    }

    /**
     * Pure read — true when both the main topic AND its DLT exist. Used by
     * the IT to assert the create/delete contract; not on the request hot
     * path.
     */
    public boolean topicExists(String applicationName) {
        String main = mainTopic(applicationName);
        String dlt = dltTopic(applicationName);
        try {
            Set<String> all = admin.listTopics().names()
                    .get(adminTimeoutMs, TimeUnit.MILLISECONDS);
            return all.contains(main) && all.contains(dlt);
        } catch (ExecutionException | TimeoutException e) {
            throw new KafkaTopicProvisionException(
                    "listTopics failed while checking '" + applicationName + "': " + e, e);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new KafkaTopicProvisionException("interrupted while listing topics", ie);
        }
    }

    public static String mainTopic(String applicationName) {
        return TOPIC_PREFIX + applicationName;
    }

    public static String dltTopic(String applicationName) {
        return TOPIC_PREFIX + applicationName + DLT_SUFFIX;
    }

    private static boolean allCausesAreTopicExists(ExecutionException e) {
        // createTopics returns a CompletableFuture<Void> for each topic; .all()
        // collapses them. If ANY topic genuinely failed (not TopicExists),
        // we want to surface that — so this only returns true when the entire
        // root-cause chain points to the idempotent case.
        Throwable c = e.getCause();
        return c instanceof TopicExistsException;
    }

    private static boolean allCausesAreUnknownTopic(ExecutionException e) {
        Throwable c = e.getCause();
        return c instanceof UnknownTopicOrPartitionException;
    }

    /**
     * Wraps any Kafka-side failure during create / delete / list. The
     * controller catches this and translates it into a 500
     * {@code TOPIC_CREATE_FAILED} (or {@code TOPIC_DELETE_FAILED}) with the
     * row rolled back so the registry stays consistent with Kafka.
     */
    public static final class KafkaTopicProvisionException extends RuntimeException {
        public KafkaTopicProvisionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
