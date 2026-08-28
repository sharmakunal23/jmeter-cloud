# KUBE-1 — Flyway migrations image (jmeter-cloud-flyway).
#
# Compose bind-mounts postgres/migrations/ into the stock flyway image;
# Kubernetes has no repo checkout to mount, so the migrations ship inside
# an image instead — versioned with the deploy, no ConfigMap size limits,
# same pattern as every service image. Built from this directory:
#
#   docker build -t jmeter-cloud-flyway:dev postgres/
#
# Both migration sets are baked in; the Job (postgres/kube/base/
# flywayJob.yaml) runs metrics + globalrun.
FROM flyway/flyway:10
COPY migrations/metrics    /flyway/sql/metrics
COPY migrations/globalrun  /flyway/sql/globalrun
