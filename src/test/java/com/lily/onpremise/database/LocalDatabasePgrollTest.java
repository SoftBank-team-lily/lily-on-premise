package com.lily.onpremise.database;

import com.lily.onpremise.schema.pgroll.DbTarget;
import com.lily.onpremise.schema.pgroll.PgrollCli;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 이 PC 의 postgres 앱 DB 에 pgroll 을 켤 때 */
class LocalDatabasePgrollTest {

    @TempDir
    Path dir;
    private final List<String> events = new ArrayList<>();

    @Test
    void 컨테이너가_남아_있어도_init_전에_admin_비밀번호를_소켓으로_맞추고_그_비밀번호로_init한_뒤_앱_계정에_권한을_준다() throws Exception {
        PgrollCli cli = new PgrollCli(PgrollCli.Settings.defaults()) {
            @Override
            public String init(DbTarget target) {
                events.add("init " + target.username() + " " + target.password() + " " + target.sslmode()
                        + " " + target.url());
                return "";
            }
        };

        new LocalDatabase(commands(), dir, "172.17.0.1").enablePgroll("blog-app", cli);

        String admin = Files.readString(dir.resolve("local-db/postgres-admin")).trim();
        assertThat(events).containsSubsequence(
                "sql ALTER ROLE postgres WITH PASSWORD '" + admin + "'",
                "init postgres " + admin + " disable jdbc:postgresql://172.17.0.1:25432/blog_app",
                "sql GRANT USAGE, CREATE ON SCHEMA pgroll TO \"blog_app\"");
    }

    private Commands commands() {
        return new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
                if (command.contains("psql")) {
                    events.add("sql " + command.get(command.indexOf("-c") + 1));
                }
            }

            @Override
            public void start(List<String> command, Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
    }
}
