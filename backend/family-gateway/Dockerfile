# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml pom.xml
COPY backend/family-gateway family-gateway
RUN mvn -q -ntp -pl family-gateway -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S family && adduser -S family -G family
WORKDIR /app
COPY --from=build --chown=family:family /build/family-gateway/target/family-gateway-0.1.0-SNAPSHOT.jar app.jar
USER family
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
