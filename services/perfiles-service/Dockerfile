FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -q -B dependency:go-offline
COPY src src

RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl

ARG OTEL_JAVA_AGENT_VERSION=2.33.1
RUN curl -fsSL "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
    -o /app/opentelemetry-javaagent.jar

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]
