package com.lily.onpremise.cutover;

import com.lily.onpremise.burst.BurstClient;
import com.lily.onpremise.burst.BurstGate;
import com.lily.onpremise.burst.IngressProbe;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.CloudflareHostnameProvisioner;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.pipeline.DeployedApp;
import com.lily.onpremise.pipeline.OnPremPipeline;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 공개 주소의 CNAME 내용물만 온프레미스 터널과 클라우드 오리진 사이에서 바꾼다.
 * 목적지가 Ready 이기 전에는 DNS 를 호출하지 않고, 공개 확인이 실패하면 이전 내용물로 되돌린다.
 *
 * <p>플랫폼 연결이면 클라우드 오리진·Ingress·builder 호출을 컨트롤 플레인이 준다 ({@link #platform}).
 * DNS 호출도 컨트롤 플레인이 중계한다.
 */
public final class HomeCutover implements DeployedApp {

    private static final Logger log = LoggerFactory.getLogger(HomeCutover.class);
    private static final long SCALE_TIMEOUT_MILLIS = 180_000;
    private static final long STANDBY_TIMEOUT_MILLIS = 15 * 60_000L;
    private static final long DRAIN_MILLIS = 30_000;
    private static final int EVENT_LIMIT = 30;

    public enum Phase { ONPREM, MOVING_TO_CLOUD, CLOUD, MOVING_TO_ONPREM, UNKNOWN }

    private final AgentProperties.Burst burstSettings;
    private final CloudflareHostnameProvisioner dns;
    private volatile BurstClient client;
    private volatile String cloudOrigin;
    private volatile String ingressHost;
    private volatile int ingressPort;
    private final BurstGate burst;
    private final LocalDeploy localDeploy;
    private final BooleanSupplier docker;
    private final Predicate<String> publicOk;
    private final BiPredicate<InetSocketAddress, String> reachesPod;
    private final Supplier<String> liveContainer;
    private final Consumer<String> stopContainer;
    private final Later later;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final Path memory;
    private final ScheduledExecutorService scheduler;
    private final Deque<String> events = new ArrayDeque<>();

    private volatile Phase phase = Phase.ONPREM;
    private volatile DeployJob job;
    private volatile boolean repoDockerfile;
    private volatile String cloudJobId;
    private volatile String appName;
    private volatile String cnameContent = "";
    private volatile boolean keepMoving;
    private Place pending;
    private final DelegatingDeploy bridge;

    public HomeCutover(
            AgentProperties properties,
            CloudflareHostnameProvisioner dns,
            BurstGate burst,
            ObjectProvider<OnPremPipeline> pipeline,
            SlotBook slots,
            ContainerRuntime runtime,
            Commands commands,
            com.fasterxml.jackson.databind.ObjectMapper json,
            Path memory) {
        this(properties, dns,
                properties.burst().builderUrl().isBlank() ? null : new BurstClient(properties.burst(), json),
                burst,
                new DelegatingDeploy(pipeline),
                () -> dockerAvailable(commands, memory),
                PublicProbe::ok,
                IngressProbe::reachesPod,
                () -> slots.liveContainer().orElse(null),
                runtime::stop,
                null,
                System::currentTimeMillis,
                HomeCutover::sleep,
                memory,
                true,
                true);
    }

    /** 로컬 재배포의 단계를 컨트롤 플레인에 넘긴다 */
    public void publisher(Consumer<JobRecord> publisher) {
        if (bridge != null && publisher != null) {
            bridge.publisher = publisher;
        }
    }

    /** 테스트는 시계와 DNS, 로컬 배포를 가짜로 넣는다 */
    HomeCutover(
            AgentProperties properties,
            CloudflareHostnameProvisioner dns,
            BurstClient client,
            BurstGate burst,
            LocalDeploy localDeploy,
            BooleanSupplier docker,
            Predicate<String> publicOk,
            BiPredicate<InetSocketAddress, String> reachesPod,
            Supplier<String> liveContainer,
            Consumer<String> stopContainer,
            Later later,
            LongSupplier clock,
            Sleeper sleeper,
            Path memory) {
        this(properties, dns, client, burst, localDeploy, docker, publicOk, reachesPod, liveContainer, stopContainer,
                later, clock, sleeper, memory, false, false);
    }

    private HomeCutover(
            AgentProperties properties,
            CloudflareHostnameProvisioner dns,
            BurstClient client,
            BurstGate burst,
            LocalDeploy localDeploy,
            BooleanSupplier docker,
            Predicate<String> publicOk,
            BiPredicate<InetSocketAddress, String> reachesPod,
            Supplier<String> liveContainer,
            Consumer<String> stopContainer,
            Later later,
            LongSupplier clock,
            Sleeper sleeper,
            Path memory,
            boolean ownScheduler,
            boolean bridged) {
        this.burstSettings = properties.burst();
        AgentProperties.Cutover cutover = properties.cutover() == null
                ? new AgentProperties.Cutover("") : properties.cutover();
        this.cloudOrigin = cutover.cloudOrigin();
        this.ingressHost = burstSettings == null ? "" : burstSettings.ingressHost();
        this.ingressPort = burstSettings == null ? 80 : burstSettings.ingressPort();
        this.dns = dns;
        this.client = client;
        this.burst = burst;
        this.localDeploy = localDeploy;
        this.bridge = bridged && localDeploy instanceof DelegatingDeploy delegating ? delegating : null;
        this.docker = docker == null ? () -> true : docker;
        this.publicOk = publicOk;
        this.reachesPod = reachesPod;
        this.liveContainer = liveContainer;
        this.stopContainer = stopContainer;
        this.clock = clock;
        this.sleeper = sleeper;
        this.memory = memory;
        if (ownScheduler) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "lily-cutover");
                thread.setDaemon(true);
                return thread;
            });
            this.later = (delay, task) -> scheduler.schedule(task, delay, TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
            this.later = later;
        }
        if (burst != null) {
            burst.allowScale(this::onPrem);
            burst.whenStandby(this::noteCloud);
        }
    }

    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 플랫폼 연결: builder 호출은 컨트롤 플레인 소켓으로, 오리진과 Ingress 는 컨트롤 플레인이 준 값이다 */
    public void platform(BurstClient relay, String ingressHost, int ingressPort, String cloudOrigin) {
        this.client = relay;
        this.ingressHost = ingressHost == null ? "" : ingressHost;
        this.ingressPort = ingressPort;
        this.cloudOrigin = new AgentProperties.Cutover(cloudOrigin).cloudOrigin();
    }

    /** 전환에 필요한 연결이 다 있다. 잡과 거점에 따른 거절은 {@link #begin} 이 한다 */
    public boolean movable() {
        return !cloudOrigin.isBlank() && client != null && dns.configured();
    }

    /** 재시작 후 마지막으로 다룬 앱의 CNAME 으로 거점을 복구한다 */
    public synchronized void restore() {
        restore(null);
    }

    /**
     * @param fallbackApp 기억한 앱이 없을 때 쓸 앱. 에이전트 컨테이너를 새로 만들면 작업 폴더가 비어서,
     *                    컨트롤 플레인이 이 에이전트로 마지막에 배포한 앱을 알려 준다
     */
    public synchronized void restore(String fallbackApp) {
        if (phase == Phase.MOVING_TO_CLOUD || phase == Phase.MOVING_TO_ONPREM) {
            return;
        }
        String app = "";
        Path file = memory == null ? null : memory.resolve("cutover-app.txt");
        if (file != null && Files.isRegularFile(file)) {
            try {
                app = Files.readString(file).trim();
            } catch (IOException e) {
                event("restore read failed");
            }
        }
        if (app.isBlank() && fallbackApp != null) {
            app = fallbackApp.trim();
        }
        if (app.isBlank() || !dns.configured()) {
            return;
        }
        try {
            String host = dns.hostname(app);
            String content = dns.cname(host);
            this.appName = app;
            this.cnameContent = content;
            if (content.equalsIgnoreCase(dns.tunnelTarget())) {
                this.phase = Phase.ONPREM;
                dns.holdDns(false);
            } else if (!cloudOrigin.isBlank() && content.equalsIgnoreCase(cloudOrigin)) {
                this.phase = Phase.CLOUD;
                dns.holdDns(true);
                burst.park();
            } else if (!content.isBlank()) {
                this.phase = Phase.UNKNOWN;
                event("cname 이 터널도 클라우드 오리진도 아닙니다");
            }
        } catch (RuntimeException e) {
            event("restore failed: " + e.getMessage());
        }
    }

    @Override
    public synchronized void note(DeployJob job, boolean repoDockerfile) {
        this.job = job;
        this.repoDockerfile = repoDockerfile;
        this.appName = job.appName();
        remember(job.appName());
    }

    public synchronized void noteCloud(DeployJob job) {
        this.cloudJobId = job.id();
    }

    public boolean localRollbackAllowed() {
        return phase == Phase.ONPREM;
    }

    public synchronized Status status() {
        return new Status(phase.name(), appName, hostOf(appName), cnameContent, false, copyEvents());
    }

    /**
     * 거절은 DNS 를 호출하기 전에 예외로 끝난다.
     * 이미 그 거점이면 {@code already} 가 참이고 단계는 바꾸지 않는다.
     */
    public synchronized Status begin(String app, String target) {
        Place want = place(target);
        if (phase == Phase.MOVING_TO_CLOUD || phase == Phase.MOVING_TO_ONPREM) {
            throw new IllegalArgumentException("이미 전환 중입니다");
        }
        if (phase == Phase.UNKNOWN) {
            throw new IllegalArgumentException("거점을 알지 못합니다");
        }
        if ((want == Place.CLOUD && phase == Phase.CLOUD) || (want == Place.ONPREM && phase == Phase.ONPREM)) {
            return new Status(phase.name(), appName, hostOf(appName), cnameContent, true, copyEvents());
        }
        reject(app, want);
        pending = want;
        phase(want == Place.CLOUD ? Phase.MOVING_TO_CLOUD : Phase.MOVING_TO_ONPREM);
        event("move: " + want.name().toLowerCase());
        return status();
    }

    public void perform() {
        Place want;
        synchronized (this) {
            want = pending;
            pending = null;
        }
        if (want == null) {
            return;
        }
        if (want == Place.CLOUD) {
            toCloud();
        } else {
            toOnPrem();
        }
    }

    private void reject(String app, Place want) {
        if (cloudOrigin.isBlank()) {
            throw new IllegalArgumentException("클라우드 오리진이 없습니다 (CUTOVER_CLOUD_ORIGIN 또는 플랫폼 연결)");
        }
        if (CloudflareHostnameProvisioner.ip(cloudOrigin)) {
            throw new IllegalArgumentException("CUTOVER_CLOUD_ORIGIN 은 호스트 이름이어야 합니다");
        }
        if (!dns.configured()) {
            throw new IllegalArgumentException("공개 주소가 플랫폼 존에 없습니다 (Cloudflare 터널 필요)");
        }
        if (client == null) {
            throw new IllegalArgumentException("builder 연결이 없습니다 (BURST_BUILDER_URL·BURST_API_TOKEN 또는 플랫폼 연결)");
        }
        if (want == Place.CLOUD && ingressHost.isBlank()) {
            throw new IllegalArgumentException("클라우드 Ingress 가 없습니다");
        }
        if (job == null) {
            throw new IllegalArgumentException(phase == Phase.CLOUD
                    ? "에이전트가 다시 시작돼 앱 설정을 모릅니다. 다시 배포한 뒤 옮겨 주세요"
                    : "배포된 앱이 없습니다");
        }
        if (!job.appName().equals(app)) {
            throw new IllegalArgumentException("앱이 다릅니다");
        }
        if (!repoDockerfile && (job.dockerfile() == null || job.dockerfile().isBlank())) {
            throw new IllegalArgumentException("Dockerfile 이 없습니다");
        }
        if (cloudJobId != null && !cloudJobId.equals(job.id())) {
            throw new IllegalArgumentException("클라우드 배포의 잡이 다릅니다");
        }
        if (want == Place.ONPREM && !docker.getAsBoolean()) {
            throw new IllegalArgumentException("로컬 Docker 가 없습니다");
        }
    }

    private void toCloud() {
        DeployJob current = job;
        String known = cloudJobId;
        String app = current.appName();
        int warm = burstSettings.warmReplicas();
        keepMoving = false;
        try {
            if (known == null || !known.equals(current.id())) {
                waitStandby(current);
                cloudJobId = current.id();
            }
            client.scale(app, Math.max(burstSettings.replicas(), warm));
            if (!await(this::cloudReady, SCALE_TIMEOUT_MILLIS)) {
                client.scale(app, warm);
                phase(Phase.ONPREM);
                throw new IllegalStateException("클라우드 Pod 에 닿지 못했습니다");
            }
            String host = dns.hostname(app);
            try {
                moveDns(host, cloudOrigin);
            } catch (RuntimeException e) {
                client.scale(app, warm);
                phase(Phase.ONPREM);
                throw new IllegalStateException("CNAME 변경에 실패했습니다: " + e.getMessage(), e);
            }
            String url = "https://" + host + OnPremPipeline.probePath(current.canaryPath(), current.healthPath());
            if (!publicOk.test(url)) {
                try {
                    moveDns(host, dns.tunnelTarget());
                    phase(Phase.ONPREM);
                } catch (RuntimeException e) {
                    keepMoving = true;
                    throw new IllegalStateException("공개 확인에 실패했고 CNAME 을 되돌리지 못했습니다: " + e.getMessage(), e);
                }
                throw new IllegalStateException("공개 주소 확인에 실패했습니다");
            }
            String container = liveContainer.get();
            phase(Phase.CLOUD);
            later.after(DRAIN_MILLIS, () -> stopIf(Phase.CLOUD, container));
            event("home: cloud");
        } catch (RuntimeException e) {
            if (!keepMoving && phase == Phase.MOVING_TO_CLOUD) {
                phase(Phase.ONPREM);
            }
            event(e.getMessage());
            throw e;
        }
    }

    private void toOnPrem() {
        DeployJob current = job;
        String app = current.appName();
        keepMoving = false;
        try {
            if (!localDeploy.deploy(current)) {
                phase(Phase.CLOUD);
                throw new IllegalStateException("로컬 배포에 실패했습니다");
            }
            String host = dns.hostname(app);
            try {
                moveDns(host, dns.tunnelTarget());
            } catch (RuntimeException e) {
                stopIf(Phase.MOVING_TO_ONPREM, liveContainer.get());
                phase(Phase.CLOUD);
                throw new IllegalStateException("CNAME 변경에 실패했습니다: " + e.getMessage(), e);
            }
            String url = "https://" + host + OnPremPipeline.probePath(current.canaryPath(), current.healthPath());
            if (!publicOk.test(url)) {
                try {
                    moveDns(host, cloudOrigin);
                    stopIf(Phase.MOVING_TO_ONPREM, liveContainer.get());
                    phase(Phase.CLOUD);
                } catch (RuntimeException e) {
                    keepMoving = true;
                    throw new IllegalStateException("공개 확인에 실패했고 CNAME 을 되돌리지 못했습니다: " + e.getMessage(), e);
                }
                throw new IllegalStateException("공개 주소 확인에 실패했습니다");
            }
            phase(Phase.ONPREM);
            if (burst != null) {
                burst.resume();
            }
            int warm = burstSettings.warmReplicas();
            later.after(DRAIN_MILLIS, () -> {
                if (phase != Phase.ONPREM || client == null) {
                    return;
                }
                try {
                    client.scale(app, warm);
                    event("cloud warm " + warm);
                } catch (RuntimeException e) {
                    event("scale down failed: " + e.getMessage());
                }
            });
            event("home: onprem");
        } catch (RuntimeException e) {
            if (!keepMoving && phase == Phase.MOVING_TO_ONPREM) {
                phase(Phase.CLOUD);
            }
            event(e.getMessage());
            throw e;
        }
    }

    private void waitStandby(DeployJob current) {
        String host = dns.hostname(current.appName());
        // DB 위치가 이 PC 면 역방향 터널 접속 정보로 보내야 해서 버스팅과 같은 길로 보낸다
        String id = burst != null ? burst.standby(current, host) : client.standby(current, host, current.database());
        if (!await(() -> standbyDone(id), STANDBY_TIMEOUT_MILLIS)) {
            throw new IllegalStateException("클라우드 대기 배포가 끝나지 않았습니다");
        }
    }

    private boolean standbyDone(String id) {
        BurstClient.BuildState state = client.build(id);
        if ("FAILED".equals(state.status())) {
            throw new IllegalStateException("클라우드 대기 배포에 실패했습니다: " + state.lastLog());
        }
        return "SUCCEEDED".equals(state.status());
    }

    private boolean cloudReady() {
        BurstClient.AppState app = client.app(job.appName());
        return app.readyReplicas() >= 1
                && reachesPod.test(new InetSocketAddress(ingressHost, ingressPort),
                dns.hostname(job.appName()));
    }

    private boolean await(BooleanSupplier ok, long budget) {
        long start = clock.getAsLong();
        while (!ok.getAsBoolean()) {
            if (clock.getAsLong() - start > budget) {
                return false;
            }
            sleeper.sleep(1_000);
        }
        return true;
    }

    private void moveDns(String host, String content) {
        dns.point(host, content);
        this.cnameContent = content;
    }

    private void stopIf(Phase expected, String container) {
        if (phase != expected || container == null) {
            return;
        }
        try {
            stopContainer.accept(container);
        } catch (RuntimeException e) {
            event("local stop failed: " + e.getMessage());
        }
    }

    private synchronized void phase(Phase next) {
        this.phase = next;
        dns.holdDns(next == Phase.CLOUD || next == Phase.MOVING_TO_ONPREM);
        if (next == Phase.CLOUD && burst != null) {
            burst.park();
        }
    }

    private boolean onPrem() {
        return phase == Phase.ONPREM;
    }

    private String hostOf(String app) {
        if (app == null || app.isBlank() || !dns.configured()) {
            return "";
        }
        try {
            return dns.hostname(app);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void remember(String app) {
        if (memory == null) {
            return;
        }
        try {
            Files.createDirectories(memory);
            Files.writeString(memory.resolve("cutover-app.txt"), app);
        } catch (IOException e) {
            event("app file failed: " + e.getMessage());
        }
    }

    private List<String> copyEvents() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void event(String line) {
        log.info("cutover {}", line);
        synchronized (events) {
            events.addFirst(line);
            while (events.size() > EVENT_LIMIT) {
                events.removeLast();
            }
        }
    }

    private static Place place(String target) {
        if ("cloud".equals(target)) {
            return Place.CLOUD;
        }
        if ("onprem".equals(target)) {
            return Place.ONPREM;
        }
        throw new IllegalArgumentException("home 은 cloud 또는 onprem 입니다");
    }

    private static String moveId() {
        return "m" + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static boolean dockerAvailable(Commands commands, Path dir) {
        try {
            commands.run(List.of("docker", "version"), dir == null ? Path.of(".") : dir);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private enum Place { CLOUD, ONPREM }

    /** 온프레미스에 같은 잡을 다시 띄운다. 실패하면 거짓 */
    @FunctionalInterface
    interface LocalDeploy {
        boolean deploy(DeployJob job);
    }

    /** 드레인 뒤에 슬롯을 멈추거나 레플리카를 내린다 */
    @FunctionalInterface
    interface Later {
        void after(long delayMillis, Runnable task);
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis);
    }

    /** 파이프라인은 거점 전환 빈보다 나중에 연결된다 */
    private static final class DelegatingDeploy implements LocalDeploy {
        private final ObjectProvider<OnPremPipeline> pipelines;
        private volatile Consumer<JobRecord> publisher = record -> {
        };

        private DelegatingDeploy(ObjectProvider<OnPremPipeline> pipelines) {
            this.pipelines = pipelines;
        }

        @Override
        public boolean deploy(DeployJob job) {
            JobRecord record = new JobRecord(moveId(), job.appName());
            pipelines.getObject().execute(record, job, publisher);
            return record.getStatus() == JobRecord.Status.SUCCEEDED;
        }
    }

    public record Status(String phase, String appName, String publicHost, String cname, boolean already,
                         List<String> events) {
    }
}
