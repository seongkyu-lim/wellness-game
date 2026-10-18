FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN ./gradlew dependencies --no-daemon || true
COPY src src
# jar 이름(프로젝트 이름·버전)을 하드코딩하지 않는다. bootJar 산출물(-plain 제외)이 정확히 하나인지 확인하고 app.jar 로 복사한다.
RUN ./gradlew bootJar --no-daemon \
 && jars="$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar')" \
 && [ "$(printf '%s\n' "$jars" | grep -c .)" -eq 1 ] \
 && cp "$jars" /workspace/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/app.jar app.jar
USER app
# JVM 옵션은 JAVA_TOOL_OPTIONS 로 준다(JVM 이 직접 읽으므로 ENTRYPOINT 를 바꿀 필요 없음).
# 기본값: 힙을 컨테이너 메모리 한도의 75% 로, OOM 이면 즉시 종료해 플랫폼이 재시작하게 한다.
# 플랫폼 환경변수로 JAVA_TOOL_OPTIONS 를 설정하면 이 기본값 전체를 대체하므로 필요한 옵션을 함께 적는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# 로컬·docker-compose 기본 포트. Render 는 PORT 환경변수를 주고 server.port=${PORT:8080} 이 따른다.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
