package com.lily.onpremise.burst;

import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.system.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 온프레미스 → 클라우드 RDS SSH 포트포워딩.
 * {@code ssh -N -L {bindHost}:{bindPort}:{remoteHost}:{remotePort} {user}@{sshHost}}
 * 배스천 계정은 authorized_keys 에서 RDS 포트로의 포워딩만 허용된다.
 *
 * <p>한 번 연 뒤에는 {@link #CHECK_MILLIS} 마다 로컬 포트를 보고, 닫혀 있으면 다시 연다
 * (PC 가 잠들었다 깨거나 네트워크가 끊겨 ssh 가 끝나면 다음 배포까지 앱 DB 가 끊겨 있었다).
 */
public class DatabaseTunnel {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTunnel.class);
    static final long CHECK_MILLIS = 5_000;

    private final AgentProperties.Database settings;
    private final Commands commands;
    private final Path knownHosts;
    private final long checkMillis;
    /** ensure 가 한 번 성공한 뒤부터 감시한다 */
    private volatile boolean wanted;
    private Thread supervisor;

    public DatabaseTunnel(AgentProperties.Database settings, Commands commands, Path workDir) {
        this(settings, commands, workDir, CHECK_MILLIS);
    }

    DatabaseTunnel(AgentProperties.Database settings, Commands commands, Path workDir, long checkMillis) {
        this.settings = settings;
        this.commands = commands;
        this.knownHosts = workDir.resolve("known_hosts");
        this.checkMillis = checkMillis;
    }

    /** 터널이 열려 있지 않으면 띄우고, 로컬 포트가 열릴 때까지 기다린다 */
    public synchronized void ensure() {
        if (open()) {
            watch();
            return;
        }
        String forward = settings.bindHost() + ":" + settings.bindPort() + ":"
                + settings.remoteHost() + ":" + settings.remotePort();
        commands.start(List.of("ssh", "-N",
                "-o", "ExitOnForwardFailure=yes",
                // 끊긴 연결을 30초 안에 알아채고 ssh 가 끝나야 감시가 다시 연다 (끝나기 전에는 로컬 포트가 열려 있다)
                "-o", "ServerAliveInterval=10",
                "-o", "ServerAliveCountMax=3",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "UserKnownHostsFile=" + knownHosts,
                "-o", "BatchMode=yes",
                "-i", settings.sshKey(),
                "-L", forward,
                settings.sshUser() + "@" + settings.sshHost()), line -> log.info("db tunnel: {}", line));
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (open()) {
                log.info("db tunnel ready: {}:{} -> {}:{}", settings.bindHost(), settings.bindPort(),
                        settings.remoteHost(), settings.remotePort());
                watch();
                return;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("DB 터널을 열지 못했습니다: " + settings.bindHost() + ":" + settings.bindPort());
    }

    private void watch() {
        wanted = true;
        if (supervisor == null) {
            supervisor = Thread.ofVirtual().name("lily-db-tunnel").start(this::supervise);
        }
    }

    private void supervise() {
        while (true) {
            try {
                Thread.sleep(checkMillis);
            } catch (InterruptedException e) {
                return;
            }
            if (!wanted || open()) {
                continue;
            }
            log.warn("db tunnel closed: {}:{}, reopening", settings.bindHost(), settings.bindPort());
            try {
                ensure();
            } catch (RuntimeException e) {
                log.warn("db tunnel reopen failed: {}", e.getMessage());
            }
        }
    }

    /** 앱 환경변수가 이 터널 주소로 붙는다 */
    public boolean usedBy(Map<String, String> appEnv) {
        String address = settings.bindHost() + ":" + settings.bindPort();
        return appEnv.values().stream().anyMatch(value -> value != null && value.contains(address));
    }

    /** 다시 붙인 앱이 이 터널을 쓰면 뒤에서 연다. 기동을 막지 않는다 */
    public void resumeFor(Map<String, String> appEnv) {
        if (!usedBy(appEnv)) {
            return;
        }
        Thread.ofVirtual().name("lily-db-tunnel-resume").start(() -> {
            try {
                ensure();
            } catch (RuntimeException e) {
                log.warn("db tunnel resume failed: {}", e.getMessage());
            }
        });
    }

    public boolean started() {
        return wanted;
    }

    public String sshHost() {
        return settings.sshHost();
    }

    public String remoteHost() {
        return settings.remoteHost();
    }

    public int remotePort() {
        return settings.remotePort();
    }

    public String host() {
        return settings.bindHost();
    }

    public int port() {
        return settings.bindPort();
    }

    private boolean open() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(settings.bindHost(), settings.bindPort()), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
