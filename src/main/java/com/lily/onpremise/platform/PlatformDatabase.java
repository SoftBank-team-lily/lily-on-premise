package com.lily.onpremise.platform;

import com.lily.onpremise.burst.DatabaseTunnel;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.system.Commands;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 개인키를 배포하지 않는 DB 터널. 에이전트가 이 머신에서 키쌍을 만들고 공개키를 hello 로 보내면,
 * 컨트롤 플레인이 SSH CA 로 서명한 단기 인증서를 welcome 으로 돌려준다.
 * 배스천은 CA 를 {@code cert-authority} 로 믿고, RDS 포트 포워딩만 허용한다.
 *
 * <p>DB 계정은 컨트롤 플레인이 잡의 {@code databaseEnv} 에 실어 보낸다 (터널 주소 기준).
 */
public final class PlatformDatabase implements DatabaseAccess {

    private final Commands commands;
    private final AgentProperties.Database defaults;
    private final Path key;
    private volatile DatabaseTunnel tunnel;

    public PlatformDatabase(Commands commands, AgentProperties.Database defaults, Path workDir) {
        this.commands = commands;
        this.defaults = defaults;
        this.key = workDir.resolve("platform_key");
    }

    /** hello 에 싣는 공개키. 없으면 만든다 */
    public synchronized String publicKey() {
        Path pub = Path.of(key + ".pub");
        try {
            if (!Files.isRegularFile(key) || !Files.isRegularFile(pub)) {
                Files.createDirectories(key.getParent());
                Files.deleteIfExists(key);
                Files.deleteIfExists(pub);
                commands.run(List.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "lily-agent",
                        "-f", key.toString()), key.getParent());
            }
            return Files.readString(pub).trim();
        } catch (IOException e) {
            throw new IllegalStateException("DB 터널 키를 만들지 못했습니다: " + e.getMessage(), e);
        }
    }

    /** welcome 의 인증서와 배스천 주소로 터널을 준비한다. 다시 받으면 인증서만 바꾼다 (열린 터널은 그대로) */
    public synchronized void configure(String sshHost, String sshUser, String remoteHost, int remotePort,
                                       String certificate) {
        Path cert = Path.of(key + "-cert.pub");
        try {
            Path temp = Files.createTempFile(key.getParent(), "cert", ".tmp");
            Files.writeString(temp, certificate.trim() + "\n");
            Files.move(temp, cert, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("DB 터널 인증서를 저장하지 못했습니다: " + e.getMessage(), e);
        }
        AgentProperties.Database settings = new AgentProperties.Database(
                sshHost, sshUser, key.toString(), remoteHost, remotePort, defaults.bindHost(), defaults.bindPort());
        if (tunnel == null) {
            tunnel = new DatabaseTunnel(settings, commands, key.getParent());
        }
    }

    public String bindHost() {
        return defaults.bindHost();
    }

    public int bindPort() {
        return defaults.bindPort();
    }

    @Override
    public boolean ready() {
        return tunnel != null;
    }

    @Override
    public Map<String, String> prepare(DeployJob job) {
        DatabaseTunnel current = tunnel;
        if (current == null) {
            throw new IllegalStateException("컨트롤 플레인이 DB 터널 인증서를 주지 않았습니다");
        }
        if (job.databaseEnv() == null || job.databaseEnv().isEmpty()) {
            throw new IllegalStateException("컨트롤 플레인이 DB 접속 정보를 보내지 않았습니다");
        }
        current.ensure();
        return job.databaseEnv();
    }
}
