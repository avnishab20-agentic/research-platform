FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common ./common
COPY agent-service ./agent-service
RUN mvn -pl agent-service -am -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/agent-service/target/*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
