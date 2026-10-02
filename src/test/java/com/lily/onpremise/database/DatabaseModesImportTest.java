package com.lily.onpremise.database;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/** 클라우드 앱을 이 PC 로 옮길 때: PC DB 를 만들고 RDS 데이터를 옮긴 뒤 PC DB 접속 정보로 띄운다 */
class DatabaseModesImportTest {

    private static final Map<String, String> RDS_ENV = Map.of(
            "DATABASE_URL", "postgresql://blog:rds-secret@172.17.0.1:15432/blog",
            "DATABASE_USERNAME", "blog",
            "DATABASE_PASSWORD", "rds-secret",
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://172.17.0.1:15432/blog",
            "SPRING_DATASOURCE_USERNAME", "blog",
            "SPRING_DATASOURCE_PASSWORD", "rds-secret");

    @TempDir
    Path dir;
    private final List<List<String>> runs = new ArrayList<>();
    private final List<String> order = new ArrayList<>();
    private final Commands commands = new Commands() {
        @Override
        public void run(List<String> command, Path workDir) {
            runs.add(command);
            String script = command.get(command.size() - 1);
            if (script.contains("pg_dump")) {
                order.add("transfer");
            } else if (script.contains("CREATE DATABASE")) {
                order.add("local");
            }
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
        }

        @Override
        public void close() {
        }
    };
    private final DatabaseAccess cloud = new DatabaseAccess() {
        @Override
        public Map<String, String> prepare(DeployJob job) {
            order.add("tunnel");
            return job.databaseEnv();
        }

        @Override
        public boolean ready() {
            return true;
        }
    };

    @Test
    void PC_DB_를_만들고_RDS_를_옮긴_뒤_PC_DB_로_띄운다() {
        DatabaseModes modes = modes(true);

        Map<String, String> env = modes.prepare(job(true));

        assertThat(order).containsExactly("local", "tunnel", "transfer");
        assertThat(env.get("SPRING_DATASOURCE_URL")).contains(":" + LocalDatabase.POSTGRES_PORT + "/blog");
        List<String> transfer = runs.get(runs.size() - 1);
        assertThat(transfer).contains("REMOTE_HOST=172.17.0.1", "REMOTE_PORT=15432", "LOCAL_DB=blog");
    }

    @Test
    void 옮기지_않는_local_잡은_RDS_에_닿지_않는다() {
        DatabaseModes modes = modes(true);

        modes.prepare(job(false));

        assertThat(order).containsExactly("local");
    }

    @Test
    void 이전_기능이_없으면_거절한다() {
        DatabaseModes modes = modes(false);

        assertThatThrownBy(() -> modes.prepare(job(true))).hasMessageContaining("RDS 데이터를 옮길 수 없습니다");
    }

    @Test
    void 잡은_local_postgres_와_RDS_접속_정보가_있어야_옮긴다() {
        assertThat(job(true).normalize().importsDatabase()).isTrue();
        assertThat(job(false).normalize().importDatabase()).isNull();
        assertThatThrownBy(() -> new DeployJob("j1", "https://github.com/a/blog", "main", null, "blog", 8080,
                "/health", null, null, Map.of(), "mysql", null, null, RDS_ENV, "local", null, true).normalize())
                .hasMessageContaining("importDatabase");
        assertThatThrownBy(() -> new DeployJob("j1", "https://github.com/a/blog", "main", null, "blog", 8080,
                "/health", null, null, Map.of(), "postgres", null, null, null, "local", null, true).normalize())
                .hasMessageContaining("databaseEnv");
    }

    private DatabaseModes modes(boolean transfer) {
        DatabaseModes modes = new DatabaseModes(cloud, new LocalDatabase(commands, dir, "172.17.0.1"),
                new ExternalDatabase(), new ReverseTunnel());
        return transfer
                ? modes.transfer(new DatabaseTransfer(commands, "lily-postgres", LocalDatabase.POSTGRES_PORT,
                        Clock.fixed(Instant.parse("2026-10-02T03:04:05Z"), ZoneOffset.UTC)))
                : modes;
    }

    private static DeployJob job(boolean importing) {
        return new DeployJob("j1", "https://github.com/a/blog", "main", null, "blog", 8080, "/health", null, null,
                Map.of(), "postgres", null, null, RDS_ENV, "local", null, importing ? Boolean.TRUE : null);
    }
}
