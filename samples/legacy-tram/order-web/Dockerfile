FROM gradle:7.6-jdk8 AS build
WORKDIR /src
COPY . .
RUN gradle bootJar --no-daemon
FROM eclipse-temurin:8-jre
COPY --from=build /src/build/libs/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
