FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /src
COPY extensions ./extensions
COPY demos/apps/request-logger-http-java/pom.xml ./demos/apps/request-logger-http-java/pom.xml
COPY demos/apps/request-logger-http-java/src ./demos/apps/request-logger-http-java/src

WORKDIR /src/demos/apps/request-logger-http-java
RUN mvn -q package

FROM eclipse-temurin:17-jre

COPY --from=build /src/demos/apps/request-logger-http-java/target/classes /app

ENTRYPOINT ["java", "-cp", "/app", "io.runtimeconditions.demos.requestlogger.RequestLoggerHttp"]
