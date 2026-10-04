package com.lily.onpremise.cutover;

import com.lily.onpremise.burst.BurstClient;
import com.lily.onpremise.database.DatabaseModes;
import com.lily.onpremise.database.DatabaseTransfer;
import com.lily.onpremise.expose.UpstreamProxy;
import com.lily.onpremise.job.DeployJob;

import java.util.Map;
import java.util.function.Supplier;

/**
 * RDS 접속 정보는 builder(/api/burst/apps/{app}/database, 플랫폼 연결이면 소켓 중계)가 RDS 터널 주소 기준으로 주고,
 * 터널은 cloud 잡과 같은 길({@link DatabaseModes#cloud()})로 연다. 복사는 {@link DatabaseTransfer}, 쓰기 멈춤은 프록시 점검이다.
 */
public final class AgentDatabaseMove implements DatabaseMove {

    private static final long DRAIN_MILLIS = 30_000;

    private final Supplier<BurstClient> client;
    private final DatabaseModes databases;
    private final DatabaseTransfer transfer;
    private final UpstreamProxy proxy;
    private final String tunnelHost;
    private final int tunnelPort;

    /**
     * @param tunnelHost RDS 터널이 이 PC 에서 듣는 주소 (앱 컨테이너와 lily-postgres 가 붙는 곳)
     */
    public AgentDatabaseMove(Supplier<BurstClient> client, DatabaseModes databases, DatabaseTransfer transfer,
                             UpstreamProxy proxy, String tunnelHost, int tunnelPort) {
        this.client = client;
        this.databases = databases;
        this.transfer = transfer;
        this.proxy = proxy;
        this.tunnelHost = tunnelHost;
        this.tunnelPort = tunnelPort;
    }

    @Override
    public Map<String, String> rdsEnv(DeployJob job) {
        BurstClient current = client.get();
        if (current == null) {
            throw new IllegalStateException("builder 연결이 없어 RDS 를 준비할 수 없습니다");
        }
        // 이 PC DB 가 pgroll 을 쓰면 진행 중인 마이그레이션을 끝내 두고, RDS 에도 pgroll 을 켜 달라고 한다
        boolean pgroll = databases.prepareLocalPgrollCopy(job.appName());
        Map<String, String> env = current.database(job.appName(), "postgres", tunnelHost, tunnelPort, pgroll);
        if (env.isEmpty()) {
            throw new IllegalStateException("builder 가 RDS 접속 정보를 주지 않았습니다");
        }
        databases.cloud().prepare(job.withDatabase("cloud", env, false));
        return env;
    }

    @Override
    public void toRemote(String appName, Map<String, String> rdsEnv) {
        transfer.toRemote(appName, DatabaseTransfer.fromEnv(rdsEnv), databases.prepareLocalPgrollCopy(appName));
    }

    @Override
    public void backupToRemote(String appName, Map<String, String> rdsEnv) {
        transfer.backupToRemote(appName, DatabaseTransfer.fromEnv(rdsEnv), databases.prepareLocalPgrollCopy(appName));
    }

    @Override
    public void pause(boolean paused) {
        proxy.pause(paused);
        if (!paused) {
            return;
        }
        long deadline = System.currentTimeMillis() + DRAIN_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            UpstreamProxy.Pressure p = proxy.pressure();
            if (p.localActive() == 0 && p.remoteActive() == 0) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
