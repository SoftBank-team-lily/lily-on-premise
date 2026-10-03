package com.lily.onpremise;

import com.lily.onpremise.burst.CloudBurst;
import com.lily.onpremise.cutover.HomeCutover;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobCancels;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.job.JobStore;
import com.lily.onpremise.pipeline.OnPremPipeline;
import com.lily.onpremise.session.ControlSession;
import com.lily.onpremise.session.JobSink;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 잡 하나를 받아 파이프라인을 돌리고, 단계마다 로컬 저장소와 컨트롤 플레인에 알린다.
 * 이 머신에서는 한 번에 하나의 잡만 슬롯을 만진다.
 */
@Service
public class AgentService implements JobSink {

    private final JobStore store;
    private final OnPremPipeline pipeline;
    private final ControlSession session;
    private final JobRunner runner;
    private final CloudBurst burst;
    private final HomeCutover cutover;
    private final AppRemoval removal;
    private final JobCancels cancels;
    private final Object gate = new Object();

    public AgentService(JobStore store, OnPremPipeline pipeline, ControlSession session, JobRunner runner,
                        CloudBurst burst, HomeCutover cutover, AppRemoval removal, JobCancels cancels) {
        this.cancels = cancels;
        this.store = store;
        this.pipeline = pipeline;
        this.session = session;
        this.runner = runner;
        this.burst = burst;
        this.cutover = cutover;
        this.removal = removal;
        cutover.publisher(this::publish);
    }

    @Override
    public JobRecord accept(DeployJob raw) {
        DeployJob job = raw.normalize();
        if (store.find(job.id()).isPresent()) {
            throw new IllegalArgumentException("이미 받은 잡입니다: " + job.id());
        }
        JobRecord record = new JobRecord(job);
        record.update(JobRecord.Status.QUEUED, "queued: " + job.repoUrl() + " branch=" + job.branch());
        publish(record);
        runner.run(() -> {
            synchronized (gate) {
                cancels.begin(job.id());
                try {
                    pipeline.execute(record, job, this::publish);
                } finally {
                    cancels.end(job.id());
                }
            }
            if (record.getStatus() == JobRecord.Status.SUCCEEDED) {
                // 클라우드 버스팅이 켜져 있으면 같은 앱을 클라우드에 대기 배포한다
                burst.onDeployed(job);
            }
        });
        return record;
    }

    @Override
    public JobRecord rollback(String app, String id) {
        if (!cutover.localRollbackAllowed()) {
            throw new IllegalArgumentException("거점이 클라우드입니다. 반대 방향 전환을 호출하세요");
        }
        if (app == null || !app.matches("[a-z][a-z0-9-]{0,30}")) {
            throw new IllegalArgumentException("app 이 올바르지 않습니다");
        }
        String idValue = id == null || id.isBlank()
                ? "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 7)
                : id;
        if (!idValue.matches("[a-z0-9][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("id 가 올바르지 않습니다");
        }
        if (store.find(idValue).isPresent()) {
            throw new IllegalArgumentException("이미 받은 잡입니다: " + idValue);
        }
        JobRecord record = new JobRecord(idValue, app);
        record.update(JobRecord.Status.QUEUED, "rollback: " + app);
        publish(record);
        runner.run(() -> {
            synchronized (gate) {
                pipeline.rollback(record, app, this::publish);
            }
        });
        return record;
    }

    @Override
    public HomeCutover.Status home(String app, String target, String id, boolean migrateDatabase) {
        if (app == null || !app.matches("[a-z][a-z0-9-]{0,30}")) {
            throw new IllegalArgumentException("app 이 올바르지 않습니다");
        }
        String idValue = id == null || id.isBlank()
                ? "h" + UUID.randomUUID().toString().replace("-", "").substring(0, 7)
                : id;
        if (!idValue.matches("[a-z0-9][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("id 가 올바르지 않습니다");
        }
        if (store.find(idValue).isPresent()) {
            throw new IllegalArgumentException("이미 받은 잡입니다: " + idValue);
        }
        HomeCutover.Status status = cutover.begin(app, target, migrateDatabase);
        if (status.already()) {
            return status;
        }
        JobRecord record = new JobRecord(idValue, app);
        record.update(JobRecord.Status.QUEUED, "home: " + target);
        publish(record);
        runner.run(() -> {
            synchronized (gate) {
                try {
                    cutover.perform();
                    record.update(JobRecord.Status.SUCCEEDED, "home: " + cutover.status().phase());
                } catch (RuntimeException e) {
                    String message = e.getMessage() == null ? "home failed" : e.getMessage();
                    record.update(JobRecord.Status.FAILED, "failed: " + message);
                }
                publish(record);
            }
        });
        return status;
    }

    @Override
    public JobRecord remove(String app, String id, boolean database) {
        if (app == null || !app.matches("[a-z][a-z0-9-]{0,30}")) {
            throw new IllegalArgumentException("app 이 올바르지 않습니다");
        }
        String idValue = id == null || id.isBlank()
                ? "d" + UUID.randomUUID().toString().replace("-", "").substring(0, 7)
                : id;
        if (!idValue.matches("[a-z0-9][a-z0-9-]{0,40}")) {
            throw new IllegalArgumentException("id 가 올바르지 않습니다");
        }
        if (store.find(idValue).isPresent()) {
            throw new IllegalArgumentException("이미 받은 잡입니다: " + idValue);
        }
        JobRecord record = new JobRecord(idValue, app);
        record.update(JobRecord.Status.QUEUED, "remove: " + app + (database ? " database" : ""));
        publish(record);
        runner.run(() -> {
            // 배포·롤백·거점 전환과 같은 잠금. 도는 중이면 끝난 뒤에 지운다
            synchronized (gate) {
                try {
                    record.update(JobRecord.Status.SUCCEEDED, "removed: " + String.join(", ", removal.remove(app, database)));
                } catch (RuntimeException e) {
                    String message = e.getMessage() == null ? "remove failed" : e.getMessage();
                    record.update(JobRecord.Status.FAILED, "failed: " + message);
                }
                publish(record);
            }
        });
        return record;
    }

    @Override
    public boolean cancel(String id) {
        return cancels.request(id);
    }

    public HomeCutover.Status homeStatus() {
        return cutover.status();
    }

    public Optional<JobRecord> get(String id) {
        return store.find(id);
    }

    public List<JobRecord> history() {
        return store.findAll();
    }

    private void publish(JobRecord record) {
        store.save(record);
        session.report(record);
    }

    /** 테스트에서 동기로 돌릴 수 있게 비동기 실행만 분리한다 */
    @Service
    public static class JobRunner {
        @Async
        public void run(Runnable task) {
            task.run();
        }
    }
}
