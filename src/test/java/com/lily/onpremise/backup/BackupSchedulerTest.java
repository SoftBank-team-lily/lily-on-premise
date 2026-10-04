package com.lily.onpremise.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lily.onpremise.backup.BackupDecision.Action;
import com.lily.onpremise.backup.BackupRecord.Outcome;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class BackupSchedulerTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final BackupPolicy POLICY = BackupPolicy.defaults();
    /** 2026-10-04 일요일 12:00 KST */
    private static final Instant T0 = Instant.parse("2026-10-04T03:00:00Z");

    @TempDir
    Path workDir;

    private final MutableClock clock = new MutableClock(T0);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong changeCount = new AtomicLong(1);
    private final AtomicInteger dumps = new AtomicInteger();
    private RuntimeException dumpFailure;
    private BackupScheduler.Deployed deployed = new BackupScheduler.Deployed("memo", true, null);
    private Double cpu = 10.0;
    private Executor executor = Runnable::run;
    private BackupStore store;

    @BeforeEach
    void setUp() {
        store = new BackupStore(workDir, JSON);
    }

    @Test
    void 백업한_적이_없으면_첫_판정에서_바로_백업한다() {
        BackupScheduler scheduler = scheduler();

        scheduler.tick();

        assertThat(dumps).hasValue(1);
        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.FORCE);
        BackupState saved = store.load("memo");
        assertThat(saved.lastBackupAt()).isEqualTo(T0);
        assertThat(saved.changeMark()).isEqualTo(1L);
        assertThat(saved.recent()).extracting(BackupRecord::outcome).containsExactly(Outcome.OK);
    }

    @Test
    void 마지막_백업_뒤_변경이_없으면_덤프하지_않고_건너뛴다() {
        BackupScheduler scheduler = scheduler();
        scheduler.tick();
        clock.advance(Duration.ofMinutes(1));

        scheduler.tick();

        assertThat(dumps).hasValue(1);
        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.SKIP);
        assertThat(store.load("memo").recent()).extracting(BackupRecord::outcome)
                .containsExactly(Outcome.OK, Outcome.SKIPPED);
        // 건너뛴 시각부터 마감 시계를 다시 센다
        assertThat(store.load("memo").lastBackupAt()).isEqualTo(T0.plus(Duration.ofMinutes(1)));
    }

    @Test
    void 창_안에서_10분_동안_조용하면_백업한다() {
        BackupScheduler scheduler = backedUpOnce();
        changeCount.set(2);

        // 첫 판정의 1분까지 10분이 차는 9분째에 백업한다
        for (int i = 1; i <= 8; i++) {
            clock.advance(Duration.ofMinutes(1));
            scheduler.tick();
            assertThat(scheduler.snapshot().decision()).as("%d분째", i).isEqualTo(Action.WAIT);
        }
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.RUN);
        assertThat(dumps).hasValue(2);
    }

    @Test
    void 요청이_많으면_미루다가_마감이_지나면_백업한다() {
        BackupPolicy oneHour = new BackupPolicy(1, 10, 5, 1.5, 50, 2, ZoneId.of("Asia/Seoul"));
        BackupScheduler scheduler = scheduler(oneHour);
        scheduler.tick();
        changeCount.set(2);
        requests.set(100);

        for (int i = 1; i < 60; i++) {
            clock.advance(Duration.ofMinutes(1));
            scheduler.tick();
            assertThat(scheduler.snapshot().decision()).isEqualTo(Action.WAIT);
        }
        assertThat(scheduler.snapshot().reason()).contains("요청 많음");
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();

        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.FORCE);
        assertThat(store.load("memo").recent().get(1).forced()).isTrue();
    }

    @Test
    void 실패하면_10분_동안_다시_하지_않는다() {
        dumpFailure = new IllegalStateException("RDS 에 닿지 않는다");
        BackupScheduler scheduler = scheduler();

        scheduler.tick();
        BackupState failed = store.load("memo");
        assertThat(failed.recent()).extracting(BackupRecord::outcome).containsExactly(Outcome.FAILED);
        assertThat(failed.recent().get(0).message()).contains("RDS");
        assertThat(failed.lastBackupAt()).isNull();

        clock.advance(Duration.ofMinutes(5));
        scheduler.tick();
        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.WAIT);
        assertThat(scheduler.snapshot().reason()).contains("실패 뒤 대기");

        dumpFailure = null;
        clock.advance(Duration.ofMinutes(5));
        scheduler.tick();
        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.FORCE);
        assertThat(dumps).hasValue(2);
    }

    @Test
    void 세_번_이어서_실패하면_30분을_기다린다() {
        dumpFailure = new IllegalStateException("x");
        BackupScheduler scheduler = scheduler();
        for (int i = 0; i < 3; i++) {
            scheduler.tick();
            clock.advance(Duration.ofMinutes(10));
        }
        assertThat(dumps).hasValue(3);

        clock.advance(Duration.ofMinutes(10));
        scheduler.tick();
        assertThat(dumps).hasValue(3);
        clock.advance(Duration.ofMinutes(10));
        scheduler.tick();
        assertThat(dumps).hasValue(4);
    }

    @Test
    void 대상이_아니면_백업하지_않지만_요청_수는_기록한다() {
        deployed = new BackupScheduler.Deployed("memo", true, "스키마 변경 롤백 창이 열려 있다");
        requests.set(7);
        BackupScheduler scheduler = scheduler();

        scheduler.tick();

        assertThat(dumps).hasValue(0);
        assertThat(scheduler.snapshot().decision()).isEqualTo(Action.NONE);
        assertThat(scheduler.snapshot().reason()).isEqualTo("스키마 변경 롤백 창이 열려 있다");
        assertThat(store.load("memo").profile().totalMinutes()).isEqualTo(1);
    }

    @Test
    void 거점이_클라우드면_요청_수를_기록하지_않는다() {
        deployed = new BackupScheduler.Deployed("memo", false, "거점이 클라우드다");
        BackupScheduler scheduler = scheduler();

        scheduler.tick();

        assertThat(store.load("memo").profile().totalMinutes()).isZero();
    }

    @Test
    void 덤프가_도는_동안에는_새로_시작하지_않는다() {
        List<Runnable> held = new ArrayList<>();
        executor = held::add;
        BackupScheduler scheduler = scheduler();

        scheduler.tick();
        assertThat(scheduler.running()).isTrue();
        clock.advance(Duration.ofMinutes(1));
        scheduler.tick();
        assertThat(scheduler.snapshot().reason()).isEqualTo("덤프 중");
        assertThat(scheduler.backupNow()).isEqualTo("덤프 중");

        held.get(0).run();
        assertThat(scheduler.running()).isFalse();
        assertThat(held).hasSize(1);
        assertThat(dumps).hasValue(1);
    }

    @Test
    void 지금_백업은_판정_없이_수동으로_기록한다() {
        BackupScheduler scheduler = backedUpOnce();

        assertThat(scheduler.backupNow()).isNull();

        BackupRecord manual = store.load("memo").recent().get(1);
        assertThat(manual.outcome()).isEqualTo(Outcome.OK);
        assertThat(manual.message()).isEqualTo("수동");
        assertThat(dumps).hasValue(2);
    }

    @Test
    void 에이전트가_다시_떠도_마지막_백업과_통계를_이어_간다() {
        backedUpOnce();
        clock.advance(Duration.ofMinutes(1));

        BackupScheduler restarted = scheduler();
        changeCount.set(2);
        restarted.tick();

        assertThat(restarted.snapshot().lastBackupAt()).isEqualTo(T0);
        assertThat(restarted.snapshot().decision()).isEqualTo(Action.WAIT);
        assertThat(store.load("memo").profile().totalMinutes()).isEqualTo(2);
    }

    @Test
    void 앱이_없으면_아무것도_하지_않는다() {
        deployed = null;
        BackupScheduler scheduler = scheduler();

        scheduler.tick();

        assertThat(scheduler.snapshot().reason()).isEqualTo("배포된 앱이 없다");
        assertThat(dumps).hasValue(0);
    }

    /** 첫 판정에서 한 번 백업한 스케줄러 (마감은 24시간 뒤) */
    private BackupScheduler backedUpOnce() {
        BackupScheduler scheduler = scheduler();
        scheduler.tick();
        assertThat(dumps).hasValue(1);
        return scheduler;
    }

    private BackupScheduler scheduler() {
        return scheduler(POLICY);
    }

    private BackupScheduler scheduler(BackupPolicy policy) {
        LocalDbStats db = new LocalDbStats(stats());
        return new BackupScheduler(policy, store, () -> deployed, requests::get,
                new PcLoad(() -> Optional.ofNullable(cpu), db), new ChangeCounter(db),
                app -> {
                    dumps.incrementAndGet();
                    if (dumpFailure != null) {
                        throw dumpFailure;
                    }
                },
                task -> executor.execute(task), clock);
    }

    /** pg_stat_activity 는 0, 변경 수는 changeCount */
    private Commands stats() {
        return new Commands() {
            @Override
            public void run(List<String> command, Path workDir) {
            }

            @Override
            public String output(List<String> command) {
                String sql = command.get(command.size() - 1);
                return sql.contains("n_tup_ins") ? String.valueOf(changeCount.get()) : "0";
            }

            @Override
            public void start(List<String> command, Consumer<String> lines) {
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
