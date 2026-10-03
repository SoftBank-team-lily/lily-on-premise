package com.lily.onpremise.burst;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.pipeline.DatabaseAccess;
import com.lily.onpremise.schema.pgroll.PgrollSet;

import java.util.Map;

/**
 * 클라우드와 같은 DB 를 온프레미스 앱에 붙인다.
 * 터널을 열고, lily-builder 를 거쳐 프로비저너에서 DB(projectId = appName)를 찾거나 만든 뒤 터널 주소로 접속 정보를 받는다.
 * 클라우드 배포(lily-cicd)도 같은 projectId 라 같은 DB 를 받는다.
 */
public class CloudDatabase implements DatabaseAccess {

    private final DatabaseTunnel tunnel;
    private final BurstClient client;

    public CloudDatabase(DatabaseTunnel tunnel, BurstClient client) {
        this.tunnel = tunnel;
        this.client = client;
    }

    @Override
    public Map<String, String> prepare(DeployJob job) {
        tunnel.ensure();
        // pgroll 마이그레이션을 받는 잡이면 RDS 에 pgroll 을 켜 달라고 한다 (관리자 권한은 builder 쪽에만 있다)
        return client.database(job.appName(), job.database(), tunnel.host(), tunnel.port(), pgroll(job));
    }

    private static boolean pgroll(DeployJob job) {
        try {
            return "postgres".equals(job.database()) && PgrollSet.isPgroll(job.migrations());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void resume(Map<String, String> appEnv) {
        tunnel.resumeFor(appEnv);
    }
}
