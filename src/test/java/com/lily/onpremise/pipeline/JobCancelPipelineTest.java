package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobCancels;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.schema.SchemaApply;
import com.lily.onpremise.expose.CandidateJudge;
import com.lily.onpremise.system.Commands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** 컨트롤 플레인의 배포 취소: 트래픽을 새 슬롯으로 바꾸기 전까지만 받고, 띄운 후보는 지운다 */
class JobCancelPipelineTest {

    @TempDir
    Path root;

    private final List<Thread> interrupted = new ArrayList<>();
    private final JobCancels cancels = new JobCancels(new Commands() {
        @Override
        public void run(List<String> command, Path workDir) {
        }

        @Override
        public void start(List<String> command, Consumer<String> lines) {
        }

        @Override
        public void close() {
        }

        @Override
        public void interrupt(Thread owner) {
            interrupted.add(owner);
        }
    });
    private final Runtime runtime = new Runtime();
    private final OnPremPipelineTest.FakeSwitch traffic = new OnPremPipelineTest.FakeSwitch();
    private Readiness ready = (port, path) -> { };
    private PublicAddress addresses = app -> "https://blog.lilycloud.kr";
    private int checkouts;

    @BeforeEach
    void repo() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
    }

    private JobRecord run(DeployJob job) {
        OnPremPipeline pipeline = new OnPremPipeline(
                ignored -> {
                    checkouts++;
                    return root;
                },
                new StackAnalyzer(), runtime, ready, traffic, addresses, new SlotBook(), 18080, 18081,
                DatabaseAccess.NONE, SchemaApply.NONE, CandidateJudge.PASS, DeployedApp.IGNORE, PgrollStep.NONE,
                cancels);
        JobRecord record = new JobRecord(job);
        cancels.begin(job.id());
        try {
            pipeline.execute(record, job, ignored -> { });
        } finally {
            cancels.end(job.id());
        }
        return record;
    }

    private static DeployJob job() {
        return new DeployJob("job1", "https://github.com/acme/blog.git", "main", null, "blog", 8080,
                null, null, null, Map.of()).normalize();
    }

    @Test
    void docker_build_중에_취소하면_빌드_프로세스를_끊고_후보를_띄우지_않고_CANCELLED() {
        runtime.onBuild = () -> {
            assertThat(cancels.request("job1")).isTrue();
            throw new IllegalStateException("docker build → 137");
        };

        JobRecord record = run(job());

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.CANCELLED);
        assertThat(record.lastLine()).startsWith("cancelled: ");
        assertThat(interrupted).containsExactly(Thread.currentThread());
        assertThat(runtime.calls).containsExactly("build");
        assertThat(traffic.port).isEqualTo(-1);
    }

    @Test
    void 헬스_대기_중에_취소하면_후보를_지우고_프록시는_그대로이고_CANCELLED() {
        ready = (port, path) -> {
            cancels.request("job1");
            throw new IllegalStateException("interrupted");
        };

        JobRecord record = run(job());

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.CANCELLED);
        assertThat(record.lastLine()).contains("traffic unchanged");
        assertThat(runtime.calls).containsExactly("build", "start blog-blue", "stop blog-blue");
        assertThat(traffic.port).isEqualTo(-1);
    }

    @Test
    void 전환_직전에_취소가_오면_후보를_지우고_프록시는_그대로이고_CANCELLED() {
        addresses = app -> {
            cancels.request("job1");
            return "https://blog.lilycloud.kr";
        };

        JobRecord record = run(job());

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.CANCELLED);
        assertThat(record.lastLine()).contains("candidate removed");
        assertThat(runtime.calls).containsExactly("build", "start blog-blue", "stop blog-blue");
        assertThat(traffic.port).isEqualTo(-1);
    }

    @Test
    void 트래픽을_바꾼_뒤에_온_취소는_거절하고_SUCCEEDED_그대로() {
        JobRecord record = run(job());

        assertThat(cancels.request("job1")).isFalse();
        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(traffic.port).isEqualTo(18080);
    }

    @Test
    void 잡이_오기_전에_받은_취소면_체크아웃도_하지_않고_CANCELLED() {
        cancels.request("job1");

        JobRecord record = run(job());

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.CANCELLED);
        assertThat(checkouts).isZero();
        assertThat(runtime.calls).isEmpty();
    }

    @Test
    void 잡이_끝나면_잡_스레드에_남은_interrupt_표시를_지운다() {
        ready = (port, path) -> Thread.currentThread().interrupt();

        run(job());

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static final class Runtime implements ContainerRuntime {
        final List<String> calls = new ArrayList<>();
        Runnable onBuild = () -> { };

        @Override
        public void build(Path context, String image, boolean generated) {
            calls.add("build");
            onBuild.run();
        }

        @Override
        public void start(String name, String image, int hostPort, int containerPort, Map<String, String> env) {
            calls.add("start " + name);
        }

        @Override
        public void stop(String name) {
            calls.add("stop " + name);
        }
    }
}
