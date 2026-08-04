# syntax=docker/dockerfile:1
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml ./
COPY frontend ./frontend
COPY src ./src
COPY docs ./docs
COPY APP-META ./APP-META
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/workspace/target/npm-cache \
    mvn -DskipGitCommitId=true package

FROM eclipse-temurin:21-jre-jammy@sha256:468586c92d39f8cbad76574623db3fe001625ed4d895431c3ff7bd2ec9ce7ae3

RUN groupadd --system autowonder && \
    useradd --system --gid autowonder --home /app autowonder && \
    install -d -o autowonder -g autowonder /app/logs

WORKDIR /app
COPY --from=build /workspace/target/auto-wonder.jar /app/auto-wonder.jar

USER autowonder
EXPOSE 7001

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/auto-wonder.jar"]
