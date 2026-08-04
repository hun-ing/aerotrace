FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle ./gradle

RUN chmod +x ./gradlew

COPY src ./src

RUN ./gradlew clean bootJar --no-daemon \
    && JAR_FILE="$(find build/libs \
        -maxdepth 1 \
        -type f \
        -name '*.jar' \
        ! -name '*-plain.jar' \
        | head -n 1)" \
    && test -n "${JAR_FILE}" \
    && cp "${JAR_FILE}" /workspace/aerotrace-backend.jar

FROM eclipse-temurin:21-jre-jammy AS runtime

WORKDIR /app

COPY --from=builder \
    /workspace/aerotrace-backend.jar \
    /app/aerotrace-backend.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/aerotrace-backend.jar"]