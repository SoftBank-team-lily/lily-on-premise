package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.DockerfilePlan;
import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.expose.TrafficSwitch;
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
 * 체크아웃 → Dockerfile → 놀고 있는 슬롯에 빌드·기동 → 그 포트가 Ready → 그 다음에만 프록시를 옮긴다.
 * Ready 전에 실패하면 후보 컨테이너만 지우고, 공개 주소는 이전 슬롯을 계속 본다.
 * 프록시를 옮긴 뒤에 이전 슬롯을 지우다 실패해도 새 슬롯은 남겨 둔다.
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
        env.put("APP_COLOR", target.name().toLowerCase());
        env.put("APP_VERSION", job.id());

        stage(record, publish, JobRecord.Status.STARTING, "start: " + name + " 127.0.0.1:" + port);
        runtime.start(name, image, port, job.targetPort(), env);

        stage(record, publish, JobRecord.Status.HEALTH, "health: 127.0.0.1:" + port + health);
        try {
            readiness.await(port, health);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("health failed, traffic unchanged: " + safe(e.getMessage()), e);
        }

        String published;
        try {
            published = addresses.ensure(job.appName());
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("hostname failed, candidate removed: " + safe(e.getMessage()), e);
        }
        stage(record, publish, JobRecord.Status.SWITCHING,
                "switch: " + target.name().toLowerCase() + " host=" + published);
        try {
            traffic.route(port);
        } catch (RuntimeException e) {
            runtime.stop(name);
            throw new IllegalStateException("switch failed, candidate removed: " + safe(e.getMessage()), e);
        }
        slots.commit(job.appName(), target);
        slots.retain(job.appName());
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

    private static String safe(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String one = message.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 500 ? one.substring(0, 500) : one;
    }
}
