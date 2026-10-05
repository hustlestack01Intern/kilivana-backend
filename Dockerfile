FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
RUN apk add --no-cache curl
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw dependency:go-offline -q

COPY src src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -g 1000 -S appgroup && adduser -u 1000 -S appuser -G appgroup \
    && apk add --no-cache su-exec
COPY --from=builder /app/target/*.jar app.jar
# The prod profile stores uploads under /data/uploads. This directory is created here so
# the docker-compose named volume is seeded with the right ownership; Docker copies the
# image directory, ownership included, into a fresh volume.
RUN mkdir -p /data/uploads && chown -R appuser:appgroup /data
# The image IS the deployment artifact, so it carries the profile it ships with: local
# development runs ./mvnw spring-boot:run and never touches this image, and
# docker-compose.yml already sets SPRING_PROFILES_ACTIVE=prod.
ENV SPRING_PROFILES_ACTIVE=prod
# Deliberately NO `USER appuser` here. The container must start as root so the entrypoint
# can chown a mounted volume, then drop to uid 1000 itself; the JVM never runs as root.
# Railway mounts volumes as root and RAILWAY_RUN_UID=0 is its only documented permission
# knob, so starting unprivileged makes the chown impossible on a Railway volume.
COPY docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 0755 /usr/local/bin/docker-entrypoint.sh
EXPOSE 8080
ENV MEDIA_ROOT=/data/uploads
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]