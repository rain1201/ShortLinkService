FROM maven:3.9-eclipse-temurin-21 AS builder
RUN mkdir /app
WORKDIR /app
COPY pom.xml /app
RUN mvn dependency:go-offline -B
COPY . /app
RUN mvn build -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN mkdir /app
WORKDIR /app
COPY --from=builder /app/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]