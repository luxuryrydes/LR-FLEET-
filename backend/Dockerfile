# --- build ---
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN mvn -B -q -DskipTests package && cp target/fleet-backend-*.jar /src/app.jar

# --- run ---
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --create-home fleet && mkdir -p /data/files && chown -R fleet /data
USER fleet
WORKDIR /app
COPY --from=build /src/app.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Kolkata" \
    STORAGE_LOCAL_DIR=/data/files \
    PORT=8080
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
