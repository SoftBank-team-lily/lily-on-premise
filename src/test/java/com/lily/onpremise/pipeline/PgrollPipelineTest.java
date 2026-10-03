package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.CandidateJudge;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.schema.SchemaApply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 잡의 migrations 가 pgroll 파일일 때. pgroll 실행은 가짜다 (실제 CLI 규칙은 lily-cicd 와 같다) */
class PgrollPipelineTest {

    private static final Map<String, String> DB_ENV = Map.of(
            "DB_URL", "jdbc:postgresql://172.17.0.1:25432/blog", "DB_USERNAME", "blog", "DB_PASSWORD", "pw");
    private static final Map<String, String> PGROLL = Map.of(
            "01_create_posts.yaml", "operations: []", "02_add_slug.yaml", "operations: []");

    @TempDir
    Path root;

    private final OnPremPipelineTest.FakeRuntime runtime = new OnPremPipelineTest.FakeRuntime();
    private final OnPremPipelineTest.FakeSwitch traffic = new OnPremPipelineTest.FakeSwitch();
    private final OnPremPipelineTest.FakeReady ready = new OnPremPipelineTest.FakeReady(traffic);
    private final SlotBook slots = new SlotBook();
    private final PublicAddress addresses = app -> "https://blog.lilycloud.kr";
    private final FakePgroll pgroll = new FakePgroll();
    private OnPremPipeline pipeline;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        pipeline = new OnPremPipeline(job -> root, new StackAnalyzer(), runtime, ready, traffic, addresses, slots,
                18080, 18081, job -> DB_ENV, SchemaApply.NONE, CandidateJudge.PASS, DeployedApp.IGNORE, pgroll);
    }

    @Test
    void pgroll_파일이면_시작한_버전_스키마로_후보를_띄우고_전환_뒤_롤백_창을_연다() {
        pgroll.next = new PgrollStep.Started("02_add_slug", true, List.of("schema: pgroll start 02_add_slug (active)"));

        JobRecord record = run(job("job1", PGROLL));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(runtime.lastEnv)
                .containsEntry("DB_URL", "jdbc:postgresql://172.17.0.1:25432/blog?currentSchema=public_02_add_slug")
                .containsEntry("LILY_DB_SCHEMA", "public_02_add_slug")
                .containsEntry("SPRING_FLYWAY_ENABLED", "false");
        assertThat(pgroll.calls).containsExactly("start previousLive=false", "opened 02_add_slug");
        assertThat(record.getLogs()).anyMatch(line -> line.contains("pgroll start 02_add_slug"));
    }

    @Test
    void 헬스가_실패하면_후보를_내린_뒤_시작한_pgroll_마이그레이션을_되돌린다() {
        pgroll.next = new PgrollStep.Started("02_add_slug", true, List.of());
        ready.fail = true;

        JobRecord record = run(job("job1", PGROLL));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(record.getLogs()).anyMatch(line -> line.contains("schema reverted (pgroll rollback 02_add_slug)"));
        assertThat(pgroll.calls).containsExactly("start previousLive=false", "revert 02_add_slug");
        assertThat(runtime.calls.indexOf("stop blog-blue")).isGreaterThan(-1);
        assertThat(traffic.port).isEqualTo(-1);
    }

    @Test
    void 이번_잡이_시작하지_않은_마이그레이션은_실패해도_되돌리지_않는다() {
        pgroll.next = new PgrollStep.Started("02_add_slug", false, List.of());
        ready.fail = true;

        JobRecord record = run(job("job1", PGROLL));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(pgroll.calls).containsExactly("start previousLive=false");
    }

    @Test
    void 마이그레이션_파일이_없어도_pgroll로_관리하던_DB면_최신_버전으로_접속한다() {
        pgroll.latest = Optional.of("02_add_slug");

        JobRecord record = run(job("job1", Map.of()));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(runtime.lastEnv).containsEntry("LILY_DB_SCHEMA", "public_02_add_slug");
        assertThat(pgroll.calls).isEmpty();
    }

    @Test
    void 롤백_창_안이면_이전_슬롯으로_되돌린_뒤_pgroll_rollback한다() {
        pgroll.next = new PgrollStep.Started("01_create_posts", true, List.of());
        run(job("job1", Map.of("01_create_posts.yaml", "operations: []")));
        pgroll.next = new PgrollStep.Started("02_add_slug", true, List.of());
        run(job("job2", PGROLL));
        pgroll.calls.clear();

        JobRecord rollback = new JobRecord("rb1", "blog");
        pipeline.rollback(rollback, "blog", ignored -> { });

        assertThat(rollback.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(rollback.getLogs()).anyMatch(line -> line.contains("rollback: slot=blue schema=reverted"));
        assertThat(pgroll.calls).containsExactly("blocker 02_add_slug", "rolledBack 02_add_slug");
        assertThat(runtime.lastEnv).containsEntry("LILY_DB_SCHEMA", "public_01_create_posts");
        assertThat(runtime.calls.lastIndexOf("stop blog-green")).isGreaterThan(-1);
        assertThat(traffic.port).isEqualTo(18080);
    }

    @Test
    void 롤백_창이_닫혔으면_롤백을_거절하고_트래픽을_그대로_둔다() {
        pgroll.next = new PgrollStep.Started("01_create_posts", true, List.of());
        run(job("job1", Map.of("01_create_posts.yaml", "operations: []")));
        pgroll.next = new PgrollStep.Started("02_add_slug", true, List.of());
        run(job("job2", PGROLL));
        pgroll.blocked = Optional.of("롤백 창이 지나 pgroll 02_add_slug 이 complete 됐다");

        JobRecord rollback = new JobRecord("rb1", "blog");
        pipeline.rollback(rollback, "blog", ignored -> { });

        assertThat(rollback.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(rollback.getLogs()).anyMatch(line -> line.contains("rollback refused, traffic unchanged"));
        assertThat(traffic.port).isEqualTo(18081);
        assertThat(pgroll.calls).doesNotContain("rolledBack 02_add_slug");
    }

    private JobRecord run(DeployJob job) {
        JobRecord record = new JobRecord(job);
        pipeline.execute(record, job, ignored -> { });
        return record;
    }

    private static DeployJob job(String id, Map<String, String> migrations) {
        return new DeployJob(id, "https://github.com/acme/blog", "main", null, "blog", 8080,
                "/health", null, null, Map.of(), "postgres", null, migrations).normalize();
    }

    static final class FakePgroll implements PgrollStep {
        final List<String> calls = new ArrayList<>();
        Started next;
        Optional<String> latest = Optional.empty();
        Optional<String> blocked = Optional.empty();

        @Override
        public boolean handles(Map<String, String> migrations) {
            return migrations.keySet().stream().anyMatch(name -> name.endsWith(".yaml"));
        }

        @Override
        public Started start(DeployJob job, Map<String, String> agentEnv, boolean previousLive) {
            calls.add("start previousLive=" + previousLive);
            return next;
        }

        @Override
        public Optional<String> latest(Map<String, String> agentEnv) {
            return latest;
        }

        @Override
        public void revert(String app, Map<String, String> agentEnv, String migration) {
            calls.add("revert " + migration);
        }

        @Override
        public void opened(String app, String migration, Map<String, String> agentEnv) {
            calls.add("opened " + migration);
        }

        @Override
        public Optional<String> rollbackBlocker(String app, String migration) {
            calls.add("blocker " + migration);
            return blocked;
        }

        @Override
        public void rolledBack(String app, String migration) {
            calls.add("rolledBack " + migration);
        }
    }
}
