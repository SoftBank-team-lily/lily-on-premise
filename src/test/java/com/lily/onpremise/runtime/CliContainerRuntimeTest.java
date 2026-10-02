package com.lily.onpremise.runtime;

import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliContainerRuntimeTest {

    private final RecordingCommands commands = new RecordingCommands();
    private final CliContainerRuntime runtime = new CliContainerRuntime(commands, new AgentProperties.Sandbox("2g", "1", 1024));

    @Test
    void 저장소_빌드는_한도만_있고_호스트를_넘기지_않는다() {
        runtime.build(Path.of("C:/work/app"), "lily-onprem/blog:job1", false);

        List<String> command = commands.ran.get(0);
        assertThat(command).containsSequence("--resource", "memory=2g", "--resource", "cpu-quota=100000");
        assertThat(command).containsSequence("--ulimit", "nproc=1024:1024");
        assertThat(command).containsSequence("-t", "lily-onprem/blog:job1");
        assertThat(command).doesNotContain("--network", "none", "-v", "--mount", "--privileged", "docker.sock");
    }

    @Test
    void 생성_Dockerfile_빌드는_기본_네트워크를_끈다() {
        runtime.build(Path.of("C:/work/app"), "lily-onprem/blog:job1", true);

        assertThat(commands.ran.get(0)).containsSequence("--network", "none");
    }

    @Test
    void resource_플래그를_거절하면_그_플래그_없이_다시_빌드한다() {
        commands.rejectResource = true;

        runtime.build(Path.of("C:/work/app"), "lily-onprem/blog:job1", true);

        assertThat(commands.ran).hasSize(2);
        assertThat(commands.ran.get(0)).contains("--resource");
        assertThat(commands.ran.get(1)).doesNotContain("--resource");
        assertThat(commands.ran.get(1)).containsSequence("--ulimit", "nproc=1024:1024", "--network", "none");
    }

    @Test
    void 실행은_루프백과_한도와_권한_제거를_같이_넣는다() {
        runtime.start("blog-blue", "lily-onprem/blog:job1", 18080, 8080, Map.of("DB_PASSWORD", "secret"));

        List<String> command = commands.ran.get(1);
        assertThat(command).containsSequence("-p", "127.0.0.1:18080:8080");
        assertThat(command).containsSequence("--memory", "2g", "--memory-swap", "2g", "--cpus", "1", "--pids-limit", "1024");
        assertThat(command).containsSequence("--cap-drop", "ALL", "--security-opt", "no-new-privileges:true");
        assertThat(command).contains("-e", "DB_PASSWORD=secret", "lily-onprem/blog:job1");
        assertThat(command).doesNotContain("--cap-add", "-v", "--mount", "docker.sock", "--privileged");
    }

    @Test
    void 낮은_포트만_바인드_권한을_넣는다() {
        runtime.start("blog-blue", "image", 18080, 80, Map.of());

        assertThat(commands.ran.get(1)).containsSequence("--cap-add", "NET_BIND_SERVICE");
    }

    @Test
    void 사용자_PC_의_DB_주소는_호스트_게이트웨이로_푼다() {
        runtime.start("blog-blue", "image", 18080, 8080, Map.of("DATABASE_URL", "jdbc:postgresql://host.docker.internal:5432/app"));

        assertThat(commands.ran.get(1)).contains("--add-host=host.docker.internal:host-gateway");
    }

    @Test
    void 한도_값이_벗어나면_거절한다() {
        assertThatThrownBy(() -> new AgentProperties.Sandbox("0g", "1", 1024))
                .hasMessageContaining("메모리");
        assertThatThrownBy(() -> new AgentProperties.Sandbox("2g", "0", 1024))
                .hasMessageContaining("CPU");
        assertThatThrownBy(() -> new AgentProperties.Sandbox("2g", "1", 8))
                .hasMessageContaining("프로세스");
        assertThat(new AgentProperties.Sandbox("512m", "1.5", 256).cpuQuota()).isEqualTo(150_000L);
    }

    private static final class RecordingCommands implements Commands {
        final List<List<String>> ran = new ArrayList<>();
        boolean rejectResource;

        @Override
        public void run(List<String> command, Path workDir) {
            ran.add(new ArrayList<>(command));
            if (rejectResource && command.contains("--resource")) {
                throw new IllegalStateException(String.join(" ", command) + " → 125 unknown flag: --resource");
            }
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
        }

        @Override
        public void close() {
        }
    }
}
