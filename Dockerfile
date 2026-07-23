FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml ./
COPY codeatlas-common/pom.xml codeatlas-common/
COPY codeatlas-engine/pom.xml codeatlas-engine/
COPY codeatlas-server/pom.xml codeatlas-server/
COPY codeatlas-web/pom.xml codeatlas-web/
RUN mvn dependency:go-offline -B -q || true

COPY . .
RUN mvn package -DskipTests -B -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup --system codeatlas && adduser --system --ingroup codeatlas codeatlas
COPY --from=builder /app/codeatlas-server/target/*.jar app.jar
USER codeatlas
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --retries=3 CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
