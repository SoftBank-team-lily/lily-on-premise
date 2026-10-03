package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.DockerfilePlan;
import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.CandidateJudge;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.schema.SchemaApply;
import com.lily.onpremise.schema.pgroll.PgrollEnv;
import com.lily.onpremise.schema.pgroll.PgrollSet;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.Slot;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.source.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 체크아웃 → Dockerfile → 스키마 → 놀고 있는 슬롯에 빌드·기동 → Ready → 후보 판정 → 그 다음에만 프록시를 옮긴다.
 * Ready 나 판정 전에 실패하면 후보 컨테이너만 지우고, 공개 주소는 이전 슬롯을 계속 본다.
 * 프록시를 옮긴 뒤에 이전 슬롯을 지우다 실패해도 새 슬롯은 남겨 둔다. 지운 슬롯의 이미지 태그는 롤백에 쓴다.
 */
public final class OnPremPipeline {

    private static final Logger log = LoggerFactory.getLogger(OnPremPipeline.class);

    private final Workspace workspace;
    private final StackAnalyzer analyzer;
    private final ContainerRuntime runtime;
    private final Readiness readiness;
    private final TrafficSwitch traffic;
    private final PublicAddress addresses;
    private final SlotBook slots;
    private final int bluePort;
    private final int greenPort;
    private final DatabaseAccess databases;
    private final SchemaApply schema;
    private final CandidateJudge judge;
    private final DeployedApp deployed;
    private final PgrollStep pgroll;
    private final JobCancel cancel;

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort) {
        this(workspace, analyzer, runtime, readiness, traffic, addresses, slots, bluePort, greenPort,
                DatabaseAccess.NONE, SchemaApply.NONE, CandidateJudge.PASS, DeployedApp.IGNORE);
    }

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort,
            DatabaseAccess databases) {
        this(workspace, analyzer, runtime, readiness, traffic, addresses, slots, bluePort, greenPort,
                databases, SchemaApply.NONE, CandidateJudge.PASS, DeployedApp.IGNORE);
    }

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort,
            DatabaseAccess databases,
            SchemaApply schema,
            CandidateJudge judge) {
        this(workspace, analyzer, runtime, readiness, traffic, addresses, slots, bluePort, greenPort,
                databases, schema, judge, DeployedApp.IGNORE);
    }

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort,
            DatabaseAccess databases,
            SchemaApply schema,
            CandidateJudge judge,
            DeployedApp deployed) {
        this(workspace, analyzer, runtime, readiness, traffic, addresses, slots, bluePort, greenPort,
                databases, schema, judge, deployed, PgrollStep.NONE);
    }

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort,
            DatabaseAccess databases,
            SchemaApply schema,
            CandidateJudge judge,
            DeployedApp deployed,
            PgrollStep pgroll) {
        this(workspace, analyzer, runtime, readiness, traffic, addresses, slots, bluePort, greenPort,
                databases, schema, judge, deployed, pgroll, JobCancel.NONE);
    }

    public OnPremPipeline(
            Workspace workspace,
            StackAnalyzer analyzer,
            ContainerRuntime runtime,
            Readiness readiness,
            TrafficSwitch traffic,
            PublicAddress addresses,
            SlotBook slots,
            int bluePort,
            int greenPort,
            DatabaseAccess databases,
            SchemaApply schema,
            CandidateJudge judge,
            DeployedApp deployed,
            PgrollStep pgroll,
            JobCancel cancel) {
        this.cancel = cancel == null ? JobCancel.NONE : cancel;
        this.pgroll = pgroll == null ? PgrollStep.NONE : pgroll;
        this.databases = databases;
        this.schema = schema == null ? SchemaApply.NONE : schema;
        this.judge = judge == null ? CandidateJudge.PASS : judge;
        this.deployed = deployed == null ? DeployedApp.IGNORE : deployed;
        this.workspace = workspace;
        this.analyzer = analyzer;
        this.runtime = runtime;
        this.readiness = readiness;
        this.traffic = traffic;
        this.addresses = addresses;
        this.slots = slots;
        this.bluePort = bluePort;
        this.greenPort = greenPort;
    }

    /**
     * 취소({@link JobCancel})는 단계 사이에서 멈추거나, 기다리던 git·docker·헬스 대기를 끊어서 실패처럼 빠져나온다.
     * 어느 쪽이든 취소를 받은 잡은 CANCELLED 로 남긴다. 트래픽을 바꾼 뒤에는 취소를 받지 않는다
     */
    public void execute(JobRecord record, DeployJob job, Consumer<JobRecord> publish) {
        try {
            cancel.check(job.id());
            run(record, job, publish);
        } catch (RuntimeException e) {
            if (record.getStatus() == JobRecord.Status.SUCCEEDED) {
                return;
            }
            if (cancel.requested(job.id())) {
                log.info("job cancelled: id={} app={} at={}", job.id(), job.appName(), e.getMessage());
                stage(record, publish, JobRecord.Status.CANCELLED, "cancelled: " + safe(e.getMessage()));
                return;
            }
            log.warn("job failed: id={} app={} reason={}", job.id(), job.appName(), e.getMessage());
            stage(record, publish, JobRecord.Status.FAILED, "failed: " + safe(e.getMessage()));
        }
    }

    private void run(JobRecord record, DeployJob job, Consumer<JobRecord> publish) {
        stage(record, publish, JobRecord.Status.CHECKOUT,
                "checkout: " + job.repoUrl() + " branch=" + job.branch());
        Path checkout = workspace.checkout(job);
        Path context = context(checkout, job.rootDir());

        DockerfilePlan plan = analyzer.plan(context, job.dockerfile());
        String health = analyzer.healthPath(plan, job.healthPath());
        if (plan.origin() != DockerfilePlan.Origin.EXISTING) {
            try {
                Files.writeString(context.resolve("Dockerfile"), plan.content());
            } catch (IOException e) {
                throw new IllegalStateException("Dockerfile 을 쓰지 못했습니다", e);
            }
        }
        stage(record, publish, JobRecord.Status.ANALYZE,
                "analyze: " + plan.origin().name().toLowerCase()
                        + " stack=" + plan.stack().name().toLowerCase()
                        + " health=" + health);

        String image = "lily-onprem/" + job.appName() + ":" + job.id();
        cancel.check(job.id());
        stage(record, publish, JobRecord.Status.BUILDING, "build: " + image);
        runtime.build(context, image, plan.origin() == DockerfilePlan.Origin.GENERATED);
        cancel.check(job.id());

        // 공개 주소가 보고 있는 포트에는 후보를 올리지 않는다. 헬스가 끝나기 전에 트래픽이 들어간다.
        int upstream = traffic.upstreamPort();
        Optional<String> live = slots.liveContainer();
        for (String retired : slots.containersExcept(live.orElse(null))) {
            runtime.stop(retired);
        }
        Slot target = upstream == bluePort ? Slot.GREEN : Slot.BLUE;
        int port = target == Slot.BLUE ? bluePort : greenPort;
        String name = SlotBook.container(job.appName(), target);

        Map<String, String> env = new LinkedHashMap<>();
        if (job.env() != null) {
            env.putAll(job.env());
        }
        if (job.database() != null) {
            // DB 위치: cloud 는 RDS 터널, local 은 이 PC 의 DB 컨테이너, external 은 사용자가 준 주소
            stage(record, publish, JobRecord.Status.STARTING,
                    "database: " + job.database() + " (" + job.databaseModeOrDefault() + ") preparing");
            Map<String, String> db = databases.prepare(job);
            env.putAll(db);
            stage(record, publish, JobRecord.Status.STARTING, "database: " + job.database()
                    + " (" + job.databaseModeOrDefault() + ") env keys=" + db.keySet());
            if (job.env() != null) {
                env.putAll(job.env()); // 사용자가 넣은 값이 이긴다
            }
        }
        env.put("APP_COLOR", target.name().toLowerCase());
        env.put("APP_VERSION", job.id());

        // 스키마를 바꾸기 전 마지막 확인. 이후에는 후보를 띄운 뒤의 확인({@link JobCancel#commit})에서 되돌린다
        cancel.check(job.id());
        boolean schemaApplied = false;
        // 이번 잡이 시작한 pgroll 마이그레이션. 전환 전에 실패하면 되돌린다
        String pgrollMigration = null;
        Map<String, String> agentEnv = job.database() == null ? Map.of() : databases.agentEnv(job, env);
        if (job.migrations() != null && !job.migrations().isEmpty()) {
            if (job.database() == null) {
                throw new IllegalStateException("migrations 가 있는데 database 가 없다");
            }
            if (pgroll.handles(job.migrations())) {
                stage(record, publish, JobRecord.Status.STARTING, "schema: pgroll " + job.migrations().size() + " files");
                PgrollStep.Started started = pgroll.start(job, agentEnv, upstream > 0);
                for (String line : started.logs()) {
                    stage(record, publish, JobRecord.Status.STARTING, line);
                }
                if (started.started()) {
                    pgrollMigration = started.migration();
                }
                env = new LinkedHashMap<>(PgrollEnv.withSchema(env, PgrollSet.versionSchema(started.migration())));
            } else {
                stage(record, publish, JobRecord.Status.STARTING, "schema: " + job.migrations().size() + " files");
                schema.apply(agentEnv, job.migrations());
                schemaApplied = true;
            }
            // 앱이 같은 스크립트를 다시 실행하지 않게 한다. 클라우드 배포와 같은 키다
            env.put("SPRING_FLYWAY_ENABLED", "false");
        } else if ("postgres".equals(job.database())) {
            // 마이그레이션 파일이 없는 커밋도 pgroll 로 관리하던 DB 면 최신 버전 스키마로 붙는다
            Optional<String> latest = pgroll.latest(agentEnv);
            if (latest.isPresent()) {
                env = new LinkedHashMap<>(PgrollEnv.withSchema(env, PgrollSet.versionSchema(latest.get())));
                env.put("SPRING_FLYWAY_ENABLED", "false");
                stage(record, publish, JobRecord.Status.STARTING,
                        "schema: pgroll 관리 DB, " + PgrollSet.versionSchema(latest.get()) + " 로 접속");
            }
        }

        stage(record, publish, JobRecord.Status.STARTING, "start: " + name + " 127.0.0.1:" + port);
        runtime.start(name, image, port, job.targetPort(), env);

        stage(record, publish, JobRecord.Status.HEALTH, "health: 127.0.0.1:" + port + ("tcp".equalsIgnoreCase(health) ? " (tcp)" : health));
        try {
            readiness.await(port, health);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("health failed, traffic unchanged: " + safe(e.getMessage()) + schemaNote(job, schemaApplied, pgrollMigration, agentEnv), e);
        }

        // 첫 배포(upstream 0)는 비교할 슬롯이 없다. 이전 슬롯이 있을 때만 후보 루프백을 판정한다
        if (upstream > 0) {
            stage(record, publish, JobRecord.Status.JUDGING,
                    "judge: 127.0.0.1:" + port + " " + probePath(job.canaryPath(), health));
            try {
                Optional<String> reason = judge.reject(port, upstream, probePath(job.canaryPath(), health));
                if (reason.isPresent()) {
                    throw new IllegalStateException("judge rejected, traffic unchanged: " + reason.get());
                }
            } catch (RuntimeException e) {
                runtime.stop(name);
                String message = e.getMessage() != null && e.getMessage().contains("traffic unchanged")
                        ? e.getMessage()
                        : "judge failed, traffic unchanged: " + safe(e.getMessage());
                throw new IllegalStateException(message + schemaNote(job, schemaApplied, pgrollMigration, agentEnv), e);
            }
        }

        String published;
        try {
            published = addresses.ensure(job.appName());
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("hostname failed, candidate removed: " + safe(e.getMessage()) + schemaNote(job, schemaApplied, pgrollMigration, agentEnv), e);
        }
        if (!cancel.commit(job.id())) {
            runtime.stop(name);
            throw new IllegalStateException("before switch, traffic unchanged, candidate removed"
                    + schemaNote(job, schemaApplied, pgrollMigration, agentEnv));
        }
        stage(record, publish, JobRecord.Status.SWITCHING,
                "switch: " + target.name().toLowerCase() + " host=" + published);
        try {
            traffic.route(port);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("switch failed, candidate removed: " + safe(e.getMessage()) + schemaNote(job, schemaApplied, pgrollMigration, agentEnv), e);
        }
        if (pgrollMigration != null) {
            try {
                pgroll.opened(job.appName(), pgrollMigration, agentEnv);
                stage(record, publish, JobRecord.Status.SWITCHING, "schema: pgroll 롤백 창 열림 " + pgrollMigration);
            } catch (RuntimeException e) {
                // 창 기록이 없으면 다음 배포가 complete 한다. 트래픽은 이미 새 슬롯이다
                log.warn("pgroll window not recorded: app={} reason={}", job.appName(), e.getMessage());
            }
        }
        slots.commit(job.appName(), target);
        slots.retain(job.appName());
        slots.published(new SlotBook.Release(
                job.appName(), target.name().toLowerCase(), image, port, job.targetPort(), health, Map.copyOf(env)));
        live.ifPresent(previous -> {
            if (previous.equals(name)) {
                return;
            }
            try {
                runtime.stop(previous);
            } catch (RuntimeException e) {
                log.warn("previous slot kept running: {}", e.getMessage());
            }
        });

        record.url(published);
        record.activeSlot(target.name().toLowerCase());
        deployed.note(job, plan.origin() == DockerfilePlan.Origin.EXISTING);
        stage(record, publish, JobRecord.Status.SUCCEEDED, "done: " + published);
    }

    /**
     * 직전 배포의 이미지로 그 슬롯을 다시 띄우고 프록시를 되돌린다.
     * 스키마는 그대로 둔다. 되돌릴 기록이 없으면 실패하고 upstream 은 바꾸지 않는다.
     */
    public void rollback(JobRecord record, String app, Consumer<JobRecord> publish) {
        try {
            restore(record, app, publish);
        } catch (RuntimeException e) {
            log.warn("rollback failed: app={} reason={}", app, e.getMessage());
            if (record.getStatus() != JobRecord.Status.SUCCEEDED) {
                stage(record, publish, JobRecord.Status.FAILED, "failed: " + safe(e.getMessage()));
            }
        }
    }

    private void restore(JobRecord record, String app, Consumer<JobRecord> publish) {
        SlotBook.Release previous = slots.previousRelease(app)
                .orElseThrow(() -> new IllegalStateException("되돌릴 슬롯이 없다"));
        SlotBook.Release current = slots.currentRelease(app)
                .orElseThrow(() -> new IllegalStateException("되돌릴 슬롯이 없다"));
        String name = SlotBook.container(app, Slot.valueOf(previous.slot().toUpperCase()));
        String retire = SlotBook.container(app, Slot.valueOf(current.slot().toUpperCase()));
        // 현재 릴리스가 pgroll 마이그레이션을 새로 적용했으면 롤백 창 안에서만 되돌린다 (이전 버전 스키마가 그때까지만 있다)
        String revert = pgrollMigration(current, previous);
        if (revert != null) {
            Optional<String> blocked = pgroll.rollbackBlocker(app, revert);
            if (blocked.isPresent()) {
                throw new IllegalStateException("rollback refused, traffic unchanged: " + blocked.get());
            }
        }
        stage(record, publish, JobRecord.Status.STARTING,
                "rollback start: " + name + " image=" + previous.image());
        runtime.start(name, previous.image(), previous.hostPort(), previous.containerPort(), previous.env());
        try {
            stage(record, publish, JobRecord.Status.HEALTH, "rollback health: 127.0.0.1:" + previous.hostPort());
            readiness.await(previous.hostPort(), previous.healthPath());
            stage(record, publish, JobRecord.Status.SWITCHING, "rollback switch: " + previous.slot());
            traffic.route(previous.hostPort());
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("rollback failed, traffic unchanged: " + safe(e.getMessage()), e);
        }
        slots.rolledBack(app);
        if (!retire.equals(name)) {
            try {
                runtime.stop(retire);
            } catch (RuntimeException e) {
                log.warn("previous slot kept running: {}", e.getMessage());
            }
        }
        String schemaResult = "";
        if (revert != null) {
            // 현재 슬롯을 내린 뒤라 새 버전 스키마를 쓰는 컨테이너가 없다
            try {
                pgroll.rolledBack(app, revert);
                stage(record, publish, JobRecord.Status.SWITCHING,
                        "rollback schema: pgroll rollback " + revert + " (새 버전 스키마 제거, 행 유지)");
                schemaResult = " schema=reverted";
            } catch (RuntimeException e) {
                log.warn("pgroll rollback failed after traffic switch: app={} reason={}", app, e.getMessage());
                stage(record, publish, JobRecord.Status.SWITCHING,
                        "rollback schema: pgroll rollback 실패, 앱 롤백은 유지 — " + safe(e.getMessage()));
                schemaResult = " schema=partial";
            }
        }
        String url = traffic.publicUrl();
        record.url(url);
        record.activeSlot(previous.slot());
        stage(record, publish, JobRecord.Status.SUCCEEDED, "rollback: slot=" + previous.slot() + schemaResult);
    }

    /** 현재 릴리스가 이전 릴리스와 다른 pgroll 버전 스키마로 붙어 있으면 그 마이그레이션 이름 */
    private static String pgrollMigration(SlotBook.Release current, SlotBook.Release previous) {
        String now = current.env() == null ? null : current.env().get(PgrollEnv.SCHEMA_ENV);
        String before = previous.env() == null ? null : previous.env().get(PgrollEnv.SCHEMA_ENV);
        if (now == null || now.equals(before) || !now.startsWith("public_")) {
            return null;
        }
        return now.substring("public_".length());
    }

    /** tcp 헬스는 5xx 를 볼 경로가 없으므로 클라우드와 같이 / 를 본다 */
    public static String probePath(String canary, String health) {
        String path = canary != null && !canary.isBlank() ? canary : health;
        if (path == null || "tcp".equalsIgnoreCase(path.trim())) {
            return "/";
        }
        return path;
    }

    private static Path context(Path checkout, String rootDir) {
        if (rootDir == null || rootDir.isBlank()) {
            return checkout;
        }
        Path resolved = checkout.resolve(rootDir).normalize();
        if (!resolved.startsWith(checkout.normalize())) {
            throw new IllegalStateException("rootDir 이 checkout 밖입니다");
        }
        return resolved;
    }

    private static void stage(JobRecord record, Consumer<JobRecord> publish, JobRecord.Status status, String line) {
        record.update(status, line);
        try {
            publish.accept(record);
        } catch (RuntimeException e) {
            log.warn("could not publish {}: {}", record.getId(), e.getMessage());
        }
    }

    /**
     * 전환 전 실패 메시지 뒤에 붙는 스키마 결과. 후보 컨테이너는 이미 내렸다.
     * 이번 잡이 시작한 pgroll 마이그레이션은 되돌리고, Flyway 스키마는 그대로 둔다
     */
    private String schemaNote(DeployJob job, boolean schemaApplied, String pgrollMigration,
                              Map<String, String> agentEnv) {
        if (pgrollMigration != null) {
            try {
                pgroll.revert(job.appName(), agentEnv, pgrollMigration);
                return ", schema reverted (pgroll rollback " + pgrollMigration + ")";
            } catch (RuntimeException e) {
                log.warn("pgroll revert failed: app={} migration={} reason={}", job.appName(), pgrollMigration,
                        e.getMessage());
                return ", schema revert failed (pgroll " + pgrollMigration + " still active): " + safe(e.getMessage());
            }
        }
        return schemaApplied ? ", schema left in place" : "";
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String one = message.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 500 ? one.substring(0, 500) : one;
    }
}
