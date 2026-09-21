FROM gradle:8.14.3-jdk21 AS build
USER root
RUN mkdir -p /workspace && chown gradle:gradle /workspace
USER gradle
WORKDIR /workspace
COPY --chown=gradle:gradle . .
ARG APP_NAME=platform-api
RUN gradle --no-daemon :apps:${APP_NAME}:bootJar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
ARG APP_NAME=platform-api
COPY --from=build /workspace/apps/${APP_NAME}/build/libs/*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
