FROM maven:3.9.16-eclipse-temurin-25 AS builder
WORKDIR /build
COPY pom.xml ./pom.xml
COPY src ./src
RUN mvn -B -ntp clean package
FROM eclipse-temurin:25.0.4.1_1-jre
USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder --chown=10001:10001 /build/target/*.jar ./app.jar
USER 10001:10001
EXPOSE 8083
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=20 CMD curl -fsS http://localhost:8083/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
