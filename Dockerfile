# syntax=docker/dockerfile:1
# One Dockerfile for all three services: docker compose passes SERVICE as a build arg.
FROM eclipse-temurin:21-jdk AS build
ARG SERVICE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -q -DskipTests -pl ${SERVICE} -am package

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}.jar app.jar
USER app
# Small heap so the whole stack fits on an 8 GB machine (see docs/decisions.md ADR-004).
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=60s --retries=20 \
    CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
