# Backend image (build context = ./backend), adapted from pragma/03-reto-reactivo.
# Stage "jre" builds a trimmed Java 26 runtime with jlink; it does not depend on the source code,
# so it stays cached across code changes. Stage "build" compiles the boot jar; the final stage is
# plain Alpine with only that runtime and the jar.

FROM eclipse-temurin:26-jdk-alpine AS jre

# Modules needed by Spring Boot WebFlux + R2DBC + Spring AI (HTTPS client to the Anthropic API).
RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.crypto.cryptoki,jdk.jfr,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs \
      --strip-debug --no-man-pages --no-header-files --compress=zip-9 \
      --output /jre

FROM eclipse-temurin:26-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle lombok.config ./
COPY gradle gradle
COPY src src

RUN --mount=type=cache,id=gradle-home,target=/root/.gradle,sharing=locked \
    sed -i 's/\r$//' gradlew && chmod +x gradlew \
    && ./gradlew --no-daemon -q bootJar -x test \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} app.jar \;

FROM alpine:3.22
ENV JAVA_HOME=/opt/jre \
    PATH="/opt/jre/bin:${PATH}"
RUN addgroup -S app && adduser -S app -G app
COPY --from=jre /jre /opt/jre
COPY --from=build --chown=app:app /workspace/app.jar /app/app.jar
USER app
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
