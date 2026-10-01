package com.lily.onpremise.burst;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final BurstClient client;
    private final BiPredicate<InetSocketAddress, String> reachesPod;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lily-burst");
        t.setDaemon(true);
        return t;
    });
    private final Deque<String> events = new ArrayDeque<>();

    private volatile Phase phase = Phase.OFF;
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

    @Autowired
    public CloudBurst(AgentProperties properties, LocalExposure exposure, ObjectMapper json) {
        this(properties.burst(), properties.cloudflare(), exposure.proxy(),
                properties.burst().enabled() ? new BurstClient(properties.burst(), json) : null,
                IngressProbe::reachesPod);
    }

    CloudBurst(AgentProperties.Burst settings, AgentProperties.Cloudflare cloudflare, UpstreamProxy proxy,
               BurstClient client, BiPredicate<InetSocketAddress, String> reachesPod) {
        this.settings = settings;
        this.cloudflare = cloudflare;
        this.proxy = proxy;
        this.client = client;
        this.reachesPod = reachesPod;
    }

    @PostConstruct
    void start() {
        if (!settings.enabled()) {
            return;
        }
        proxy.localLimit(settings.localLimit());
        scheduler.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
        event("enabled: localLimit=" + settings.localLimit() + " replicas=" + settings.replicas()
                + " warm=" + settings.warmReplicas()
                + " target=" + settings.ingressHost() + ":" + settings.ingressPort());
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
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
        phase = Phase.OFF;
        event("park: home is cloud");
    }

    /** 온프레미스 배포가 성공하면 클라우드에 같은 앱을 대기 배포한다 */
    public void onDeployed(DeployJob job) {
        if (!settings.enabled()) {
            return;
        }
        String publicHost = publicHost(job.appName());
        if (publicHost == null) {
            event("skip: publicHost 를 정할 수 없습니다 (BURST_PUBLIC_HOST 또는 Cloudflare zone 필요)");
            return;
        }
        proxy.clearOverflow();
        this.warm = false;
        this.appName = job.appName();
        this.host = publicHost;
        this.phase = Phase.STANDBY;
        scheduler.execute(() -> prepareStandby(job, publicHost));
    }

    public Status status() {
        UpstreamProxy.Pressure pressure = proxy.pressure();
        synchronized (events) {
            return new Status(settings.enabled(), phase, appName, host, warm, pressure, List.copyOf(events));
        }
    }

    private void prepareStandby(DeployJob job, String publicHost) {
        try {
            String database = job.database() != null ? job.database() : settings.database();
            String id = client.standby(job, publicHost, database);
            event("standby: cloud build " + id + " host=" + publicHost);
            long deadline = System.currentTimeMillis() + 15 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                BurstClient.BuildState state = client.build(id);
                if ("SUCCEEDED".equals(state.status())) {
                    Consumer<DeployJob> listener = standbyListener;
                    if (listener != null) {
                        listener.accept(job);
                    }
                    standbyReady(System.currentTimeMillis());
                    return;
                }
                if ("FAILED".equals(state.status())) {
                    phase = Phase.OFF;
                    event("standby failed: " + state.lastLog());
                    return;
                }
                Thread.sleep(5_000);
            }
            phase = Phase.OFF;
            event("standby timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            phase = Phase.OFF;
            event("standby error: " + e.getMessage());
        }
    }

    /** 대기 배포가 끝났다 (builder 가 레플리카 0 으로 내려 둔 상태). 대기 Pod 를 띄운다 */
    void standbyReady(long now) {
        if (settings.warmReplicas() < 1) {
            phase = Phase.IDLE;
            event("standby ready: cloud replicas 0");
            return;
        }
        client.scale(appName, settings.warmReplicas());
        phase = Phase.WARMING;
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
                    phase = Phase.IDLE;
                    event("warm: cloud ready, overflow on");
                } else if (now - scalingSince > SCALE_TIMEOUT_MILLIS) {
                    // 대기 Pod 없이도 동작은 한다. 넘길 때 띄우는 방식으로 돌아간다
                    client.scale(appName, 0);
                    phase = Phase.IDLE;
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
                        phase = Phase.OVERFLOWING;
                        quietSince = 0;
                    } else {
                        phase = Phase.SCALING;
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
                    phase = Phase.OVERFLOWING;
                    quietSince = 0;
                    event("overflowing: cloud ready " + app.readyReplicas() + "/" + app.replicas());
                } else if (now - scalingSince > SCALE_TIMEOUT_MILLIS) {
                    client.scale(appName, 0);
                    phase = Phase.IDLE;
                    event("scale up timeout: cloud back to 0");
                }
            }
            case OVERFLOWING -> {
                boolean busy = delta > 0 || p.remoteActive() > 0 || p.localActive() >= p.localLimit();
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
                    phase = Phase.IDLE;
                    event("scale down: quiet " + settings.cooldownSeconds() + "s, cloud replicas "
                            + (warm ? settings.warmReplicas() : 0) + ", overflowed total " + p.overflowedTotal());
                }
            }
            default -> {
                // OFF, STANDBY: 판단하지 않는다
            }
        }
    }

    private boolean cloudReachable() {
        BurstClient.AppState app = client.app(appName);
        return app.readyReplicas() >= 1 && reachesPod.test(ingress(), host);
    }

    private InetSocketAddress ingress() {
        return new InetSocketAddress(settings.ingressHost(), settings.ingressPort());
    }

    private String publicHost(String app) {
        String pattern = settings.publicHost();
        if (pattern != null && !pattern.isBlank()) {
            return pattern.replace("{app}", app).toLowerCase();
        }
        String zone = cloudflare == null ? "" : cloudflare.zoneNameNormalized();
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

    public record Status(boolean enabled, Phase phase, String appName, String publicHost, boolean warm,
                         UpstreamProxy.Pressure pressure, List<String> events) {
    }
}
