# MusicTraceBack 多阶段构建镜像
# 阶段 1:构建(Maven + JDK)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

# 阶段 2:运行(仅 JRE)
FROM eclipse-temurin:17-jre
WORKDIR /app

# 数据目录(通过 volume 持久化)
VOLUME ["/app/data"]

COPY --from=build /build/target/MusicTraceBack-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
