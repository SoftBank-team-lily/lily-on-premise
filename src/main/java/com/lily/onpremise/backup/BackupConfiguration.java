package com.lily.onpremise.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.cutover.DatabaseMove;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.expose.LocalExposure;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.runtime.ContainerStats;
import com.lily.onpremise.schema.pgroll.PgrollWindows;
import com.lily.onpremise.system.Commands;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 정기 백업을 에이전트에 붙인다. 대상은 이 PC 에 마지막으로 배포한 HYBRID 앱 중 DB 가 이 PC(local, postgres)인 앱이다.
 * 사본은 거점 전환과 같은 길({@link DatabaseMove#rdsEnv} → {@link DatabaseMove#toRemote})로 올린다.
 */
@Configuration
public class BackupConfiguration {

    static final String RESTARTED = "에이전트가 다시 떠서 배포 정보가 없다 (한 번 다시 배포하면 백업을 이어 간다)";

    @Bean(destroyMethod = "close")
    BackupScheduler backupScheduler(BackupProperties properties, AgentProperties agent, HomeCutover homes,
                                    LocalExposure exposure, ContainerStats stats, Commands commands, ObjectMapper json) {
        Path root = workspace(agent);
        LocalDbStats db = new LocalDbStats(commands);
        PgrollWindows windows = new PgrollWindows(root, json);
        Executor dumps = task -> Thread.ofVirtual().name("lily-backup-dump").start(task);
        return new BackupScheduler(properties.policy(), new BackupStore(root, json),
                () -> deployed(homes, windows),
                () -> exposure.proxy().localTraffic().requests(),
                new PcLoad(() -> stats.usage().map(ContainerStats.Usage::cpuPercent), db),
                new ChangeCounter(db),
                app -> dump(homes, app),
                dumps,
                Clock.systemUTC());
    }

    @Bean
    ApplicationRunner startBackupScheduler(BackupScheduler scheduler, BackupProperties properties) {
        return args -> {
            if (properties.intervalHours() > 0) {
                scheduler.start();
            }
        };
    }

    static BackupScheduler.Deployed deployed(HomeCutover homes, PgrollWindows windows) {
        DeployJob job = homes.job();
        boolean moving = homes.moving();
        boolean onPc = !moving && homes.localRollbackAllowed();
        if (job == null) {
            // 배포 잡은 메모리에만 있다 (git 토큰이 들어 있어 남기지 않는다). 에이전트가 다시 뜨면 기억한 앱 이름으로
            // 요청 수만 이어 세고, 백업은 다음 배포부터 한다
            String remembered = homes.status().appName();
            return remembered == null || remembered.isBlank() ? null
                    : new BackupScheduler.Deployed(remembered, onPc, RESTARTED);
        }
        return new BackupScheduler.Deployed(job.appName(), onPc, skip(job, moving, onPc, homes, windows));
    }

    /** 백업하지 않는 이유. 대상이면 null */
    static String skip(DeployJob job, boolean moving, boolean onPc, HomeCutover homes, PgrollWindows windows) {
        if (job.onPremOnly()) {
            return "ONPREM_ONLY 앱은 백업하지 않는다";
        }
        if (!"local".equals(job.databaseModeOrDefault())) {
            return "DB 가 이 PC 에 없다 (" + job.databaseModeOrDefault() + ")";
        }
        if (!"postgres".equals(job.database())) {
            return "postgres 만 백업한다";
        }
        if (moving) {
            return "거점을 옮기는 중";
        }
        if (!onPc) {
            return "거점이 클라우드다";
        }
        if (homes.databaseMove() == null || homes.client() == null) {
            return "builder 연결이 없어 사본 DB 를 받을 수 없다";
        }
        // 사본 DB 를 받을 때 진행 중인 pgroll 마이그레이션을 complete 한다. 롤백 창을 닫지 않게 기다린다
        if (windows.find(job.appName()).isPresent()) {
            return "스키마 변경 롤백 창이 열려 있다";
        }
        return null;
    }

    private static void dump(HomeCutover homes, String app) {
        DeployJob job = homes.job();
        DatabaseMove move = homes.databaseMove();
        if (job == null || !app.equals(job.appName()) || move == null) {
            throw new IllegalStateException("백업하려던 앱이 바뀌었다: " + app);
        }
        Map<String, String> rds = move.rdsEnv(job);
        move.backupToRemote(app, rds);
    }

    private static Path workspace(AgentProperties agent) {
        String dir = agent.workspace();
        return dir == null || dir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lily-onprem")
                : Path.of(dir);
    }
}
