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
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 클라우드 버스팅 상태 머신. 1초마다 프록시의 연결 수를 보고 판단한다.
 *
 * <pre>
 * 배포 성공 → STANDBY  클라우드에 대기 배포 (레플리카 0) 요청, 빌드가 끝날 때까지
 *          → IDLE     로컬만 처리
 * 로컬이 한도를 scaleUpAfter 초 동안 넘김 → SCALING  클라우드 레플리카 N 으로
 * 클라우드 Ready 1 이상 → OVERFLOWING  넘치는 연결을 클라우드 Ingress 로
 * cooldown 초 동안 한가 → 넘김 끄고 레플리카 0 → IDLE
 * </pre>
 */
@Component
public class CloudBurst {

    private static final Logger log = LoggerFactory.getLogger(CloudBurst.class);
    private static final long SCALE_TIMEOUT_MILLIS = 180_000;
    private static final int EVENT_LIMIT = 30;

    public enum Phase { OFF, STANDBY, IDLE, SCALING, OVERFLOWING }

    private final AgentProperties.Burst settings;
    private final AgentProperties.Cloudflare cloudflare;
    private final LocalExposure exposure;
    private final BurstClient client;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lily-burst");
        t.setDaemon(true);
        return t;
    });
    private final Deque<String> events = new ArrayDeque<>();

    private volatile Phase phase = Phase.OFF;
    private volatile String appName;
    private volatile String host;
    private long lastSaturated;
    private long saturatedSince;
    private long quietSince;
    private long scalingSince;

    public CloudBurst(AgentProperties properties, LocalExposure exposure, ObjectMapper json) {
        this.settings = properties.burst();
        this.cloudflare = properties.cloudflare();
        this.exposure = exposure;
        this.client = settings.enabled() ? new BurstClient(settings, json) : null;
    }

    @PostConstruct
    void start() {
        if (!settings.enabled()) {
            return;
        }
        exposure.proxy().localLimit(settings.localLimit());
        scheduler.scheduleWithFixedDelay(this::safeTick, 1, 1, TimeUnit.SECONDS);
        event("enabled: localLimit=" + settings.localLimit() + " replicas=" + settings.replicas()
                + " target=" + settings.ingressHost() + ":" + settings.ingressPort());
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
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
        exposure.proxy().clearOverflow();
        this.appName = job.appName();
        this.host = publicHost;
        this.phase = Phase.STANDBY;
        scheduler.execute(() -> prepareStandby(job, publicHost));
    }

    public Status status() {
        UpstreamProxy.Pressure pressure = exposure.proxy().pressure();
        synchronized (events) {
            return new Status(settings.enabled(), phase, appName, host, pressure, List.copyOf(events));
        }
    }

    private void prepareStandby(DeployJob job, String publicHost) {
        try {
            String id = client.standby(job, publicHost, settings.database());
            event("standby: cloud build " + id + " host=" + publicHost);
            long deadline = System.currentTimeMillis() + 15 * 60_000L;
            while (System.currentTimeMillis() < deadline) {
                BurstClient.BuildState state = client.build(id);
                if ("SUCCEEDED".equals(state.status())) {
                    phase = Phase.IDLE;
                    event("standby ready: cloud replicas 0");
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

    private void safeTick() {
        try {
            tick();
        } catch (RuntimeException e) {
            event("error: " + e.getMessage());
        }
    }

    void tick() {
        UpstreamProxy proxy = exposure.proxy();
        UpstreamProxy.Pressure p = proxy.pressure();
        long delta = p.saturatedTotal() - lastSaturated;
        lastSaturated = p.saturatedTotal();
        long now = System.currentTimeMillis();

        switch (phase) {
            case IDLE -> {
                // 한도가 찬 채로 새 연결이 오거나, keep-alive 로 연결이 한도만큼 계속 붙어 있으면 과부하
                boolean pressured = delta > 0 || p.localActive() >= p.localLimit();
                if (!pressured) {
                    saturatedSince = 0;
                    return;
                }
                if (saturatedSince == 0) {
                    saturatedSince = now;
                }
                if (now - saturatedSince >= settings.scaleUpAfterSeconds() * 1000L) {
                    client.scale(appName, settings.replicas());
                    phase = Phase.SCALING;
                    scalingSince = now;
                    saturatedSince = 0;
                    event("scale up: local " + p.localActive() + "/" + p.localLimit()
                            + " -> cloud replicas " + settings.replicas());
                }
            }
            case SCALING -> {
                BurstClient.AppState app = client.app(appName);
                if (app.readyReplicas() >= 1) {
                    proxy.overflowTo(new InetSocketAddress(settings.ingressHost(), settings.ingressPort()));
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
                    proxy.clearOverflow();
                    client.scale(appName, 0);
                    phase = Phase.IDLE;
                    event("scale down: quiet " + settings.cooldownSeconds() + "s, overflowed total "
                            + p.overflowedTotal());
                }
            }
            default -> {
                // OFF, STANDBY: 판단하지 않는다
            }
        }
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

    public record Status(boolean enabled, Phase phase, String appName, String publicHost,
                         UpstreamProxy.Pressure pressure, List<String> events) {
    }
}
