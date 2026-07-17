FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/wellness-game-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
