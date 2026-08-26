# KUBE-1 — Flyway migrations image (jmeter-cloud-flyway).
#
# Compose bind-mounts postgres/migrations/ into the stock flyway image;
# Kubernetes has no repo checkout to mount, so the migrations ship inside
# an image instead — versioned with the deploy, no ConfigMap size limits,
# same pattern as every service image. Built from this directory:
#
#   docker build -t jmeter-cloud-flyway:dev postgres/
#
# All three migration sets are baked in so one image serves any consumer;
# which sets actually run is the Job's choice (postgres/kube/base/
# flywayJob.yaml runs metrics + globalrun — k8srun belongs to the
# standalone jmeter-orchestrator-k8s deployment, not this stack).
FROM flyway/flyway:10
COPY migrations/metrics    /flyway/sql/metrics
COPY migrations/globalrun  /flyway/sql/globalrun
COPY migrations/k8srun     /flyway/sql/k8srun
