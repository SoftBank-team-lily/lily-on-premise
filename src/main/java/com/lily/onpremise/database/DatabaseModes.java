package com.lily.onpremise.database;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.pipeline.DatabaseAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 잡의 DB 위치(databaseMode)에 따라 DB 를 준비한다.
 *
 * <pre>
 * cloud    클라우드 RDS 를 SSH 터널로 (이 필드 전의 동작, {@code cloud} 위임)
 * local    이 PC 에 띄운 DB ({@link LocalDatabase})
 * external 사용자가 준 DB 주소 ({@link ExternalDatabase})
 * </pre>
 *
 * local 잡이 importDatabase 면 이 PC DB 를 만든 뒤 클라우드 RDS(databaseEnv, 터널 주소)의 스키마와 데이터를 옮긴다
 * ({@link DatabaseTransfer#toLocal}). 클라우드 앱을 이 PC 로 옮길 때다.
 *
 * local·external 은 플랫폼이 역방향 터널을 주면 그 DB 를 클라우드에 연다.
 * 클라우드 대기 배포는 {@link #cloudEnv} 의 접속 정보(배스천 주소 기준)를 받는다.
 */
public final class DatabaseModes implements DatabaseAccess {

    private static final Logger log = LoggerFactory.getLogger(DatabaseModes.class);

    private final DatabaseAccess cloud;
    private final LocalDatabase local;
    private final ExternalDatabase external;
    private final ReverseTunnel reverse;
    private volatile DatabaseTransfer transfer;
    /** 앱 → 온프레미스 DB. agent 쪽이 역방향 터널의 대상이고, 계정은 클라우드 대기 Pod 도 같이 쓴다 */
    private final Map<String, ExternalDatabase.Resolved> onPrem = new ConcurrentHashMap<>();

    public DatabaseModes(DatabaseAccess cloud, LocalDatabase local, ExternalDatabase external, ReverseTunnel reverse) {
        this.cloud = cloud;
        this.local = local;
        this.external = external;
        this.reverse = reverse;
    }

    /** RDS 터널 쪽 구현 (플랫폼 인증서, 팀 환경 파일, 또는 없음) */
    public DatabaseAccess cloud() {
        return cloud;
    }

    public ReverseTunnel reverse() {
        return reverse;
    }

    /**
     * 앱을 지울 때 이 PC 의 DB 컨테이너(local)에 있는 앱 DB 와 계정을 지운다.
     * external 은 사용자 DB 라서, cloud(RDS)는 builder 가 지우므로 건드리지 않는다
     */
    public List<String> dropLocal(String appName) {
        onPrem.remove(appName);
        return local.drop(appName);
    }

    @Override
    public void resume(Map<String, String> appEnv) {
        cloud.resume(appEnv);
    }

    /** RDS → 이 PC DB 이전. 없으면 importDatabase 잡을 거절한다 */
    public DatabaseModes transfer(DatabaseTransfer transfer) {
        this.transfer = transfer;
        return this;
    }

    @Override
    public Map<String, String> prepare(DeployJob job) {
        String engine = job.database();
        return switch (job.databaseModeOrDefault()) {
            case "local" -> {
                DatabaseCredentials db = local.prepare(engine, job.appName());
                if (job.importsDatabase()) {
                    importFromCloud(job);
                }
                open(job.appName(), new ExternalDatabase.Resolved(db, db), !job.onPremOnly());
                yield db.env();
            }
            case "external" -> {
                ExternalDatabase.Resolved db = external.resolve(engine, job.databaseUrl());
                external.check(db.agent());
                open(job.appName(), db, !job.onPremOnly());
                yield db.app().env();
            }
            default -> {
                onPrem.remove(job.appName());
                yield cloud.prepare(job);
            }
        };
    }

    /** RDS 터널을 열고 그 DB 를 이 PC DB 로 덮어쓴다. 이전 PC DB 는 백업으로 남는다 */
    private void importFromCloud(DeployJob job) {
        DatabaseTransfer current = transfer;
        if (current == null) {
            throw new IllegalStateException("이 에이전트는 RDS 데이터를 옮길 수 없습니다");
        }
        // cloud 잡과 같은 길로 RDS 터널을 연다. 접속 정보는 컨트롤 플레인이 databaseEnv 로 보낸 것이다
        Map<String, String> rds = cloud.prepare(job);
        String backup = current.toLocal(job.appName(), DatabaseTransfer.fromEnv(rds));
        log.info("imported cloud database into this pc: app={} backup={}", job.appName(), backup);
    }

    /** 에이전트에서 붙을 주소 (스키마 적용). external 이 localhost 면 앱과 에이전트의 주소가 다르다 */
    @Override
    public Map<String, String> agentEnv(DeployJob job, Map<String, String> appEnv) {
        ExternalDatabase.Resolved db = onPrem.get(job.appName());
        if (db == null || "cloud".equals(job.databaseModeOrDefault()) || db.app().equals(db.agent())) {
            return appEnv;
        }
        // 사용자가 env 로 덮은 값은 그대로 두고, 플랫폼이 넣은 값만 에이전트 주소로 바꾼다
        Map<String, String> appSide = db.app().env();
        Map<String, String> agentSide = db.agent().env();
        Map<String, String> env = new LinkedHashMap<>(appEnv);
        appSide.forEach((key, value) -> {
            if (value.equals(env.get(key))) {
                env.put(key, agentSide.get(key));
            }
        });
        return env;
    }

    /** RDS 터널이 있어야 cloud 잡을 받는다. local·external 은 hello 의 databaseModes 로 알린다 */
    @Override
    public boolean ready() {
        return cloud.ready();
    }

    /**
     * 클라우드 대기 Pod 가 이 앱의 온프레미스 DB 에 붙을 접속 정보. 역방향 터널이 없거나 이 앱이 온프레미스 DB 가 아니면 empty
     */
    public Optional<Map<String, String>> cloudEnv(String appName) {
        ExternalDatabase.Resolved db = onPrem.get(appName);
        ReverseTunnel.Settings settings = reverse.settings();
        if (db == null || settings == null) {
            return Optional.empty();
        }
        return Optional.of(db.app().at(settings.reverseHost(), settings.reversePort()).env());
    }

    private void open(String appName, ExternalDatabase.Resolved db, boolean shareWithCloud) {
        onPrem.put(appName, db);
        if (!shareWithCloud) {
            log.info("on-prem only: {} stays on this pc", appName);
            return;
        }
        if (reverse.settings() == null) {
            log.info("reverse tunnel not offered by platform: {} stays local only", appName);
            return;
        }
        reverse.point(db.agent().host(), db.agent().port());
    }
}
