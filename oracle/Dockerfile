# jmeter-cloud-flyway — the migration image for compose and the K8s Job.
#
# The stock flyway/flyway image bundles its own Oracle driver under
# /flyway/drivers (ojdbc11 21.18 in flyway:11). This image replaces it with
# the ojdbc11 build the services use — two OracleDriver classes on one
# classpath is a coin toss — and bakes the migrations in for Kubernetes,
# which has no repo checkout to bind-mount. Compose bind-mounts the same
# directory over the copy so an edit needs no rebuild.
# Flyway 11 is the first line that knows Oracle 23ai; 10.x warns it is
# untested past 21.3.
#
#   docker build --provenance=false -t jmeter-cloud-flyway:dev oracle/
FROM flyway/flyway:11
RUN rm -f /flyway/drivers/ojdbc*.jar
# Pinned to the version Spring Boot 3.5.16's BOM manages (oracle-database.version),
# so the Job and the services speak through the same driver build.
ADD --chmod=644 https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.7.0.25.01/ojdbc11-23.7.0.25.01.jar /flyway/drivers/ojdbc11.jar
COPY migrations /flyway/sql
