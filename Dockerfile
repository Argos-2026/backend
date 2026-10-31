FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY . .

RUN chmod +x ./gradlew
RUN ./gradlew clean bootJar -x test --no-daemon
RUN cp "$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)" app.jar

# 2. 애플리케이션 실행
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
