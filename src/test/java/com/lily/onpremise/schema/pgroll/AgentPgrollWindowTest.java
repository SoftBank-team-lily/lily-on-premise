package com.lily.onpremise.schema.pgroll;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 전환 뒤 열리는 롤백 창. 창이 닫힐 때 complete, 롤백할 때 rollback 을 이 창의 접속 정보로 한다 */
class AgentPgrollWindowTest {

    @TempDir
    Path dir;

    @Test
    void 롤백_창은_에이전트_접속_정보의_sslmode까지_저장해_재시작_뒤에도_같은_sslmode로_붙는다() {
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        AgentPgroll pgroll = new AgentPgroll(new PgrollMigrator(new PgrollCli(PgrollCli.Settings.defaults())),
                null, new PgrollWindows(dir, json), Duration.ofSeconds(600));

        pgroll.opened("blog", "02_add_slug", Map.of(
                "DB_URL", "jdbc:postgresql://172.17.0.1:25432/blog", "DB_USERNAME", "blog", "DB_PASSWORD", "pw",
                DbTarget.SSLMODE, "disable", "SPRING_DATASOURCE_URL", "jdbc:postgresql://172.17.0.1:25432/blog"));

        PgrollWindows.Window window = new PgrollWindows(dir, json).find("blog").orElseThrow();
        assertThat(window.migration()).isEqualTo("02_add_slug");
        assertThat(DbTarget.from(window.env()).sslmode()).isEqualTo("disable");
        assertThat(window.env()).doesNotContainKey("SPRING_DATASOURCE_URL");
    }
}
