package com.lily.onpremise.analyze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StackAnalyzerTest {

    @TempDir
    Path root;

    private final StackAnalyzer analyzer = new StackAnalyzer();

    @Test
    void compose_만_있으면_슬롯으로_배포하지_않는다() throws Exception {
        Files.writeString(root.resolve("docker-compose.yml"), "services: {}\n");

        assertThatThrownBy(() -> analyzer.plan(root, null))
                .hasMessageContaining("Dockerfile");
    }

    @Test
    void 빌드_파일이_없으면_실패한다() {
        assertThatThrownBy(() -> analyzer.plan(root, null))
                .hasMessageContaining("스택");
    }

    @Test
    void pom_이면_maven_이미지를_만든다() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        DockerfilePlan plan = analyzer.plan(root, null);

        assertThat(plan.origin()).isEqualTo(DockerfilePlan.Origin.GENERATED);
        assertThat(plan.stack()).isEqualTo(DockerfilePlan.Stack.MAVEN);
        assertThat(plan.content()).contains("mvnw");
        assertThat(analyzer.healthPath(plan, null)).isEqualTo("/actuator/health/readiness");
    }
}
