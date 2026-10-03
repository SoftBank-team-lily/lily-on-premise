package com.lily.onpremise.burst;

import com.lily.onpremise.database.DatabaseModes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.expose.UpstreamProxy;
import com.lily.onpremise.job.DeployJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 클라우드 버스팅 상태 머신. 1초마다 프록시에서 처리 중인 요청 수를 보고 판단한다.
 *
 * <pre>
 * 배포 성공 → STANDBY  클라우드에 대기 배포 요청, 빌드가 끝날 때까지
 *          → WARMING  대기 Pod warmReplicas 대를 띄우고 Ingress 로 닿을 때까지 (warmReplicas 0 이면 건너뜀)
 *          → IDLE     로컬이 한도 안에서 처리. 대기 Pod 가 있으면 한도를 넘는 요청은 바로 그쪽으로
 * 로컬이 한도를 scaleUpAfter 초 동안 넘김
 *          → 대기 Pod 있음: OVERFLOWING  레플리카 N 으로 올리면서 넘김은 이미 켜져 있다
 *          → 대기 Pod 없음: SCALING      레플리카 N 으로, Ready 1 이상이면 OVERFLOWING
 * cooldown 초 동안 한가 → 레플리카를 warmReplicas 로 (없으면 넘김 끄고 0) → IDLE
 * </pre>
 *
 * 대기 Pod 를 두는 이유: 0 에서 띄우면 Spring 앱 기준 Ingress 가 Pod 까지 닿는 데 20~40초 걸리고,
 * 그동안 넘기지 못해 로컬에 요청이 쌓인다.
 *
 * <p>켜고 끄기와 클라우드 비율은 화면에서 정한다 ({@link #configure}). 비율은 대기 Pod 가 닿은 뒤부터
 * 요청마다 그 확률로 클라우드에 보내고, 넘침(로컬 한도 초과)은 비율과 상관없이 계속 넘긴다.
 * 플랫폼 연결이면 builder 호출과 Ingress 주소를 컨트롤 플레인이 준다 ({@link #platform}).
 */
@Component
public class CloudBurst implements BurstGate {

    private static final Logger log = LoggerFactory.getLogger(CloudBurst.class);
    private static final long SCALE_TIMEOUT_MILLIS = 180_000;
    private static final int EVENT_LIMIT = 30;

    public enum Phase { OFF, STANDBY, WARMING, IDLE, SCALING, OVERFLOWING }

    private final AgentProperties.Burst settings;
    private final AgentProperties.Cloudflare cloudflare;
    private final UpstreamProxy proxy;
    private final BiPredicate<InetSocketAddress, String> reachesPod;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lily-burst");
        t.setDaemon(true);
        return t;
    });
    private final Deque<String> events = new ArrayDeque<>();

    private volatile BurstClient client;
    private volatile String ingressHost;
    private volatile int ingressPort;
    /** 플랫폼 존. 환경변수에 존이 없을 때 공개 호스트를 정한다 */
    private volatile String platformZone = "";

    private volatile Phase phase = Phase.OFF;
    /** 지금 단계에 들어온 시각 (화면이 경과 시간을 보인다) */
    private volatile long phaseSince = System.currentTimeMillis();
    /** 진행 중이거나 마지막 대기 배포의 builder 빌드 id */
    private volatile String standbyBuild = "";
    /** 거점을 옮기는 중이면 참. 그동안 켜기·끄기를 받지 않는다 */
    private volatile BooleanSupplier moving = () -> false;
    /** 화면에서 켠 상태. 환경변수 BURST_ENABLED 가 처음 값이다 */
    private volatile boolean enabled;
    private volatile int cloudPercent;
    /** 마지막으로 성공한 온프레미스 배포. 켜면 이 잡으로 대기 배포한다 */
    private volatile DeployJob lastJob;
    /** 끄거나 다시 배포하면 늘린다. 이전 대기 배포가 늦게 끝나도 반영하지 않는다 */
    private final AtomicInteger generation = new AtomicInteger();
    private volatile String appName;
    private volatile String host;
    /** 대기 Pod 가 Ingress 로 닿아서 넘김이 켜져 있다 */
    private volatile boolean warm;
    private long lastSaturated;
    private long saturatedSince;
    private long quietSince;
    private long scalingSince;
    /** 거점이 온프레미스가 아니면 레플리카를 움직이지 않는다 */
    private volatile BooleanSupplier scaleGate = () -> true;
    private volatile Consumer<DeployJob> standbyListener;
    /**
     * 에이전트가 다시 떠서 슬롯을 다시 붙인 앱 ({@link #recover}). 버스팅이 켜져 있으면 다음 틱에 클라우드 대기 Pod 를 찾아
     * 넘김을 다시 켠다. 플랫폼 연결이면 builder 중계가 붙을 때까지 기다린다
     */
    private volatile String pendingRecover;

    @Autowired
    public CloudBurst(AgentProperties properties, LocalExposure exposure, ObjectMapper json) {
        this(properties.burst(), properties.cloudflare(), exposure.proxy(),
                properties.burst().builderUrl().isBlank() ? null : new BurstClient(properties.burst(), json),
                IngressProbe::reachesPod);
    }

    CloudBurst(AgentProperties.Burst settings, AgentProperties.Cloudflare cloudflare, UpstreamProxy proxy,
               BurstClient client, BiPredicate<InetSocketAddress, String> reachesPod) {
        this.settings = settings;
        this.cloudflare = cloudflare;
        this.proxy = proxy;
        this.client = client;
        this.reachesPod = reachesPod;
        this.ingressHost = settings.ingressHost();
        this.ingressPort = settings.ingressPort();
        this.enabled = settings.enabled();
    }

    @PostConstruct
    void start() {
        proxy.localLimit(settings.localLimit());
        scheduler.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
        if (enabled) {
            event("enabled: localLimit=" + settings.localLimit() + " replicas=" + settings.replicas()
                    + " warm=" + settings.warmReplicas() + " target=" + ingressHost + ":" + ingressPort);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** 플랫폼 연결: builder 호출은 컨트롤 플레인 소켓으로, 넘길 곳은 컨트롤 플레인이 준 Ingress 다 */
    public void platform(BurstClient relay, String ingressHost, int ingressPort, String zoneName) {
        this.client = relay;
        this.ingressHost = ingressHost == null ? "" : ingressHost;
        this.ingressPort = ingressPort;
        this.platformZone = zoneName == null ? "" : zoneName.trim().toLowerCase();
        event("platform: target=" + this.ingressHost + ":" + ingressPort);
    }

    /** builder 와 넘길 곳이 있어서 켤 수 있다 */
    public boolean available() {
        return client != null && ingressHost != null && !ingressHost.isBlank();
    }

    /**
     * 화면에서 켜고 끄고 클라우드 비율을 정한다. 소켓 수신 스레드에서 불리므로 일은 스케줄러에서 한다.
     * 끄면 넘김을 멈추고 클라우드를 0 으로 내린다. 켜면 마지막 배포로 대기 배포를 시작한다 (없으면 다음 배포부터).
     *
     * @param app     이 앱에 대한 설정일 때만 받는다. 비우면 지금 앱
     * @param percent 0~100. 범위를 벗어나면 잘라 낸다
     */
    public void configure(String app, boolean on, int percent) {
        scheduler.execute(() -> apply(app, on, Math.max(0, Math.min(100, percent))));
    }

    void apply(String app, boolean on, int percent) {
        DeployJob job = lastJob;
        String current = job != null ? job.appName() : appName;
        if (app != null && !app.isBlank() && current != null && !app.equals(current)) {
            event("skip config: app " + app + " is not " + current);
            return;
        }
        if (percent != cloudPercent) {
            cloudPercent = percent;
            proxy.cloudShare(percent);
            event("share: cloud " + percent + "%");
        }
        if (on == enabled) {
            return;
        }
        if (moving.getAsBoolean()) {
            // 거점 전환도 대기 배포를 쓴다. 겹치면 늦게 끝난 쪽이 클라우드를 0 으로 내릴 수 있다
            event("skip: 거점을 옮기는 중이라 버스팅을 " + (on ? "켜지" : "끄지") + " 않아요");
            return;
        }
        if (on && job != null && job.onPremOnly()) {
            event("skip: 온프레미스 전용이라 버스팅을 켜지 않아요");
            return;
        }
        if (on && !scaleGate.getAsBoolean()) {
            // 공개 주소가 클라우드를 보는 동안 대기 배포를 하면 끝날 때 그 클라우드 Pod 를 0 으로 내린다
            event("skip: 공개 주소가 클라우드라 버스팅을 켜지 않아요");
            return;
        }
        enabled = on;
        if (!on) {
            pendingRecover = null;
            generation.incrementAndGet();
            proxy.clearOverflow();
            warm = false;
            Phase was = phase;
            setPhase(Phase.OFF);
            if (was != Phase.OFF && appName != null && client != null && scaleGate.getAsBoolean()) {
                try {
                    client.scale(appName, 0);
                } catch (RuntimeException e) {
                    event("off: scale down failed: " + e.getMessage());
                }
            }
            event("off");
            return;
        }
        event("on");
        if (job != null) {
            onDeployed(job);
        } else if (appName != null) {
            // 재시작 뒤 배포 잡 없이 다시 붙인 앱. 클라우드에 남은 대기 배포를 다시 쓴다
            pendingRecover = appName;
        }
    }

    /**
     * 앱을 지웠다. 이 앱의 대기 배포와 넘김을 잊고 켜기 상태도 처음 값으로 돌린다.
     * 클라우드 쪽 리소스는 builder 가 지우므로 레플리카를 움직이지 않는다
     */
    public void forget(String app) {
        try {
            scheduler.submit(() -> drop(app)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("버스팅 상태를 지우지 못했습니다: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    void drop(String app) {
        DeployJob job = lastJob;
        String current = job != null ? job.appName() : appName;
        if (app == null || !app.equals(current)) {
            return;
        }
        generation.incrementAndGet();
        pendingRecover = null;
        proxy.clearOverflow();
        warm = false;
        setPhase(Phase.OFF);
        lastJob = null;
        appName = null;
        host = null;
        standbyBuild = "";
        enabled = settings.enabled();
        event("removed: " + app);
    }

    private void setPhase(Phase next) {
        if (phase != next) {
            phaseSince = System.currentTimeMillis();
        }
        phase = next;
    }

    @Override
    public void holdChanges(BooleanSupplier moving) {
        this.moving = moving == null ? () -> false : moving;
    }

    @Override
    public boolean busy() {
        return phase == Phase.STANDBY;
    }

    @Override
    public void allowScale(BooleanSupplier gate) {
        this.scaleGate = gate == null ? () -> true : gate;
    }

    @Override
    public void whenStandby(Consumer<DeployJob> listener) {
        this.standbyListener = listener;
    }

    /** 공개 주소가 클라우드를 보는 동안 버스팅이 레플리카를 0으로 내리지 않게 한다 */
    @Override
    public void park() {
        proxy.clearOverflow();
        warm = false;
        setPhase(Phase.OFF);
        event("park: home is cloud");
    }

    /** 거점 전환이 클라우드 Pod 를 대기 수로 되돌린다. 닿는지 확인하고 넘김을 다시 켠다 */
    @Override
    public void resume() {
        if (!enabled || appName == null || client == null || phase != Phase.OFF) {
            return;
        }
        scalingSince = System.currentTimeMillis();
        setPhase(Phase.WARMING);
        event("resume: home is onprem");
    }

    private volatile DatabaseModes onPremDatabase;

    /** DB 위치가 local·external 인 앱의 클라우드 접속 정보를 여기서 받는다 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void onPremDatabase(DatabaseModes databases) {
        this.onPremDatabase = databases;
    }

    @Override
    public void standbyAgain(DeployJob job) {
        onDeployed(job);
    }

    /**
     * 에이전트가 다시 떠서 이미 떠 있던 앱 슬롯을 다시 붙였다. 배포 잡이 없으니 대기 배포는 새로 하지 않고,
     * 클라우드에 남은 대기 배포가 있으면 대기 Pod 수를 맞추고 넘김을 다시 켠다 (없으면 다음 배포부터)
     */
    public void recover(String app) {
        if (app != null && !app.isBlank() && lastJob == null) {
            pendingRecover = app;
        }
    }

    /** 온프레미스 배포가 성공하면 클라우드에 같은 앱을 대기 배포한다 (켜져 있을 때) */
    public void onDeployed(DeployJob job) {
        pendingRecover = null;
        lastJob = job;
        if (job.onPremOnly()) {
            enabled = false;
            proxy.clearOverflow();
            event("skip: 온프레미스 전용이라 클라우드 대기 배포를 하지 않아요");
            return;
        }
        if (!enabled) {
            return;
        }
        if (!scaleGate.getAsBoolean()) {
            event("skip: 공개 주소가 클라우드라 대기 배포하지 않아요");
            return;
        }
        if (!available()) {
            event("skip: builder 또는 클라우드 Ingress 가 없습니다 (BURST_* 또는 플랫폼 연결 필요)");
            return;
        }
        String publicHost = publicHost(job.appName());
        if (publicHost == null) {
            event("skip: publicHost 를 정할 수 없습니다 (BURST_PUBLIC_HOST 또는 Cloudflare zone 필요)");
            return;
        }
        int run = generation.incrementAndGet();
        proxy.clearOverflow();
        this.warm = false;
        this.appName = job.appName();
        this.host = publicHost;
        setPhase(Phase.STANDBY);
        // 대기 배포는 길게는 15분 걸린다. 그동안 틱과 설정 변경이 막히지 않게 스케줄러 밖에서 기다린다
        Thread.ofVirtual().name("lily-burst-standby").start(() -> prepareStandby(job, publicHost, run));
    }

    /** 내 PC 가 처리한 요청의 최근 5분 p95 (ms). 없으면 -1 */
    public long proxyLocalP95() {
        return proxy.localP95Millis();
    }

    public Status status() {
        UpstreamProxy.Pressure pressure = proxy.pressure();
        synchronized (events) {
            DeployJob job = lastJob;
            return new Status(enabled, available(), cloudPercent, phase, phaseSince, standbyBuild, appName, host, warm,
                    pressure,
                    List.copyOf(events),
                    job == null ? "HYBRID" : job.deploymentModeOrDefault());
        }
    }

    @Override
    public String standby(DeployJob job, String publicHost) {
        String database = job.database() != null ? job.database() : settings.database();
        Map<String, String> databaseEnv = null;
        if (job.database() != null && !"cloud".equals(job.databaseModeOrDefault())) {
            // 이 PC 의 DB 를 쓴다. 클라우드 Pod 는 역방향 터널로 같은 DB 에 붙는다
            Optional<Map<String, String>> env = onPremDatabase == null
                    ? Optional.empty() : onPremDatabase.cloudEnv(job.appName());
            if (env.isEmpty()) {
                throw new IllegalStateException("클라우드에서 이 PC 의 DB 에 닿을 역방향 터널이 없습니다 (플랫폼 연결 필요)");
            }
            database = null;
            databaseEnv = env.get();
        }
        BurstClient current = client;
        if (current == null) {
            throw new IllegalStateException("builder 연결이 없습니다");
        }
        return current.standby(job, publicHost, database, databaseEnv);
    }

    private void prepareStandby(DeployJob job, String publicHost, int run) {
        try {
            String id = standby(job, publicHost);
            standbyBuild = id;
            event("standby: cloud build " + id + " host=" + publicHost);
            long deadline = System.currentTimeMillis() + 15 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                if (run != generation.get()) {
                    event("standby superseded: " + id);
                    return;
                }
                BurstClient.BuildState state = client.build(id);
                if ("SUCCEEDED".equals(state.status())) {
                    Consumer<DeployJob> listener = standbyListener;
                    if (listener != null) {
                        listener.accept(job);
                    }
                    scheduler.execute(() -> {
                        if (run == generation.get()) {
                            standbyReady(System.currentTimeMillis());
                        }
                    });
                    return;
                }
                if ("FAILED".equals(state.status())) {
                    off(run, "standby failed: " + state.lastLog());
                    return;
                }
                Thread.sleep(5_000);
            }
            off(run, "standby timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            off(run, "standby error: " + e.getMessage());
        }
    }

    private void off(int run, String line) {
        if (run == generation.get()) {
            setPhase(Phase.OFF);
        }
        event(line);
    }

    /** 대기 배포가 끝났다 (builder 가 레플리카 0 으로 내려 둔 상태). 대기 Pod 를 띄운다 */
    void standbyReady(long now) {
        if (!scaleGate.getAsBoolean()) {
            // 거점이 클라우드다. 공개 주소가 쓰는 레플리카를 대기 수로 내리지 않는다
            setPhase(Phase.OFF);
            event("standby ready: home is cloud, replicas unchanged");
            return;
        }
        if (settings.warmReplicas() < 1) {
            setPhase(Phase.IDLE);
            event("standby ready: cloud replicas 0");
            return;
        }
        client.scale(appName, settings.warmReplicas());
        setPhase(Phase.WARMING);
        scalingSince = now;
        event("standby ready: warming cloud replicas " + settings.warmReplicas());
    }

    private void safeTick() {
        try {
            tick(proxy.pressure(), System.currentTimeMillis());
        } catch (RuntimeException e) {
            event("error: " + e.getMessage());
        }
    }

    void tick(UpstreamProxy.Pressure p, long now) {
        if (pendingRecover != null && phase == Phase.OFF) {
            recoverPending(now);
        }
        if (!scaleGate.getAsBoolean()) {
            lastSaturated = p.saturatedTotal();
            return;
        }
        long delta = p.saturatedTotal() - lastSaturated;
        lastSaturated = p.saturatedTotal();

        switch (phase) {
            case WARMING -> {
                if (cloudReachable()) {
                    proxy.overflowTo(ingress());
                    warm = true;
                    setPhase(Phase.IDLE);
                    event("warm: cloud ready, overflow on" + (cloudPercent > 0 ? ", share " + cloudPercent + "%" : ""));
                } else if (now - scalingSince > SCALE_TIMEOUT_MILLIS) {
                    // 대기 Pod 없이도 동작은 한다. 넘길 때 띄우는 방식으로 돌아간다
                    client.scale(appName, 0);
                    setPhase(Phase.IDLE);
                    event("warm timeout: cloud back to 0");
                }
            }
            case IDLE -> {
                // 한도가 찬 채로 새 요청이 오거나, 처리 중인 요청이 한도만큼 계속 있으면 과부하
                boolean pressured = delta > 0 || p.localActive() >= p.localLimit();
                if (!pressured) {
                    saturatedSince = 0;
                    return;
                }
                if (saturatedSince == 0) {
                    saturatedSince = now;
                }
                if (now - saturatedSince >= settings.scaleUpAfterSeconds() * 1000L) {
                    int replicas = Math.max(settings.replicas(), settings.warmReplicas());
                    client.scale(appName, replicas);
                    saturatedSince = 0;
                    if (warm) {
                        // 넘김은 대기 Pod 로 이미 켜져 있다. 늘어나는 Pod 는 Ready 되는 대로 Ingress 가 나눠 준다
                        setPhase(Phase.OVERFLOWING);
                        quietSince = 0;
                    } else {
                        setPhase(Phase.SCALING);
                        scalingSince = now;
                    }
                    event("scale up: local " + p.localActive() + "/" + p.localLimit()
                            + " -> cloud replicas " + replicas + (warm ? " (warm, overflowing)" : ""));
                }
            }
            case SCALING -> {
                BurstClient.AppState app = client.app(appName);
                // Deployment 가 Ready 여도 Ingress 엔드포인트 반영은 늦다. 공개 호스트로 Pod 까지 닿는지 확인하고 넘긴다
                if (app.readyReplicas() >= 1 && reachesPod.test(ingress(), host)) {
                    proxy.overflowTo(ingress());
                    setPhase(Phase.OVERFLOWING);
                    quietSince = 0;
                    event("overflowing: cloud ready " + app.readyReplicas() + "/" + app.replicas());
                } else if (now - scalingSince > SCALE_TIMEOUT_MILLIS) {
                    client.scale(appName, 0);
                    setPhase(Phase.IDLE);
                    event("scale up timeout: cloud back to 0");
                }
            }
            case OVERFLOWING -> {
                // 비율로 나누는 중이면 클라우드에 늘 요청이 있다. 그때는 로컬 과부하만 본다
                boolean busy = delta > 0 || p.localActive() >= p.localLimit()
                        || (cloudPercent == 0 && p.remoteActive() > 0);
                if (busy) {
                    quietSince = 0;
                    return;
                }
                if (quietSince == 0) {
                    quietSince = now;
                }
                if (now - quietSince >= settings.cooldownSeconds() * 1000L) {
                    if (warm) {
                        client.scale(appName, settings.warmReplicas());
                    } else {
                        proxy.clearOverflow();
                        client.scale(appName, 0);
                    }
                    setPhase(Phase.IDLE);
                    event("scale down: quiet " + settings.cooldownSeconds() + "s, cloud replicas "
                            + (warm ? settings.warmReplicas() : 0) + ", overflowed total " + p.overflowedTotal());
                }
            }
            default -> {
                // OFF, STANDBY: 판단하지 않는다
            }
        }
    }

    private void recoverPending(long now) {
        String app = pendingRecover;
        if (!enabled || lastJob != null) {
            pendingRecover = null;
            return;
        }
        if (!available()) {
            // 플랫폼 연결: welcome 으로 builder 중계와 Ingress 를 받을 때까지 기다린다
            return;
        }
        pendingRecover = null;
        String publicHost = publicHost(app);
        if (publicHost == null) {
            event("restore: publicHost 를 정할 수 없습니다 (BURST_PUBLIC_HOST 또는 Cloudflare zone 필요)");
            return;
        }
        this.appName = app;
        this.host = publicHost;
        if (!scaleGate.getAsBoolean()) {
            // 거점이 클라우드다. 거점 전환이 온프레미스로 돌아오면 resume() 이 넘김을 다시 켠다
            event("restore: " + app + " home is cloud, waiting");
            return;
        }
        BurstClient.AppState cloud;
        try {
            cloud = client.app(app);
        } catch (RuntimeException e) {
            event("restore: " + app + " has no cloud standby (" + e.getMessage() + "), next deploy");
            return;
        }
        if (settings.warmReplicas() < 1) {
            setPhase(Phase.IDLE);
            event("restore: " + app + " cloud standby found, cloud replicas 0");
            return;
        }
        if (cloud.replicas() < settings.warmReplicas()) {
            client.scale(app, settings.warmReplicas());
        }
        scalingSince = now;
        setPhase(Phase.WARMING);
        event("restore: " + app + " warming cloud replicas " + Math.max(cloud.replicas(), settings.warmReplicas()));
    }

    private boolean cloudReachable() {
        BurstClient.AppState app = client.app(appName);
        return app.readyReplicas() >= 1 && reachesPod.test(ingress(), host);
    }

    private InetSocketAddress ingress() {
        return new InetSocketAddress(ingressHost, ingressPort);
    }

    private String publicHost(String app) {
        String pattern = settings.publicHost();
        if (pattern != null && !pattern.isBlank()) {
            return pattern.replace("{app}", app).toLowerCase();
        }
        String zone = cloudflare == null ? "" : cloudflare.zoneNameNormalized();
        if (zone.isBlank()) {
            zone = platformZone;
        }
        return zone.isBlank() ? null : app + "." + zone;
    }

    private void event(String line) {
        log.info("burst {}", line);
        synchronized (events) {
            events.addFirst(Instant.now() + " " + line);
            while (events.size() > EVENT_LIMIT) {
                events.removeLast();
            }
        }
    }

    /**
     * @param enabled      화면(또는 BURST_ENABLED)에서 켰다
     * @param available    builder 와 클라우드 Ingress 가 있어 켤 수 있다
     * @param cloudPercent 대기 Pod 가 닿은 뒤 클라우드로 보내는 요청 비율
     */
    public record Status(boolean enabled, boolean available, int cloudPercent, Phase phase, long phaseSince,
                         String standbyBuild, String appName,
                         String publicHost, boolean warm, UpstreamProxy.Pressure pressure, List<String> events,
                         String deploymentMode) {
    }
}
