package com.lily.onpremise.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 이 PC 의 DB 를 클라우드에 연다 (역방향 터널).
 * {@code ssh -N -R {reverseHost}:{reversePort}:{db host}:{db port} lily-tunnel@{배스천}}
 *
 * <p>배스천은 인증서 key ID({@code agent-{key}-p{port}})에 적힌 포트 하나만 이 에이전트에게 열어 준다.
 * 클라우드 대기 Pod 는 {@code reverseHost:reversePort} 로 붙는다. 데이터는 이 PC 에 남고 쿼리만 터널을 지난다.
 *
 * <p>끊기면 5초 뒤 다시 연다. 대상 DB 가 바뀌면(앱의 DB 위치를 바꿔 다시 배포) 새로 연다.
 */
public final class ReverseTunnel implements AutoCloseable {

    /**
     * @param key 인증서({@code {key}-cert.pub})가 옆에 있는 개인키
     */
    public record Settings(String sshHost, String sshUser, Path key, Path knownHosts,
                           String reverseHost, int reversePort) {
    }

    private static final Logger log = LoggerFactory.getLogger(ReverseTunnel.class);

    private volatile Settings settings;
    private volatile String targetHost;
    private volatile int targetPort;
    private volatile Process process;
    private volatile boolean closed;
    private final Thread supervisor;

    public ReverseTunnel() {
        supervisor = Thread.ofVirtual().name("lily-reverse-tunnel").start(this::supervise);
    }

    /** welcome 으로 받은 배스천·포트. 다시 받으면 바뀐 경우에만 다시 연다 */
    public synchronized void configure(Settings next) {
        if (Objects.equals(settings, next)) {
            return;
        }
        settings = next;
        restart();
    }

    public Settings settings() {
        return settings;
    }

    /** 클라우드에 열 DB. 같은 대상이고 살아 있으면 그대로 둔다 */
    public synchronized void point(String host, int port) {
        if (host.equals(targetHost) && port == targetPort && alive()) {
            return;
        }
        targetHost = host;
        targetPort = port;
        restart();
    }

    private boolean alive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    private synchronized void restart() {
        Process previous = process;
        process = null;
        if (previous != null) {
            previous.destroy();
        }
        Settings s = settings;
        if (closed || s == null || targetHost == null) {
            return;
        }
        String forward = s.reverseHost() + ":" + s.reversePort() + ":" + targetHost + ":" + targetPort;
        List<String> command = List.of("ssh", "-N",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=3",
                "-o", "StrictHostKeyChecking=accept-new",
                "-o", "UserKnownHostsFile=" + s.knownHosts(),
                "-o", "BatchMode=yes",
                "-i", s.key().toString(),
                "-R", forward,
                s.sshUser() + "@" + s.sshHost());
        try {
            Process started = new ProcessBuilder(command).redirectErrorStream(true).start();
            process = started;
            Thread.ofVirtual().name("lily-reverse-tunnel-log").start(() -> drain(started));
            log.info("reverse tunnel: {}:{} -> {}:{}", s.reverseHost(), s.reversePort(), targetHost, targetPort);
        } catch (IOException e) {
            log.warn("reverse tunnel not started: {}", e.getMessage());
        }
    }

    private void supervise() {
        while (!closed) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                if (!closed && settings != null && targetHost != null && !alive()) {
                    restart();
                }
            }
        }
    }

    private static void drain(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("reverse tunnel: {}", line);
            }
        } catch (IOException ignored) {
            // 프로세스를 끊으면 읽기가 끝난다
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        supervisor.interrupt();
        Process current = process;
        if (current != null) {
            current.destroyForcibly();
        }
    }
}
