package com.lily.onpremise.database;

import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 프로젝트를 지울 때 이 PC 의 DB 컨테이너에 있는 앱 DB 를 지운다 */
class LocalDatabaseDropTest {

    @TempDir
    Path dir;
    private final List<List<String>> runs = new ArrayList<>();
    /** 백업 DB 목록 질의의 결과 */
    private String backups = "";

    @Test
    void RDS에서_가져올_때_남긴_백업_DB도_계정보다_먼저_지운다() {
        backups = "blog_app_bak_20261003053435\n";

        List<String> dropped = database(Set.of("lily-postgres")).drop("blog-app");

        assertThat(dropped).containsExactly("postgres blog_app_bak_20261003053435", "postgres blog_app");
        assertThat(sql()).containsSubsequence(
                "DROP DATABASE IF EXISTS \"blog_app\" WITH (FORCE)",
                "DROP DATABASE IF EXISTS \"blog_app_bak_20261003053435\" WITH (FORCE)",
                "DROP ROLE IF EXISTS \"blog_app\"");
    }

    @Test
    void postgres_컨테이너가_있으면_앱_DB를_FORCE로_지우고_계정과_비밀번호_파일도_지운다() throws Exception {
        Files.createDirectories(dir.resolve("local-db"));
        Files.writeString(dir.resolve("local-db/postgres-blog_app"), "0".repeat(32));

        List<String> dropped = database(Set.of("lily-postgres")).drop("blog-app");

        assertThat(dropped).containsExactly("postgres blog_app");
        assertThat(sql()).containsSubsequence(
                "DROP DATABASE IF EXISTS \"blog_app\" WITH (FORCE)",
                "DROP ROLE IF EXISTS \"blog_app\"");
        assertThat(dir.resolve("local-db/postgres-blog_app")).doesNotExist();
    }

    @Test
    void DB_컨테이너가_하나도_없으면_새로_띄우지_않고_아무것도_지우지_않는다() {
        List<String> dropped = database(Set.of()).drop("blog-app");

        assertThat(dropped).isEmpty();
        assertThat(runs).noneMatch(command -> command.contains("run"));
        assertThat(sql()).isEmpty();
    }

    @Test
    void mysql_컨테이너만_있으면_mysql_DB와_계정만_지운다() {
        List<String> dropped = database(Set.of("lily-mysql")).drop("blog-app");

        assertThat(dropped).containsExactly("mysql blog_app");
        assertThat(runs).anyMatch(command -> command.stream()
                .anyMatch(arg -> arg.contains("DROP DATABASE IF EXISTS `blog_app`; DROP USER IF EXISTS 'blog_app'@'%';")));
    }

    /** @param containers docker start 가 성공하는 DB 컨테이너 */
    private LocalDatabase database(Set<String> containers) {
        Commands commands = new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
                runs.add(command);
                if (command.size() == 3 && command.get(1).equals("start") && !containers.contains(command.get(2))) {
                    throw new IllegalStateException("docker start " + command.get(2) + " → Error: No such container");
                }
            }

            @Override
            public String output(List<String> command) {
                return backups;
            }

            @Override
            public void start(List<String> command, Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
        return new LocalDatabase(commands, dir, "172.17.0.1");
    }

    /** psql -c 로 보낸 SQL */
    private List<String> sql() {
        return runs.stream()
                .filter(command -> command.contains("psql"))
                .map(command -> command.get(command.size() - 1))
                .toList();
    }
}
