FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common ./common
COPY retrieval-service ./retrieval-service
RUN mvn -pl retrieval-service -am -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/retrieval-service/target/*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
