# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
# Shown in /actuator/info; set by the image workflow (release tag, or "edge").
ARG VERSION
RUN ./gradlew --no-daemon bootJar ${VERSION:+-PformkilnVersion=$VERSION} \
    && cp build/libs/*.jar app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system formkiln && useradd --system --gid formkiln formkiln
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER formkiln
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
