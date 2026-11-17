# syntax=docker/dockerfile:1
# 多阶段构建：maven 编译 → JRE 运行，运行用户非 root，JVM 使用容器内存上限。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml pom.xml
COPY backend/family-common family-common
COPY backend/family-api family-api
COPY backend/family-security family-security
COPY backend/family-ai-svc family-ai-svc
RUN mvn -q -ntp -pl family-ai-svc -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S family && adduser -S family -G family
WORKDIR /app
COPY --from=build --chown=family:family /build/family-ai-svc/target/family-ai-svc-0.1.0-SNAPSHOT.jar app.jar
USER family
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8091
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
