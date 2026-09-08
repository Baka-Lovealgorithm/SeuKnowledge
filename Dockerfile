# syntax=docker/dockerfile:1

# Build with a pinned Maven/JDK toolchain so image builds do not depend on
# Maven Wrapper's download shell or the host Maven installation.
FROM maven:3.9.16-eclipse-temurin-21-jammy AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src/ src/
RUN mvn -B -DskipTests package

# Keep the runtime image small while retaining wget for the Compose health check.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
