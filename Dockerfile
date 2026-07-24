# ---- 前端构建 ----
FROM node:20-alpine AS frontend-builder
WORKDIR /frontend
COPY codeatlas-web/package.json codeatlas-web/package-lock.json ./
RUN npm ci --silent
COPY codeatlas-web/ .
RUN npm run build

# ---- 后端构建 ----
FROM eclipse-temurin:17-jdk-alpine AS backend-builder
WORKDIR /app
COPY pom.xml ./
COPY codeatlas-common/pom.xml codeatlas-common/
COPY codeatlas-engine/pom.xml codeatlas-engine/
COPY codeatlas-server/pom.xml codeatlas-server/
RUN mvn dependency:go-offline -B -q || true

COPY . .
# 将前端构建产物放入 Spring Boot 静态资源目录
COPY --from=frontend-builder /frontend/dist codeatlas-server/src/main/resources/static/
RUN mvn package -DskipTests -B -q

# ---- 运行镜像 ----
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache tini curl
WORKDIR /app
RUN addgroup --system codeatlas && adduser --system --ingroup codeatlas codeatlas
COPY --from=backend-builder /app/codeatlas-server/target/*.jar app.jar
USER codeatlas
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["/sbin/tini", "--", "java", "-Xms512m", "-Xmx2g", "-XX:+UseG1GC", "-jar", "app.jar"]
