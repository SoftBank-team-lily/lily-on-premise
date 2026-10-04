package com.lily.onpremise.backup;

import com.lily.onpremise.backup.BackupDecision.Action;
import com.lily.onpremise.backup.BackupRecord.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 1분마다 이 PC 의 앱을 보고 백업할지 정한다. 요청 수를 기록하고, {@link BackupWindow} 로 후보 창을 고르고,
 * {@link BackupDecision} 이 RUN·FORCE 면 덤프를 뒤에서 돌린다. 상태는 {@link BackupStore} 에 남긴다.
 *
 * <p>덤프가 도는 동안에도 요청 수는 계속 기록한다. 덤프는 한 번에 하나다 (에이전트 하나는 앱 하나).
 */
public final class BackupScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);
    static final Duration RETRY = Duration.ofMinutes(10);
    static final Duration RETRY_LONG = Duration.ofMinutes(30);
    static final int FAILURES_BEFORE_LONG_RETRY = 3;
    /** 메모리에 둘 최근 분 수 */
    private static final int KEEP_MINUTES = 60;
    private static final java.time.format.DateTimeFormatter LOG_TIME =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 지금 이 PC 에 배포된 앱.
     *
     * @param app  앱 이름
     * @param onPc 공개 주소가 이 PC 를 가리키고 옮기는 중이 아니다 (요청 수를 기록한다)
     * @param skip 백업하지 않는 이유. 대상이면 null
     */
    public record Deployed(String app, boolean onPc, String skip) {
    }

    /** PC DB 를 클라우드 사본으로 덮어쓴다. 실패하면 예외 */
    public interface Dumper {
        void dump(String app);
    }

    /**
     * 화면과 API 에 보여 줄 마지막 판정.
     *
     * @param plan 후보 창. 앱이 없으면 null
     */
    public record Snapshot(String app, BackupPolicy policy, BackupWindow.Plan plan, List<Integer> lastMinutes,
                           Action decision, String reason, boolean running, Instant lastBackupAt,
                           List<BackupRecord> recent) {
    }

    private final BackupPolicy policy;
    private final BackupStore store;
    private final Supplier<Deployed> deployed;
    private final IntSupplier requests;
    private final PcLoad load;
    private final ChangeCounter changes;
    private final Dumper dumper;
    private final Executor dumps;
    private final Clock clock;
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lily-backup");
        t.setDaemon(true);
        return t;
    });

    private String app;
    private RequestHistory history;
    private BackupState state;
    private Instant retryAfter;
    private volatile boolean running;
    private volatile Snapshot last;
    private String lastLogged;

    /**
     * @param requests 프록시가 이 PC 로 보낸 최근 60초 요청 수
     * @param dumps    덤프를 돌릴 곳 (판정 스레드를 막지 않는다)
     */
    public BackupScheduler(BackupPolicy policy, BackupStore store, Supplier<Deployed> deployed, IntSupplier requests,
                           PcLoad load, ChangeCounter changes, Dumper dumper, Executor dumps, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.deployed = deployed;
        this.requests = requests;
        this.load = load;
        this.changes = changes;
        this.dumper = dumper;
        this.dumps = dumps;
        this.clock = clock;
        this.last = new Snapshot(null, policy, null, List.of(), Action.NONE, "아직 판정하지 않았다", false, null, List.of());
    }

    /** 1분마다 판정한다. 첫 판정은 1분 뒤 (첫 요청 수가 온전한 1분이 되게) */
    public void start() {
        ticks.scheduleWithFixedDelay(this::safeTick, 60, 60, TimeUnit.SECONDS);
        log.info("backup scheduler started: every {}h, quiet {}m, zone {}", policy.intervalHours(),
                policy.quietMinutes(), policy.zone());
    }

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("backup tick failed: {}", e.getMessage());
        }
    }

    synchronized void tick() {
        Instant now = clock.instant();
        Deployed current = deployed.get();
        if (current == null || current.app() == null || current.app().isBlank()) {
            last = new Snapshot(null, policy, null, List.of(), Action.NONE, "배포된 앱이 없다", running, null, List.of());
            return;
        }
        switchTo(current.app());
        if (current.onPc()) {
            history.record(now, requests.getAsInt());
            state = state.withProfile(history.profile());
            save();
        }
        BackupWindow.Plan plan = BackupWindow.plan(history.profile(), policy, now, state.lastBackupAt(),
                state.longestDump());
        List<Integer> minutes = history.lastMinutes(now, policy.quietMinutes());

        BackupDecision.Result result;
        Long count = null;
        if (current.skip() != null) {
            result = new BackupDecision.Result(Action.NONE, current.skip());
        } else if (running) {
            result = new BackupDecision.Result(Action.NONE, "덤프 중");
        } else {
            count = changes.current(app);
            PcLoad.Reading reading = load.read(app);
            result = BackupDecision.decide(new BackupDecision.Inputs(true, false, retryAfter,
                    ChangeCounter.changed(state.changeMark(), count), now, plan, minutes,
                    reading.cpuPercent(), reading.activeConnections()), policy);
        }

        logDecision(result, plan, minutes);
        switch (result.action()) {
            case SKIP -> {
                state = state.withRecord(new BackupRecord(now, now, Outcome.SKIPPED, false, result.reason()))
                        .withChangeMark(count);
                save();
            }
            case RUN, FORCE -> begin(count, result.action() == Action.FORCE, null);
            default -> {
            }
        }
        last = new Snapshot(app, policy, plan, minutes, result.action(), result.reason(), running,
                state.lastBackupAt(), state.recent());
    }

    /**
     * 판정 없이 지금 백업한다 (발표·수동). 대상이 아니거나 덤프 중이면 하지 않는다.
     *
     * @return 하지 않은 이유. 시작했으면 null
     */
    public synchronized String backupNow() {
        Deployed current = deployed.get();
        if (current == null || current.app() == null || current.app().isBlank()) {
            return "배포된 앱이 없다";
        }
        if (current.skip() != null) {
            return current.skip();
        }
        if (running) {
            return "덤프 중";
        }
        switchTo(current.app());
        begin(changes.current(app), true, "수동");
        return null;
    }

    public Snapshot snapshot() {
        return last;
    }

    public boolean running() {
        return running;
    }

    /**
     * 판정이 바뀔 때만 한 줄 남긴다 (매분 같은 줄을 찍지 않는다). SKIP·RUN·FORCE 는 일이 일어난 것이라 늘 남긴다.
     * 예: {@code backup decision: app=memo WAIT 후보 창 밖 | stage=2 window=10-05 03:00~04:00 KST deadline=10-05 11:12 threshold=5 last=[0, 1, 0]}
     */
    private void logDecision(BackupDecision.Result result, BackupWindow.Plan plan, List<Integer> minutes) {
        // 사유 안의 숫자("분당 57건")는 매분 바뀌므로 괄호 앞까지만 비교한다
        String reason = result.reason();
        int paren = reason.indexOf(" (");
        String key = app + "|" + result.action() + "|" + (paren < 0 ? reason : reason.substring(0, paren));
        boolean event = result.action() == Action.SKIP || result.action() == Action.RUN || result.action() == Action.FORCE;
        if (!event && key.equals(lastLogged)) {
            return;
        }
        lastLogged = key;
        log.info("backup decision: app={} {} {} | stage={} window={}~{} {} deadline={} threshold={} last={}",
                app, result.action(), result.reason(), plan.stage(), LOG_TIME.format(plan.start()),
                LOG_TIME.format(plan.end()), policy.zone().getId(),
                LOG_TIME.format(plan.deadline().atZone(policy.zone())), Math.round(plan.threshold()), minutes);
    }

    /** 앱이 바뀌었으면 그 앱의 상태를 읽는다 (새 배포, 에이전트 재시작) */
    private void switchTo(String name) {
        if (name.equals(app) && history != null) {
            return;
        }
        app = name;
        state = store.load(name);
        history = new RequestHistory(state.profile(), policy.zone(), KEEP_MINUTES);
        retryAfter = null;
    }

    private void begin(Long mark, boolean forced, String note) {
        String target = app;
        running = true;
        log.info("backup start: app={} forced={}", target, forced);
        try {
            dumps.execute(() -> dump(target, mark, forced, note));
        } catch (RuntimeException e) {
            running = false;
            throw e;
        }
    }

    private void dump(String target, Long mark, boolean forced, String note) {
        Instant started = clock.instant();
        try {
            dumper.dump(target);
            finish(target, new BackupRecord(started, clock.instant(), Outcome.OK, forced, note), mark);
        } catch (RuntimeException e) {
            log.warn("backup failed: app={} {}", target, e.getMessage());
            finish(target, new BackupRecord(started, clock.instant(), Outcome.FAILED, forced, e.getMessage()), null);
        } finally {
            running = false;
        }
    }

    private synchronized void finish(String target, BackupRecord record, Long mark) {
        boolean mine = target.equals(app);
        BackupState base = mine ? state : store.load(target);
        BackupState next = base.withRecord(record);
        if (record.outcome() == Outcome.OK) {
            next = next.withChangeMark(mark);
        }
        if (mine) {
            state = next;
            retryAfter = record.outcome() == Outcome.OK ? null : record.endedAt().plus(
                    next.failuresInRow() >= FAILURES_BEFORE_LONG_RETRY ? RETRY_LONG : RETRY);
            last = new Snapshot(app, policy, last.plan(), last.lastMinutes(), last.decision(), last.reason(), false,
                    state.lastBackupAt(), state.recent());
        }
        store.save(target, next);
        log.info("backup {}: app={} took={}s", record.outcome(), target, record.took().toSeconds());
    }

    private void save() {
        try {
            store.save(app, state);
        } catch (RuntimeException e) {
            log.warn("backup state not saved: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        ticks.shutdownNow();
    }
}
