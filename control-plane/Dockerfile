FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY common ./common
COPY control-plane ./control-plane
RUN mvn -pl control-plane -am -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/control-plane/target/*.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
