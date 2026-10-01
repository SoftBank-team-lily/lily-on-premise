package com.lily.onpremise.expose;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 온보딩에서 프록시와 터널을 한 번 연다. 이후 배포는 {@link #route} 만 호출한다.
 *
 * <p>플랫폼 연결(컨트롤 플레인 주소만 있고 Cloudflare 자격이 없음)이면 터널은 컨트롤 플레인의 welcome 을 받은 뒤
 * {@link #attachPlatform} 으로 연다. welcome 이 오지 않으면 quick tunnel 로 연다.
 */
public final class LocalExposure implements TrafficSwitch, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalExposure.class);
    private static final long PLATFORM_WAIT_MILLIS = 60_000;

    private final UpstreamProxy proxy;
    private final CloudflareLauncher cloudflare;
    private final AgentProperties.Cloudflare settings;
    private final AgentIdentity identity;
    private final CloudflareHostnameProvisioner hostnames;
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean tunnelStarted = new AtomicBoolean();
    private final boolean platform;
    private volatile String publicUrl = "";

    public LocalExposure(
            AgentProperties properties,
            Commands commands,
            AgentIdentity identity,
            CloudflareHostnameProvisioner hostnames) {
        this.proxy = new UpstreamProxy(properties.proxyPort());
        this.settings = properties.cloudflare();
        this.identity = identity;
        this.hostnames = hostnames;
        this.platform = properties.platformExposure();
        this.cloudflare = new CloudflareLauncher(
                commands,
                settings != null && settings.enabled(),
                settings == null ? "" : settings.token(),
                properties.publicUrl());
    }

    public void open() {
        if (!opened.compareAndSet(false, true)) {
            return;
        }
        proxy.start();
        if (settings != null && settings.partialApi()) {
            throw new IllegalStateException(
                    "CLOUDFLARE_API_TOKEN, CLOUDFLARE_ACCOUNT_ID, CLOUDFLARE_ZONE_ID, CLOUDFLARE_ZONE_NAME 은 함께 있어야 합니다");
        }
        if (platform) {
            Thread.ofVirtual().name("lily-tunnel-fallback").start(() -> {
                try {
                    Thread.sleep(PLATFORM_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!tunnelStarted.get()) {
                    log.warn("control plane did not configure the tunnel in time. using quick tunnel");
                    quickTunnel();
                }
            });
            log.info("proxy ready. tunnel waits for the control plane");
            return;
        }
        if (settings != null && settings.apiConfigured()) {
            tunnelStarted.set(true);
            String tunnelToken = hostnames.openTunnel(identity.id());
            cloudflare.runWithToken(tunnelToken);
            publicUrl = "";
            log.info("tunnel ready. hostname is attached per app");
            return;
        }
        tunnelStarted.set(true);
        publicUrl = cloudflare.launch(proxy.port());
        log.info("exposure ready: {}", publicUrl);
    }

    /**
     * 컨트롤 플레인이 플랫폼 존을 주면 그 존에 이 에이전트의 터널을 만들고 연다 (API 호출은 컨트롤 플레인이 한다).
     * 존이 없거나 터널을 열지 못하면 quick tunnel 로 연다. 처음 한 번만 동작한다.
     *
     * @param zone null 이면 플랫폼 존 없음. 값은 {accountId, zoneId, zoneName}
     */
    public void attachPlatform(String tunnelAgentId, String[] zone) {
        if (!platform || !tunnelStarted.compareAndSet(false, true)) {
            return;
        }
        String[] ids = zone;
        if (ids != null) {
            try {
                hostnames.configure(ids[0], ids[1], ids[2]);
                String tunnelToken = hostnames.openTunnel(tunnelAgentId);
                cloudflare.runWithToken(tunnelToken);
                publicUrl = "";
                log.info("platform tunnel ready. hostname is attached per app");
                return;
            } catch (RuntimeException e) {
                hostnames.configure("", "", "");
                log.warn("platform tunnel failed, using quick tunnel: {}", e.getMessage());
            }
        }
        publicUrl = cloudflare.launch(proxy.port());
        log.info("exposure ready: {}", publicUrl);
    }

    private void quickTunnel() {
        if (!tunnelStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            publicUrl = cloudflare.launch(proxy.port());
            log.info("exposure ready: {}", publicUrl);
        } catch (RuntimeException e) {
            log.warn("quick tunnel failed: {}", e.getMessage());
        }
    }

    public void publish(String url) {
        this.publicUrl = url;
    }

    public int listenPort() {
        return proxy.port();
    }

    /** 클라우드 버스팅이 연결 수를 보고 넘김 대상을 켜고 끈다 */
    public UpstreamProxy proxy() {
        return proxy;
    }

    @Override
    public void route(int upstreamPort) {
        proxy.switchTo(upstreamPort);
    }

    @Override
    public int upstreamPort() {
        return proxy.upstreamPort();
    }

    @Override
    public String publicUrl() {
        return publicUrl;
    }

    @Override
    public void close() {
        proxy.close();
    }
}
