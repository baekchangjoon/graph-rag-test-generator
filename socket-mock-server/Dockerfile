FROM eclipse-temurin:17-jre
COPY build/libs/*.jar /app/app.jar
EXPOSE 9099 9000
ENV SERVER_PORT=9099
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
