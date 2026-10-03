package com.lily.onpremise.schema.pgroll;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 앱마다 열려 있는 pgroll 롤백 창. 에이전트가 다시 떠도 complete 와 롤백을 이어 가도록 작업 폴더에 남긴다
 * ({@code {workDir}/pgroll/{app}.json}). 에이전트가 DB 에 붙을 접속 정보가 들어 있어서 소유자만 읽는다.
 */
public final class PgrollWindows {

    private final Path dir;
    private final ObjectMapper json;

    public PgrollWindows(Path workDir, ObjectMapper json) {
        this.dir = workDir.resolve("pgroll");
        this.json = json;
    }

    public synchronized void put(Window window) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(window.app() + ".json");
            Files.writeString(file, json.writeValueAsString(window));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows 개발 환경
            }
        } catch (IOException e) {
            throw new IllegalStateException("pgroll 롤백 창을 남기지 못했다: " + e.getMessage(), e);
        }
    }

    public synchronized Optional<Window> find(String app) {
        Path file = dir.resolve(app + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(Files.readString(file), Window.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public synchronized void remove(String app) {
        try {
            Files.deleteIfExists(dir.resolve(app + ".json"));
        } catch (IOException ignored) {
            // 다음 배포가 complete 하면서 다시 지운다
        }
    }

    public synchronized List<Window> all() {
        List<Window> windows = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return windows;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                String name = f.getFileName().toString();
                find(name.substring(0, name.length() - ".json".length())).ifPresent(windows::add);
            });
        } catch (IOException ignored) {
            // 다음 회차에 다시 본다
        }
        return windows;
    }

    /**
     * @param env           에이전트가 DB 에 붙을 접속 정보 (DB_URL, DB_USERNAME, DB_PASSWORD)
     * @param completeAfter 이 시각이 지나면 complete
     */
    public record Window(String app, String migration, String slot, Instant completeAfter, Map<String, String> env) {
    }
}
