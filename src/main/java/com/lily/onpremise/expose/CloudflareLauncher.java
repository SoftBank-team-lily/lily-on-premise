package com.lily.onpremise.expose;

import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 터널은 프록시 포트에만 붙인다. 배포마다 다시 열지 않는다.
 * 토큰이 있으면 named tunnel 이고, 공개 주소는 설정값이다.
 * 토큰이 없으면 quick tunnel 이고, 주소는 cloudflared 출력에서 읽는다.
 */
public final class CloudflareLauncher {

    private static final Logger log = LoggerFactory.getLogger(CloudflareLauncher.class);
    private static final Pattern QUICK = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    private final Commands commands;
    private final boolean enabled;
    private final String token;
    private final String configuredPublicUrl;
    private volatile String activeToken = "";

    public CloudflareLauncher(Commands commands, boolean enabled, String token, String configuredPublicUrl) {
        this.commands = commands;
        this.enabled = enabled;
        this.token = token == null ? "" : token;
        this.configuredPublicUrl = configuredPublicUrl == null ? "" : configuredPublicUrl;
    }

    public String launch(int proxyPort) {
        if (!enabled) {
            return "http://127.0.0.1:" + proxyPort;
        }
        String origin = "http://127.0.0.1:" + proxyPort;
        if (!token.isBlank()) {
            if (configuredPublicUrl.isBlank()) {
                throw new IllegalStateException("CLOUDFLARE_TUNNEL_TOKEN 을 쓰면 PUBLIC_URL 이 필요합니다");
            }
            return runWithToken(token, configuredPublicUrl);
        }
        AtomicReference<String> found = new AtomicReference<>();
        commands.start(
                List.of("cloudflared", "tunnel", "--no-autoupdate", "--url", origin),
                line -> {
                    logLine(line);
                    Matcher matcher = QUICK.matcher(line);
                    if (matcher.find()) {
                        found.compareAndSet(null, matcher.group());
                    }
                });
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (found.get() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (found.get() == null) {
            throw new IllegalStateException("cloudflared 가 공개 주소를 주지 않았습니다");
        }
        return found.get();
    }

    /** API 가 발급한 터널 토큰으로 cloudflared 를 띄운다. 공개 주소는 호스트이름 호출이 정한다. */
    public void runWithToken(String tunnelToken) {
        runWithToken(tunnelToken, "");
    }

    public String runWithToken(String tunnelToken, String publicUrl) {
        this.activeToken = tunnelToken;
        commands.start(List.of("cloudflared", "tunnel", "--no-autoupdate", "run", "--token", tunnelToken), this::logLine);
        return publicUrl;
    }

    private void logLine(String line) {
        String safe = line;
        if (!token.isBlank()) {
            safe = safe.replace(token, "***");
        }
        if (activeToken != null && !activeToken.isBlank()) {
            safe = safe.replace(activeToken, "***");
        }
        log.info("cloudflared: {}", safe);
    }
}
