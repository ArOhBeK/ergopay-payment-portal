# Build stage
FROM gradle:7.6.2-jdk11 AS build
WORKDIR /workspace
COPY build.gradle.kts settings.gradle.kts gradlew gradlew.bat /workspace/
COPY gradle /workspace/gradle
COPY src /workspace/src
RUN ./gradlew --no-daemon clean bootJar

# Runtime stage
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
