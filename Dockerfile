# syntax=docker/dockerfile:1

# ---- Build stage: full JDK + Gradle, discarded after the jar is produced ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Copy only what's needed to resolve dependencies first, so a source-only change
# doesn't invalidate this (much slower) layer on rebuild.
COPY gradlew gradle.properties build.gradle settings.gradle ./
COPY gradle ./gradle
RUN ./gradlew dependencies --no-daemon

# Now the actual source, which changes far more often than dependencies do.
COPY src ./src
# bootJar only pulls in compileJava/processResources/classes - it never triggers
# test or checkstyle, so there's nothing to explicitly skip here. Tests already ran
# in CI before this image is ever built, and couldn't run in here anyway: the
# integration suite needs its own Docker socket for Testcontainers, which this
# build environment doesn't have.
RUN ./gradlew bootJar --no-daemon

# ---- Runtime stage: JRE only, no build toolchain, no source ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
