package com.lily.onpremise.expose;

import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudflareLauncherTest {

    @Test
    void 터널을_끄면_프록시_주소를_공개_주소로_쓴다() {
        RecordingCommands commands = new RecordingCommands();

        String url = new CloudflareLauncher(commands, false, "", "").launch(8099);

        assertThat(url).isEqualTo("http://127.0.0.1:8099");
        assertThat(commands.started).isEmpty();
    }

    @Test
    void quick_tunnel_주소를_출력에서_읽는다() {
        RecordingCommands commands = new RecordingCommands();
        commands.lines.add("requesting new quick Tunnel");
        commands.lines.add("https://random-name.trycloudflare.com");

        String url = new CloudflareLauncher(commands, true, "", "").launch(8099);

        assertThat(url).isEqualTo("https://random-name.trycloudflare.com");
        assertThat(commands.started.get(0)).contains("--url", "http://127.0.0.1:8099");
    }

    @Test
    void named_tunnel_은_고정_주소가_있어야_프로세스를_띄운다() {
        RecordingCommands commands = new RecordingCommands();

        assertThatThrownBy(() -> new CloudflareLauncher(commands, true, "tokenvalue", "").launch(8099))
                .hasMessageContaining("PUBLIC_URL");
        assertThat(commands.started).isEmpty();

        String url = new CloudflareLauncher(commands, true, "tokenvalue", "https://blog.example.com").launch(8099);

        assertThat(url).isEqualTo("https://blog.example.com");
        assertThat(commands.started.get(0)).contains("tokenvalue");
    }

    static final class RecordingCommands implements Commands {
        final List<List<String>> started = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override
        public void run(List<String> command, Path workDir) {
        }

        @Override
        public void start(List<String> command, Consumer<String> consumer) {
            started.add(command);
            lines.forEach(consumer);
        }

        @Override
        public void close() {
        }
    }
}
