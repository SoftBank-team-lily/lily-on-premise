package com.lily.onpremise;

import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.job.JobStore;
import com.lily.onpremise.pipeline.OnPremPipeline;
import com.lily.onpremise.session.ControlSession;
import com.lily.onpremise.session.JobSink;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

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
    private final Object gate = new Object();

    public AgentService(JobStore store, OnPremPipeline pipeline, ControlSession session, JobRunner runner) {
        this.store = store;
        this.pipeline = pipeline;
        this.session = session;
        this.runner = runner;
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
                pipeline.execute(record, job, this::publish);
            }
        });
        return record;
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
