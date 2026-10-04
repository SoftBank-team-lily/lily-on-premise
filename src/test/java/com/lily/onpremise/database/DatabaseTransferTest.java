package com.lily.onpremise.database;

import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTransferTest {

    private static final DatabaseCredentials RDS =
            new DatabaseCredentials("postgres", "172.17.0.1", 15432, "blog", "blog", "s3cret", "");

    private final List<List<String>> runs = new ArrayList<>();
    private String testName = "";
    private RuntimeException failure;
    private final Commands commands = new Commands() {
        @Override
        public void run(List<String> command, Path workDir) {
            runs.add(command);
            replay(command);
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
        }

        @Override
        public void close() {
        }
    };
    private final DatabaseTransfer transfer = new DatabaseTransfer(commands, "lily-postgres", 25432,
            Clock.fixed(Instant.parse("2026-10-02T03:04:05Z"), ZoneOffset.UTC));

    @BeforeEach
    void name(TestInfo info) {
        testName = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("test");
    }

    @Test
    void 비밀번호는_환경변수로만_넘기고_컨테이너_안에서_덤프한다() {
        transfer.toRemote("blog", RDS);

        List<String> command = runs.get(0);
        assertThat(command.subList(0, 2)).containsExactly("docker", "exec");
        assertThat(command).contains("REMOTE_PASSWORD=s3cret", "LOCAL_DB=blog", "REMOTE_HOST=172.17.0.1",
                "REMOTE_PORT=15432", "lily-postgres");
        String script = command.get(command.size() - 1);
        assertThat(script).doesNotContain("s3cret");
        assertThat(script).contains("pg_dump", "--no-owner", "lily-verify");
    }

    @Test
    void RDS_를_비우고_채우는_일은_한_트랜잭션이고_덤프가_끝까지_성공해야_커밋한다() {
        transfer.toRemote("blog", RDS, true);

        List<String> command = runs.get(0);
        String script = command.get(command.size() - 1);
        int begin = script.indexOf("echo 'BEGIN;'");
        int reset = script.indexOf("reset_public\n", begin);
        int dump = script.indexOf("pg_dump -U postgres", reset);
        int history = script.indexOf("-t pgroll.migrations", dump);
        int commit = script.indexOf("echo 'COMMIT;'", history);
        int send = script.indexOf("} | remote", commit);
        assertThat(begin).isNotNegative();
        assertThat(List.of(reset, dump, history, commit, send)).allMatch(i -> i > begin);
        assertThat(reset).isLessThan(dump);
        assertThat(dump).isLessThan(history);
        assertThat(history).isLessThan(commit);
        assertThat(commit).isLessThan(send);
        // 비우기를 따로 보내면 그 순간 커밋된다
        assertThat(script).doesNotContain("remote -c \"$(reset_public)\"");
        // 덤프가 실패하면 COMMIT 을 보내지 않고 끝낸다 (연결이 닫히면 서버가 되돌린다)
        assertThat(script.substring(dump, commit)).contains("|| exit 1");
    }

    @Test
    void PC_로_옮길_때는_백업_이름을_돌려준다() {
        String backup = transfer.toLocal("my-blog", RDS);

        assertThat(backup).isEqualTo("my_blog_bak_20261002030405");
        assertThat(runs.get(0)).contains("BACKUP_DB=my_blog_bak_20261002030405", "LOCAL_DB=my_blog");
    }

    @Test
    void 실패하면_명령줄_대신_DB_오류만_남긴다() {
        failure = new IllegalStateException("docker exec -e REMOTE_PASSWORD=s3cret lily-postgres sh -c ... → 3 "
                + "lily-verify: public.post 행 수가 다르다 (3 / 2)");

        assertThatThrownBy(() -> transfer.toRemote("blog", RDS))
                .hasMessageContaining("행 수가 다르다")
                .hasMessageNotContaining("s3cret");
    }

    @Test
    void postgres_가_아니면_거절한다() {
        DatabaseCredentials mysql = new DatabaseCredentials("mysql", "h", 3306, "blog", "blog", "p", "");

        assertThatThrownBy(() -> transfer.toRemote("blog", mysql)).hasMessageContaining("postgres");
        assertThat(runs).isEmpty();
    }

    @Test
    void 백업_이름이_그_앱의_것이_아니면_되돌리지_않는다() {
        assertThatThrownBy(() -> transfer.restoreLocal("blog", "other_bak_20261002030405"))
                .isInstanceOf(IllegalArgumentException.class);
        transfer.restoreLocal("blog", "blog_bak_20261002030405");
        assertThat(runs).hasSize(1);
    }

    @Test
    void provisioner_환경변수에서_RDS_접속_정보를_읽는다() {
        DatabaseCredentials db = DatabaseTransfer.fromEnv(Map.of(
                "DATABASE_URL", "postgresql://blog:p%40ss@172.17.0.1:15432/blog"));

        assertThat(db.host()).isEqualTo("172.17.0.1");
        assertThat(db.port()).isEqualTo(15432);
        assertThat(db.password()).isEqualTo("p@ss");
    }

    /** LILY_REPLAY_DIR 이 있으면 명령을 파일로 남긴다. 실제 postgres 컨테이너로 다시 돌려 보는 데 쓴다 */
    private void replay(List<String> command) {
        String dir = System.getenv("LILY_REPLAY_DIR");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            String name = testName + "-" + runs.size() + ".args";
            Files.writeString(Path.of(dir, name), String.join("\0", command), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
