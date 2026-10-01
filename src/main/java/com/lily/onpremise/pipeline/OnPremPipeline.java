package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.DockerfilePlan;
import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.CandidateJudge;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.schema.SchemaApply;
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
                DatabaseAccess.NONE, SchemaApply.NONE, CandidateJudge.PASS);
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
                databases, SchemaApply.NONE, CandidateJudge.PASS);
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
        this.databases = databases;
        this.schema = schema == null ? SchemaApply.NONE : schema;
        this.judge = judge == null ? CandidateJudge.PASS : judge;
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

    public void execute(JobRecord record, DeployJob job, Consumer<JobRecord> publish) {
        try {
            run(record, job, publish);
        } catch (RuntimeException e) {
            log.warn("job failed: id={} app={} reason={}", job.id(), job.appName(), e.getMessage());
            if (record.getStatus() != JobRecord.Status.SUCCEEDED) {
                stage(record, publish, JobRecord.Status.FAILED, "failed: " + safe(e.getMessage()));
            }
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
        stage(record, publish, JobRecord.Status.BUILDING, "build: " + image);
        runtime.build(context, image);

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
            // 클라우드와 같은 DB. 접속 주소는 온프레미스에서 RDS 로 가는 터널
            Map<String, String> db = databases.prepare(job);
            env.putAll(db);
            stage(record, publish, JobRecord.Status.STARTING, "database: " + job.database() + " env keys=" + db.keySet());
            if (job.env() != null) {
                env.putAll(job.env()); // 사용자가 넣은 값이 이긴다
            }
        }
        env.put("APP_COLOR", target.name().toLowerCase());
        env.put("APP_VERSION", job.id());

        boolean schemaApplied = false;
        if (job.migrations() != null && !job.migrations().isEmpty()) {
            if (job.database() == null) {
                throw new IllegalStateException("migrations 가 있는데 database 가 없다");
            }
            stage(record, publish, JobRecord.Status.STARTING, "schema: " + job.migrations().size() + " files");
            schema.apply(env, job.migrations());
            schemaApplied = true;
            // 앱이 같은 스크립트를 다시 실행하지 않게 한다. 클라우드 배포와 같은 키다
            env.put("SPRING_FLYWAY_ENABLED", "false");
        }

        stage(record, publish, JobRecord.Status.STARTING, "start: " + name + " 127.0.0.1:" + port);
        runtime.start(name, image, port, job.targetPort(), env);

        stage(record, publish, JobRecord.Status.HEALTH, "health: 127.0.0.1:" + port + ("tcp".equalsIgnoreCase(health) ? " (tcp)" : health));
        try {
            readiness.await(port, health);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException(withSchema("health failed, traffic unchanged: " + safe(e.getMessage()), schemaApplied), e);
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
                throw new IllegalStateException(withSchema(message, schemaApplied), e);
            }
        }

        String published;
        try {
            published = addresses.ensure(job.appName());
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException(withSchema("hostname failed, candidate removed: " + safe(e.getMessage()), schemaApplied), e);
        }
        stage(record, publish, JobRecord.Status.SWITCHING,
                "switch: " + target.name().toLowerCase() + " host=" + published);
        try {
            traffic.route(port);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException(withSchema("switch failed, candidate removed: " + safe(e.getMessage()), schemaApplied), e);
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
        String url = traffic.publicUrl();
        record.url(url);
        record.activeSlot(previous.slot());
        stage(record, publish, JobRecord.Status.SUCCEEDED, "rollback: slot=" + previous.slot());
    }

    /** tcp 헬스는 5xx 를 볼 경로가 없으므로 클라우드와 같이 / 를 본다 */
    static String probePath(String canary, String health) {
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

    private static String withSchema(String message, boolean schemaApplied) {
        return schemaApplied ? message + ", schema left in place" : message;
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String one = message.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 500 ? one.substring(0, 500) : one;
    }
}
