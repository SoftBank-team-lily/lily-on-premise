package com.lily.onpremise.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 앱마다 백업 상태를 작업 폴더에 남긴다 ({@code {workDir}/backup/{app}.json}).
 * 요청 통계는 PC 밖으로 보내지 않는다. 쓰는 도중에 끊겨도 이전 파일이 남도록 임시 파일에 쓰고 이름을 바꾼다.
 */
public final class BackupStore {

    private static final Logger log = LoggerFactory.getLogger(BackupStore.class);
    private static final String APP = "[a-z0-9]([-a-z0-9]*[a-z0-9])?";

    private final Path dir;
    private final ObjectMapper json;

    public BackupStore(Path workDir, ObjectMapper json) {
        this.dir = workDir.resolve("backup");
        this.json = json;
    }

    /** 파일이 없거나 읽지 못하면 빈 상태다. 깨진 파일은 다음 저장이 덮어쓴다 */
    public synchronized BackupState load(String app) {
        Path file = file(app);
        if (!Files.isRegularFile(file)) {
            return BackupState.empty();
        }
        try {
            BackupState state = json.readValue(Files.readString(file), BackupState.class);
            if (state.requests() == null || state.minutes() == null
                    || state.requests().length != RequestProfile.SLOTS || state.minutes().length != RequestProfile.SLOTS) {
                throw new IOException("168칸이 아니다");
            }
            return state;
        } catch (IOException e) {
            log.warn("backup state for {} unreadable, starting empty: {}", app, e.getMessage());
            return BackupState.empty();
        }
    }

    public synchronized void save(String app, BackupState state) {
        Path file = file(app);
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, app, ".tmp");
            try {
                Files.writeString(temp, json.writeValueAsString(state));
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("백업 상태를 남기지 못했다: " + e.getMessage(), e);
        }
    }

    private Path file(String app) {
        if (app == null || !app.matches(APP)) {
            throw new IllegalArgumentException("앱 이름이 아니다: " + app);
        }
        return dir.resolve(app + ".json");
    }
}
