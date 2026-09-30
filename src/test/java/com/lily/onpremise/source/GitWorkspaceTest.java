package com.lily.onpremise.source;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitWorkspaceTest {

    @TempDir
    Path root;

    @Test
    void 토큰은_클론_인자에만_있고_실패_로그에는_없다() {
        List<String> seen = new ArrayList<>();
        Commands commands = new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
                seen.addAll(command);
                throw new IllegalStateException(String.join(" ", command));
            }

            @Override
            public void start(List<String> command, java.util.function.Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
        DeployJob job = new DeployJob(
                "job1", "https://github.com/acme/blog.git", "main", "ghp_secret", "blog", 8080,
                null, null, null, Map.of()).normalize();

        assertThatThrownBy(() -> new GitWorkspace(commands, root).checkout(job))
                .hasMessageNotContaining("ghp_secret");
        assertThat(seen).anyMatch(arg -> arg.contains("x-access-token:ghp_secret@github.com"));
    }
}
