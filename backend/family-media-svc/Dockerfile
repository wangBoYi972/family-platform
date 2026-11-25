# syntax=docker/dockerfile:1
# 多阶段构建：maven 编译 → JRE 运行，运行用户非 root，JVM 使用容器内存上限。
# COPY backend/ 整体复制是有意为之：父 pom 声明了全部模块，只复制当前服务的少数依赖，
# Maven 会直接报 "Child module ... does not exist"，镜像根本构建不出来（.dockerignore 已排除 target/logs）。
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY backend/pom.xml pom.xml
COPY backend/ .
RUN mvn -q -ntp -pl family-media-svc -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S family && adduser -S family -G family
WORKDIR /app
COPY --from=build --chown=family:family /build/family-media-svc/target/family-media-svc-0.1.0-SNAPSHOT.jar app.jar
USER family
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
