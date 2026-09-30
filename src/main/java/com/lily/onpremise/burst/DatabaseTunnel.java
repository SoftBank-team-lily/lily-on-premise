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

/**
 * 온프레미스 → 클라우드 RDS SSH 포트포워딩.
 * {@code ssh -N -L {bindHost}:{bindPort}:{remoteHost}:{remotePort} {user}@{sshHost}}
 * 배스천 계정은 authorized_keys 에서 RDS 포트로의 포워딩만 허용된다.
 */
public class DatabaseTunnel {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTunnel.class);

    private final AgentProperties.Database settings;
    private final Commands commands;
    private final Path knownHosts;

    public DatabaseTunnel(AgentProperties.Database settings, Commands commands, Path workDir) {
        this.settings = settings;
        this.commands = commands;
        this.knownHosts = workDir.resolve("known_hosts");
    }

    /** 터널이 열려 있지 않으면 띄우고, 로컬 포트가 열릴 때까지 기다린다 */
    public synchronized void ensure() {
        if (open()) {
            return;
        }
        String forward = settings.bindHost() + ":" + settings.bindPort() + ":"
                + settings.remoteHost() + ":" + settings.remotePort();
        commands.start(List.of("ssh", "-N",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
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
