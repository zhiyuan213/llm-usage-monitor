ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-alpine
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2/repository --mount=type=secret,id=maven_settings if [ -f /run/secrets/maven_settings ]; then mvn -s /run/secrets/maven_settings -B dependency:go-offline; else mvn -B dependency:go-offline; fi
COPY src src
RUN --mount=type=cache,target=/root/.m2/repository --mount=type=secret,id=maven_settings if [ -f /run/secrets/maven_settings ]; then mvn -s /run/secrets/maven_settings -B package -DskipTests; else mvn -B package -DskipTests; fi
FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build /build/target/metertrail-0.1.0.jar app.jar
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
