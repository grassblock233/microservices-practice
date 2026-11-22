FROM maven:3.9.16-eclipse-temurin-25 AS builder
WORKDIR /build

COPY pom.xml ./pom.xml
COPY maven-settings.xml /tmp/maven-settings.xml
COPY src ./src
RUN mvn -s /tmp/maven-settings.xml -B -ntp clean package

FROM eclipse-temurin:25.0.4.1_1-jre
WORKDIR /app

COPY --from=builder --chown=10001:10001 /build/target/*.jar ./app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
