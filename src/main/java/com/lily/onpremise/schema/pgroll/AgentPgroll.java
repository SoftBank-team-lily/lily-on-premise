package com.lily.onpremise.schema.pgroll;

import com.lily.onpremise.database.LocalDatabase;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.pipeline.PgrollStep;
import com.lily.onpremise.runtime.SlotBook;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 에이전트의 pgroll 단계. 규칙은 lily-cicd 와 같다 ({@link PgrollMigrator}, {@link PgrollLinter}).
 *
 * <p>init 은 이벤트 트리거라 superuser 가 필요해서 DB 위치마다 다르다.
 * <ul>
 *   <li>local: 이 PC 의 DB 컨테이너 관리자 계정({@code postgres})으로 한다</li>
 *   <li>external: 사용자가 준 계정으로 해 본다. superuser 가 아니면 이유를 알리고 거절한다</li>
 *   <li>cloud: RDS 관리자 권한은 lily-db-provisioner 에만 있어서 builder 가 잡을 보내기 전에 켠다</li>
 * </ul>
 *
 * <p>롤백 창은 {@link PgrollWindows} 에 남기고, 창이 지나면 {@link #completeDue} 가 complete 한다.
 */
public final class AgentPgroll implements PgrollStep {

    private static final Logger log = LoggerFactory.getLogger(AgentPgroll.class);

    private final PgrollMigrator migrator;
    private final LocalDatabase local;
    private final PgrollWindows windows;
    private final Duration rollbackWindow;
    private final Clock clock;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public AgentPgroll(PgrollMigrator migrator, LocalDatabase local, PgrollWindows windows, Duration rollbackWindow) {
        this(migrator, local, windows, rollbackWindow, Clock.systemUTC());
    }

    AgentPgroll(PgrollMigrator migrator, LocalDatabase local, PgrollWindows windows, Duration rollbackWindow,
                Clock clock) {
        this.migrator = migrator;
        this.local = local;
        this.windows = windows;
        this.rollbackWindow = rollbackWindow;
        this.clock = clock;
    }

    @Override
    public boolean handles(Map<String, String> migrations) {
        return PgrollSet.isPgroll(migrations);
    }

    @Override
    public Started start(DeployJob job, Map<String, String> agentEnv, boolean previousLive) {
        if (!"postgres".equals(job.database())) {
            throw new IllegalArgumentException("pgroll 마이그레이션은 postgres 만 지원한다");
        }
        PgrollSet set = PgrollSet.parse(job.migrations());
        List<String> logs = new ArrayList<>();
        if (!migrator.installed(agentEnv)) {
            enable(job, agentEnv);
            logs.add("schema: pgroll enabled (" + job.databaseModeOrDefault() + ")");
        }
        PgrollMigrator.PgrollChange change = migrator.migrate(agentEnv, set, previousLive, logs);
        if (change.completed() != null) {
            windows.find(job.appName())
                    .filter(w -> w.migration().equals(change.completed()))
                    .ifPresent(w -> windows.remove(job.appName()));
        }
        cache.remove(job.appName());
        return new Started(change.to(), change.started(), logs);
    }

    /** DB 위치에 맞는 관리자 권한으로 pgroll 상태 스키마와 이벤트 트리거를 만든다 */
    private void enable(DeployJob job, Map<String, String> agentEnv) {
        switch (job.databaseModeOrDefault()) {
            case "local" -> local.enablePgroll(job.appName(), migrator.cli());
            case "external" -> {
                try {
                    migrator.cli().init(DbTarget.from(agentEnv));
                } catch (SchemaOperationException e) {
                    throw new IllegalArgumentException("사용자 DB 계정으로 pgroll 을 켜지 못했다 (이벤트 트리거라 superuser 가 필요하다). "
                            + "superuser 계정의 주소를 주거나 db/pgroll 대신 Flyway SQL 로 보낸다 — " + e.getMessage(), e);
                }
            }
            default -> throw new IllegalStateException("클라우드 DB 에 pgroll 이 켜져 있지 않다 (builder 가 잡을 보내기 전에 켠다)");
        }
    }

    @Override
    public Optional<String> latest(Map<String, String> agentEnv) {
        try {
            return migrator.latest(agentEnv);
        } catch (RuntimeException e) {
            log.warn("pgroll state lookup failed, app keeps default schema: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void revert(String app, Map<String, String> agentEnv, String migration) {
        migrator.rollback(agentEnv, migration, new ArrayList<>());
        windows.remove(app);
        cache.remove(app);
    }

    @Override
    public void opened(String app, String migration, Map<String, String> agentEnv) {
        windows.put(new PgrollWindows.Window(app, migration, null, clock.instant().plus(rollbackWindow),
                connection(agentEnv)));
        cache.remove(app);
    }

    @Override
    public Optional<String> rollbackBlocker(String app, String migration) {
        Optional<PgrollWindows.Window> window = windows.find(app).filter(w -> w.migration().equals(migration));
        if (window.isEmpty()) {
            return Optional.of(closed(migration));
        }
        try {
            if (migrator.active(window.get().env()).filter(migration::equals).isEmpty()) {
                windows.remove(app);
                return Optional.of(closed(migration));
            }
        } catch (RuntimeException e) {
            return Optional.of("pgroll 상태를 읽지 못했다: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void rolledBack(String app, String migration) {
        PgrollWindows.Window window = windows.find(app)
                .orElseThrow(() -> new IllegalStateException(closed(migration)));
        migrator.rollback(window.env(), migration, new ArrayList<>());
        windows.remove(app);
        cache.remove(app);
    }

    /** 앱의 롤백 창. 없으면 빈 값 */
    public Optional<PgrollWindows.Window> window(String app) {
        return windows.find(app);
    }

    /**
     * 앱의 스키마 상태. lily-cicd {@code GET /api/deployments/{app}/schema} 와 같은 모양이라
     * builder 가 그대로 화면에 넘긴다: appName, database, engine, currentVersion, window, slots, history, message.
     *
     * @param current  지금 트래픽을 받는 슬롯의 릴리스
     * @param previous 롤백하면 되살릴 슬롯의 릴리스
     */
    public Map<String, Object> status(String app, Optional<SlotBook.Release> current,
                                      Optional<SlotBook.Release> previous) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appName", app);
        String currentSchema = current.map(r -> schema(r)).orElse(null);
        Optional<PgrollWindows.Window> window = windows.find(app);
        boolean pgroll = currentSchema != null || window.isPresent();
        body.put("database", current.map(r -> r.env() != null && r.env().containsKey("DB_URL")
                ? (r.env().get("DB_URL").startsWith("jdbc:mysql:") ? "mysql" : "postgres") : null).orElse(null));
        body.put("engine", pgroll ? "pgroll" : null);
        body.put("currentVersion", currentSchema == null ? null : currentSchema.substring("public_".length()));
        body.put("window", window.map(w -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("migration", w.migration());
            m.put("slot", current.map(SlotBook.Release::slot).orElse(null));
            m.put("completeAfter", w.completeAfter() == null ? null : w.completeAfter().toString());
            return m;
        }).orElse(null));
        List<Map<String, Object>> slots = new ArrayList<>();
        current.ifPresent(r -> slots.add(slot(r, true, window)));
        previous.ifPresent(r -> slots.add(slot(r, false, window)));
        body.put("slots", slots);
        List<Map<String, Object>> history = new ArrayList<>();
        String message = null;
        Map<String, String> env = window.map(PgrollWindows.Window::env)
                .orElse(current.map(SlotBook.Release::env).orElse(null));
        if (pgroll && env != null) {
            try {
                for (PgrollMigrator.History h : migrator.history(env)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("version", h.name());
                    row.put("description", null);
                    row.put("state", h.done() ? "complete" : "active");
                    row.put("startedAt", h.startedAt() == null ? null : h.startedAt().toString());
                    history.add(row);
                }
            } catch (RuntimeException e) {
                message = "스키마 이력을 읽지 못했다: " + e.getMessage();
            }
        } else if (!pgroll) {
            message = "pgroll 로 관리하지 않는 앱이다";
        }
        body.put("history", history);
        body.put("message", message);
        return body;
    }

    private static Map<String, Object> slot(SlotBook.Release release, boolean live,
                                            Optional<PgrollWindows.Window> window) {
        Map<String, Object> m = new LinkedHashMap<>();
        String schema = schema(release);
        String version = schema == null ? null : schema.substring("public_".length());
        m.put("slot", release.slot());
        m.put("schemaVersion", version);
        m.put("replicas", live ? 1 : 0);
        m.put("deployedAt", null);
        m.put("pgrollState", live && window.filter(w -> w.migration().equals(version)).isPresent() ? "active" : null);
        return m;
    }

    private static String schema(SlotBook.Release release) {
        String schema = release.env() == null ? null : release.env().get(PgrollEnv.SCHEMA_ENV);
        return schema != null && schema.startsWith("public_") ? schema : null;
    }

    /** 상태 보고(3초마다)에 싣는다. DB 이력은 15초에 한 번만 읽는다 */
    public Map<String, Object> cachedStatus(String app, Optional<SlotBook.Release> current,
                                            Optional<SlotBook.Release> previous) {
        Cached hit = cache.get(app);
        Instant now = clock.instant();
        if (hit != null && hit.at().plusSeconds(15).isAfter(now)) {
            return hit.body();
        }
        Map<String, Object> body = status(app, current, previous);
        cache.put(app, new Cached(now, body));
        return body;
    }

    /**
     * 롤백 창을 기다리지 않고 지금 complete 한다 (화면의 complete 버튼). 이후에는 스키마를 되돌릴 수 없다.
     *
     * @return 결과 한 줄
     */
    public String completeNow(String app) {
        Optional<PgrollWindows.Window> window = windows.find(app);
        if (window.isEmpty()) {
            return "롤백 창이 열린 pgroll 마이그레이션이 없다";
        }
        PgrollWindows.Window w = window.get();
        try {
            if (migrator.active(w.env()).filter(w.migration()::equals).isPresent()) {
                migrator.complete(w.env(), w.migration(), new ArrayList<>());
            }
            windows.remove(app);
            cache.remove(app);
            return "pgroll complete " + w.migration();
        } catch (RuntimeException e) {
            return "pgroll complete 실패: " + e.getMessage();
        }
    }

    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();

    private record Cached(Instant at, Map<String, Object> body) {
    }

    /** 롤백 창이 지난 마이그레이션을 complete 한다. 다음 배포가 먼저 complete 했으면 창 기록만 지운다 */
    public void completeDue() {
        Instant now = clock.instant();
        for (PgrollWindows.Window window : windows.all()) {
            if (window.completeAfter() == null || now.isBefore(window.completeAfter())) {
                continue;
            }
            try {
                if (migrator.active(window.env()).filter(window.migration()::equals).isPresent()) {
                    migrator.complete(window.env(), window.migration(), new ArrayList<>());
                    log.info("pgroll completed: app={} migration={}", window.app(), window.migration());
                }
                windows.remove(window.app());
            } catch (RuntimeException e) {
                log.warn("pgroll complete failed, retry later: app={} reason={}", window.app(), e.getMessage());
            }
        }
    }

    /** 30초마다 {@link #completeDue} */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("lily-pgroll-completer").start(() -> {
            while (!stopped.get()) {
                completeDue();
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @PreDestroy
    public void stop() {
        stopped.set(true);
    }

    private static String closed(String migration) {
        return "롤백 창이 지나 pgroll " + migration + " 이 complete 됐다. 이전 버전 스키마가 없다";
    }

    /** 창 기록에는 접속에 필요한 값만 남긴다 */
    private static Map<String, String> connection(Map<String, String> agentEnv) {
        DbTarget target = DbTarget.from(agentEnv);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DB_URL", target.url());
        env.put("DB_USERNAME", target.username());
        env.put("DB_PASSWORD", target.password());
        return env;
    }
}
