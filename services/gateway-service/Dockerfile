FROM maven:3.9.16-eclipse-temurin-25 AS builder
WORKDIR /build
COPY compose.yaml ./compose.yaml
WORKDIR /build/services/gateway-service
COPY services/gateway-service/pom.xml ./pom.xml
COPY maven-settings.xml /tmp/maven-settings.xml
COPY services/gateway-service/src ./src
RUN mvn -s /tmp/maven-settings.xml -B -ntp clean package
FROM eclipse-temurin:25.0.4.1_1-jre
USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder --chown=10001:10001 /build/services/gateway-service/target/*.jar ./app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=20 CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
