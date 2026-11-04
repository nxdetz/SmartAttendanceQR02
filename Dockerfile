# Build Java application
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# Small runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/smart-attendance-web-1.0.0.jar /app/app.jar
RUN mkdir -p /app/data
ENV PORT=8080
ENV DB_PATH=/app/data/attendance.db
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT} -Dspring.datasource.url=jdbc:sqlite:${DB_PATH} -jar /app/app.jar"]
