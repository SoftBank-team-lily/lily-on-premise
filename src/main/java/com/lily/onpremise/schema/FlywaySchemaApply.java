package com.lily.onpremise.schema;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 잡에 실린 V/U/R 스크립트를 Flyway 로 적용한다.
 * 접속 정보는 프로비저너가 넣는 DB_URL, DB_USERNAME, DB_PASSWORD (또는 SPRING_DATASOURCE_*) 다.
 */
public final class FlywaySchemaApply implements SchemaApply {

    @Override
    public void apply(Map<String, String> env, Map<String, String> scripts) {
        if (scripts == null || scripts.isEmpty()) {
            return;
        }
        String url = first(env, "DB_URL", "SPRING_DATASOURCE_URL");
        String username = first(env, "DB_USERNAME", "SPRING_DATASOURCE_USERNAME");
        String password = first(env, "DB_PASSWORD", "SPRING_DATASOURCE_PASSWORD");
        if (url == null || username == null || password == null) {
            throw new IllegalStateException("schema failed, traffic unchanged: DB 접속 정보가 없다");
        }
        if (!url.startsWith("jdbc:postgresql:") && !url.startsWith("jdbc:mysql:")) {
            throw new IllegalStateException("schema failed, traffic unchanged: postgres 와 mysql 만 지원한다");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("lily-schema");
            for (Map.Entry<String, String> script : scripts.entrySet()) {
                Path file = dir.resolve(script.getKey()).normalize();
                if (!file.startsWith(dir)) {
                    throw new IllegalStateException("schema failed, traffic unchanged: 스크립트 이름이 올바르지 않다");
                }
                Files.writeString(file, script.getValue() == null ? "" : script.getValue());
            }
            String location = "filesystem:" + dir.toAbsolutePath().toString().replace('\\', '/');
            Flyway.configure()
                    .dataSource(url, username, password)
                    .locations(location)
                    .load()
                    .migrate();
        } catch (FlywayException e) {
            throw new IllegalStateException("schema failed, traffic unchanged: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("schema failed, traffic unchanged: 스크립트를 쓰지 못했다", e);
        } finally {
            delete(dir);
        }
    }

    private static String first(Map<String, String> env, String... keys) {
        if (env == null) {
            return null;
        }
        for (String key : keys) {
            String value = env.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static void delete(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // 임시 스크립트가 남아도 다음 배포는 새 디렉터리를 쓴다
        }
    }
}
