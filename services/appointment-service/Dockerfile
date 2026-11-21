# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY shared-auth/pom.xml shared-auth/pom.xml
COPY services/auth-service/pom.xml services/auth-service/pom.xml
COPY services/appointment-service/pom.xml services/appointment-service/pom.xml
COPY services/report-service/pom.xml services/report-service/pom.xml
COPY services/prediction-service/pom.xml services/prediction-service/pom.xml
COPY shared-auth/src shared-auth/src
COPY services/appointment-service/src services/appointment-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -pl services/appointment-service -am -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache curl && addgroup -S -g 10001 ckd && adduser -S -D -H -u 10001 -G ckd ckd
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/services/appointment-service/target/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD curl --fail --silent http://127.0.0.1:9082/actuator/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
