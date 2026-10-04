package com.lily.onpremise.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lily.onpremise.backup.BackupRecord.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant T0 = Instant.parse("2026-10-03T18:00:00Z");

    @TempDir
    Path workDir;

    @Test
    void 저장한_상태를_에이전트가_다시_떠도_그대로_읽는다() {
        BackupStore store = new BackupStore(workDir, JSON);
        RequestProfile profile = new RequestProfile();
        profile.add(ZonedDateTime.of(2026, 10, 4, 3, 0, 0, 0, ZoneId.of("Asia/Seoul")), 7);
        BackupState state = BackupState.empty()
                .withProfile(profile)
                .withChangeMark(42L)
                .withRecord(new BackupRecord(T0, T0.plusSeconds(30), Outcome.OK, false, null));

        store.save("my-blog", state);
        BackupState loaded = new BackupStore(workDir, JSON).load("my-blog");

        assertThat(loaded.requests()).isEqualTo(state.requests());
        assertThat(loaded.minutes()).isEqualTo(state.minutes());
        assertThat(loaded.lastBackupAt()).isEqualTo(T0);
        assertThat(loaded.changeMark()).isEqualTo(42L);
        assertThat(loaded.recent()).containsExactlyElementsOf(state.recent());
        assertThat(loaded.profile().mean(java.time.DayOfWeek.SUNDAY, 3)).isEqualTo(7.0);
    }

    @Test
    void 파일이_없거나_깨졌으면_빈_상태로_시작한다() throws IOException {
        BackupStore store = new BackupStore(workDir, JSON);
        assertThat(store.load("blog").lastBackupAt()).isNull();

        Files.createDirectories(workDir.resolve("backup"));
        Files.writeString(workDir.resolve("backup/blog.json"), "{\"requests\":[1,2,3]");
        assertThat(store.load("blog").recent()).isEmpty();

        Files.writeString(workDir.resolve("backup/blog.json"), "{\"requests\":[1,2,3],\"minutes\":[1,2,3]}");
        assertThat(store.load("blog").requests()).hasSize(RequestProfile.SLOTS);
    }

    @Test
    void 저장_뒤에_임시_파일이_남지_않는다() throws IOException {
        BackupStore store = new BackupStore(workDir, JSON);
        store.save("blog", BackupState.empty());
        store.save("blog", BackupState.empty());

        try (Stream<Path> files = Files.list(workDir.resolve("backup"))) {
            assertThat(files.map(f -> f.getFileName().toString())).containsExactly("blog.json");
        }
    }

    @Test
    void 앱_이름이_아니면_경로로_쓰지_않는다() {
        BackupStore store = new BackupStore(workDir, JSON);

        assertThatThrownBy(() -> store.save("../etc", BackupState.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.load("Blog")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 성공과_건너뜀은_마감_시계를_새로_시작하고_실패는_그대로_둔다() {
        BackupState ok = BackupState.empty().withRecord(new BackupRecord(T0, T0.plusSeconds(60), Outcome.OK, false, null));
        Instant later = T0.plusSeconds(3600);
        BackupState failed = ok.withRecord(new BackupRecord(later, later.plusSeconds(5), Outcome.FAILED, false, "x"));
        BackupState skipped = failed.withRecord(new BackupRecord(later, later, Outcome.SKIPPED, false, "변경 없음"));

        // 성공은 덤프를 시작한 시각 (사본은 그 시점 데이터다)
        assertThat(ok.lastBackupAt()).isEqualTo(T0);
        assertThat(failed.lastBackupAt()).isEqualTo(T0);
        assertThat(failed.failuresInRow()).isEqualTo(1);
        assertThat(skipped.lastBackupAt()).isEqualTo(later);
        assertThat(skipped.failuresInRow()).isZero();
    }

    @Test
    void 결과는_최근_30개만_남는다() {
        BackupState state = BackupState.empty();
        for (int i = 0; i < 35; i++) {
            Instant at = T0.plusSeconds(i * 60L);
            state = state.withRecord(new BackupRecord(at, at.plusSeconds(1), Outcome.OK, false, null));
        }

        assertThat(state.recent()).hasSize(BackupState.KEEP);
        assertThat(state.recent().get(0).startedAt()).isEqualTo(T0.plusSeconds(5 * 60L));
    }

    @Test
    void 창_길이에_쓰는_덤프_시간은_최근_성공_3번_중_가장_긴_것이다() {
        BackupState state = BackupState.empty();
        assertThat(state.longestDump()).isNull();

        int[] seconds = {3600, 60, 120, 90};
        for (int i = 0; i < seconds.length; i++) {
            Instant at = T0.plusSeconds(i * 86_400L);
            state = state.withRecord(new BackupRecord(at, at.plusSeconds(seconds[i]), Outcome.OK, false, null));
        }
        Instant at = T0.plusSeconds(10 * 86_400L);
        state = state.withRecord(new BackupRecord(at, at.plusSeconds(9_999), Outcome.FAILED, false, "x"));

        // 1시간짜리는 4번 전이라 보지 않고, 실패는 세지 않는다
        assertThat(state.longestDump()).isEqualTo(Duration.ofSeconds(120));
    }
}
