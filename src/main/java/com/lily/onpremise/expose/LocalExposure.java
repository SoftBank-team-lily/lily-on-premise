package com.lily.onpremise.expose;

import com.lily.onpremise.AgentIdentity;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/** 온보딩에서 프록시와 터널을 한 번 연다. 이후 배포는 {@link #route} 만 호출한다. */
public final class LocalExposure implements TrafficSwitch, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalExposure.class);

    private final UpstreamProxy proxy;
    private final CloudflareLauncher cloudflare;
    private final AgentProperties.Cloudflare settings;
    private final AgentIdentity identity;
    private final CloudflareHostnameProvisioner hostnames;
    private final AtomicBoolean opened = new AtomicBoolean();
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
        if (settings != null && settings.apiConfigured()) {
            String tunnelToken = hostnames.openTunnel(identity.id());
            cloudflare.runWithToken(tunnelToken);
            publicUrl = "";
            log.info("tunnel ready. hostname is attached per app");
            return;
        }
        publicUrl = cloudflare.launch(proxy.port());
        log.info("exposure ready: {}", publicUrl);
    }

    public void publish(String url) {
        this.publicUrl = url;
    }

    public int listenPort() {
        return proxy.port();
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
