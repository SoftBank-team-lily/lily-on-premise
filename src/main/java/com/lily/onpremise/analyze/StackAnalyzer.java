package com.lily.onpremise.analyze;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 컨트롤 플레인이 Dockerfile 을 실어 보냈으면 그것을 쓴다.
 * 비어 있고 레포에도 없으면, 빌드 파일을 보고 앱 컨테이너 한 장을 만든다.
 * compose 로 스택 전체를 띄우지 않는다. 슬롯 전환의 단위가 컨테이너 하나여야 하기 때문이다.
 */
@Component
public class StackAnalyzer {

    public DockerfilePlan plan(Path context, String provided) {
        if (!Files.isDirectory(context)) {
            throw new IllegalStateException("checkout 이 없습니다");
        }
        if (provided != null && !provided.isBlank()) {
            return new DockerfilePlan(provided, DockerfilePlan.Origin.PROVIDED, DockerfilePlan.Stack.UNKNOWN);
        }
        Path dockerfile = context.resolve("Dockerfile");
        if (Files.isRegularFile(dockerfile)) {
            try {
                return new DockerfilePlan(
                        Files.readString(dockerfile), DockerfilePlan.Origin.EXISTING, DockerfilePlan.Stack.DOCKERFILE);
            } catch (IOException e) {
                throw new IllegalStateException("Dockerfile 을 읽지 못했습니다", e);
            }
        }
        if (exists(context, "build.gradle") || exists(context, "build.gradle.kts")) {
            return new DockerfilePlan(GRADLE, DockerfilePlan.Origin.GENERATED, DockerfilePlan.Stack.GRADLE);
        }
        if (exists(context, "pom.xml")) {
            return new DockerfilePlan(MAVEN, DockerfilePlan.Origin.GENERATED, DockerfilePlan.Stack.MAVEN);
        }
        if (exists(context, "package.json")) {
            return new DockerfilePlan(NODE, DockerfilePlan.Origin.GENERATED, DockerfilePlan.Stack.NODE);
        }
        if (exists(context, "requirements.txt")) {
            return new DockerfilePlan(PYTHON, DockerfilePlan.Origin.GENERATED, DockerfilePlan.Stack.PYTHON);
        }
        if (exists(context, "docker-compose.yml") || exists(context, "compose.yaml") || exists(context, "compose.yml")) {
            throw new IllegalStateException(
                    "compose 만 있는 레포는 한 슬롯으로 트래픽을 전환할 수 없습니다. 앱 Dockerfile 이 필요합니다");
        }
        throw new IllegalStateException("Dockerfile 을 만들 스택을 찾지 못했습니다");
    }

    /**
     * 잡이 경로를 주면 그 경로를 쓴다.
     * 에이전트가 Spring 으로 만들었거나, 레포에 Dockerfile 이 있으면 플랫폼 계약인 readiness 를 본다.
     * Node 와 Python 은 생성한 이미지 기준으로 {@code /} 를 본다.
     */
    public String healthPath(DockerfilePlan plan, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        return switch (plan.stack()) {
            case NODE, PYTHON -> "/";
            default -> "/actuator/health/readiness";
        };
    }

    private static boolean exists(Path context, String name) {
        return Files.isRegularFile(context.resolve(name));
    }

    /** 의존성을 받는 RUN 만 네트워크를 연다. 빌드는 {@code --network=none} 이다 */
    private static final String GRADLE = """
            FROM eclipse-temurin:21-jdk AS build
            WORKDIR /src
            COPY . .
            RUN --network=default if [ -f gradlew ]; then chmod +x gradlew && ./gradlew bootJar -x test --no-daemon; \\
                else gradle bootJar -x test --no-daemon; fi \\
                && find build/libs -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} /tmp/app.jar \\;
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            COPY --from=build /tmp/app.jar /app/app.jar
            EXPOSE 8080
            ENTRYPOINT ["java", "-jar", "/app/app.jar"]
            """;

    private static final String MAVEN = """
            FROM eclipse-temurin:21-jdk AS build
            WORKDIR /src
            COPY . .
            RUN --network=default if [ -f mvnw ]; then chmod +x mvnw && ./mvnw -DskipTests package; \\
                else mvn -DskipTests package; fi \\
                && find target -maxdepth 1 -type f -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -exec cp {} /tmp/app.jar \\;
            FROM eclipse-temurin:21-jre
            WORKDIR /app
            COPY --from=build /tmp/app.jar /app/app.jar
            EXPOSE 8080
            ENTRYPOINT ["java", "-jar", "/app/app.jar"]
            """;

    private static final String NODE = """
            FROM node:22-alpine
            WORKDIR /app
            COPY . .
            RUN --network=default npm install --omit=dev
            EXPOSE 3000
            CMD ["npm", "start"]
            """;

    private static final String PYTHON = """
            FROM python:3.12-slim
            WORKDIR /app
            COPY . .
            RUN --network=default pip install --no-cache-dir -r requirements.txt
            EXPOSE 8080
            CMD ["sh", "-c", "if [ -f app.py ]; then python app.py; else python main.py; fi"]
            """;
}
