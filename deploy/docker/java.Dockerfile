FROM gradle:8.14.3-jdk21 AS build
USER root
RUN mkdir -p /workspace && chown gradle:gradle /workspace
USER gradle
WORKDIR /workspace
COPY --chown=gradle:gradle . .
ARG APP_NAME=platform-api
RUN gradle --no-daemon :apps:${APP_NAME}:bootJar -x test \
 && find "apps/${APP_NAME}/build/libs" -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} /tmp/app.jar \; \
 && test -s /tmp/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
