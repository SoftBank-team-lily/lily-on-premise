package com.lily.onpremise.pipeline;

import com.lily.onpremise.analyze.StackAnalyzer;
import com.lily.onpremise.expose.PublicAddress;
import com.lily.onpremise.expose.Readiness;
import com.lily.onpremise.expose.TrafficSwitch;
import com.lily.onpremise.job.DeployJob;
import com.lily.onpremise.job.JobRecord;
import com.lily.onpremise.runtime.ContainerRuntime;
import com.lily.onpremise.runtime.SlotBook;
import com.lily.onpremise.source.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ready 전에는 프록시를 옮기지 않고, 옮긴 뒤에는 이전 슬롯만 내린다. */
class OnPremPipelineTest {

    @TempDir
    Path root;

    private final FakeRuntime runtime = new FakeRuntime();
    private final FakeSwitch traffic = new FakeSwitch();
    private final FakeReady ready = new FakeReady(traffic);
    private final SlotBook slots = new SlotBook();
    private final PublicAddress addresses = app -> "https://app.trycloudflare.com";
    private final OnPremPipeline pipeline = new OnPremPipeline(
            job -> root, new StackAnalyzer(), runtime, ready, traffic, addresses, slots, 18080, 18081);

    @Test
    void 첫_배포는_blue_가_Ready_된_뒤에만_공개_주소를_옮긴다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        DeployJob job = job("blog", null, null);

        JobRecord record = run(job);

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(record.getUrl()).isEqualTo("https://app.trycloudflare.com");
        assertThat(record.getActiveSlot()).isEqualTo("blue");
        assertThat(ready.port).isEqualTo(18080);
        assertThat(ready.path).isEqualTo("/actuator/health/readiness");
        assertThat(traffic.port).isEqualTo(18080);
        assertThat(runtime.calls).containsExactly("build", "start blog-blue");
        assertThat(runtime.lastEnv).containsEntry("APP_COLOR", "blue").containsEntry("APP_VERSION", "job1");
        assertThat(Files.readString(root.resolve("Dockerfile"))).contains("bootJar");
        assertThat(record.getLogs()).noneMatch(line -> line.contains("ghp_secret"));
    }

    @Test
    void 다음_배포는_green_으로_갈아타고_이전_슬롯을_내린다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");

        run(job("blog", null, null));
        JobRecord second = run(new DeployJob(
                "job2", "https://github.com/acme/blog", "main", null, "blog", 8080,
                null, null, null, Map.of()).normalize());

        assertThat(second.getActiveSlot()).isEqualTo("green");
        assertThat(traffic.port).isEqualTo(18081);
        assertThat(runtime.calls).contains("start blog-green", "stop blog-blue");
        assertThat(runtime.calls.indexOf("start blog-green")).isLessThan(runtime.calls.lastIndexOf("stop blog-blue"));
    }

    @Test
    void 헬스가_실패하면_후보만_지우고_프록시는_그대로다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        ready.fail = true;

        JobRecord record = run(job("blog", null, null));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(record.getLogs()).anyMatch(line -> line.contains("traffic unchanged"));
        assertThat(traffic.port).isEqualTo(-1);
        assertThat(runtime.calls).contains("start blog-blue", "stop blog-blue");
        assertThat(slots.active("blog")).isEmpty();
    }

    @Test
    void 프록시_전환이_실패하면_후보를_지운다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        traffic.fail = true;

        JobRecord record = run(job("blog", null, null));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(record.getLogs()).anyMatch(line -> line.contains("candidate removed"));
        assertThat(traffic.port).isEqualTo(-1);
        assertThat(runtime.calls).contains("stop blog-blue");
    }

    @Test
    void 호스트이름_발급이_실패하면_후보를_지우고_프록시는_그대로다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        PublicAddress failing = app -> {
            throw new IllegalStateException("dns failed");
        };
        OnPremPipeline broken = new OnPremPipeline(
                job -> root, new StackAnalyzer(), runtime, ready, traffic, failing, new SlotBook(), 18080, 18081);

        JobRecord record = recordOf(broken, job("blog", null, null));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.FAILED);
        assertThat(record.getLogs()).anyMatch(line -> line.contains("hostname failed"));
        assertThat(traffic.port).isEqualTo(-1);
        assertThat(runtime.calls).contains("stop blog-blue");
    }

    @Test
    void 이전_슬롯을_내리다_실패해도_새_주소는_유지한다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        run(job("blog", null, null));
        runtime.failStop = "blog-blue";

        JobRecord second = run(new DeployJob(
                "job2", "https://github.com/acme/blog", "main", null, "blog", 8080,
                null, null, null, Map.of()).normalize());

        assertThat(second.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(second.getActiveSlot()).isEqualTo("green");
        assertThat(traffic.port).isEqualTo(18081);
    }

    @Test
    void 다른_앱이_오면_이전_앱_슬롯을_비운다() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        run(job("blog", null, null));

        JobRecord shop = run(new DeployJob(
                "job2", "https://github.com/acme/shop", "main", null, "shop", 8080,
                null, null, null, Map.of()).normalize());

        assertThat(shop.getActiveSlot()).isEqualTo("green");
        assertThat(traffic.port).isEqualTo(18081);
        assertThat(runtime.calls).contains("start shop-green", "stop blog-blue");
        assertThat(runtime.calls.indexOf("start shop-green")).isLessThan(runtime.calls.lastIndexOf("stop blog-blue"));
        assertThat(slots.active("blog")).isEmpty();
    }

    @Test
    void 레포의_Dockerfile_은_덮어쓰지_않는다() throws Exception {
        Files.writeString(root.resolve("Dockerfile"), "FROM scratch\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");

        JobRecord record = run(job("blog", "/actuator/health/readiness", null));

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(Files.readString(root.resolve("Dockerfile"))).isEqualTo("FROM scratch\n");
        assertThat(record.getLogs()).anyMatch(line -> line.contains("existing"));
    }

    @Test
    void 잡에_실린_Dockerfile_이_레포_파일보다_우선한다() throws Exception {
        Files.writeString(root.resolve("Dockerfile"), "FROM scratch\n");

        run(job("blog", "/", "FROM node:22\n"));

        assertThat(Files.readString(root.resolve("Dockerfile"))).isEqualTo("FROM node:22\n");
    }

    @Test
    void 하위_폴더를_빌드_컨텍스트로_쓴다() throws Exception {
        Path backend = Files.createDirectories(root.resolve("backend"));
        Files.writeString(backend.resolve("package.json"), "{}");
        Workspace workspace = job -> root;
        OnPremPipeline nested = new OnPremPipeline(
                workspace, new StackAnalyzer(), runtime, ready, traffic, addresses, new SlotBook(), 18080, 18081);

        JobRecord record = recordOf(nested, new DeployJob(
                "job1", "https://github.com/acme/blog", "main", null, "blog", 3000,
                null, "backend", null, Map.of("SPRING_PROFILES_ACTIVE", "local")).normalize());

        assertThat(record.getStatus()).isEqualTo(JobRecord.Status.SUCCEEDED);
        assertThat(Files.exists(root.resolve("Dockerfile"))).isFalse();
        assertThat(Files.readString(backend.resolve("Dockerfile"))).contains("npm");
        assertThat(ready.path).isEqualTo("/");
        assertThat(runtime.lastEnv).containsEntry("SPRING_PROFILES_ACTIVE", "local");
        assertThat(runtime.lastPort).isEqualTo(18080);
        assertThat(runtime.lastContainerPort).isEqualTo(3000);
    }

    private JobRecord run(DeployJob job) {
        return recordOf(pipeline, job);
    }

    private static JobRecord recordOf(OnPremPipeline pipeline, DeployJob job) {
        JobRecord record = new JobRecord(job);
        pipeline.execute(record, job, ignored -> { });
        return record;
    }

    private static DeployJob job(String app, String health, String dockerfile) {
        return new DeployJob(
                "job1", "https://github.com/acme/blog.git", "main", "ghp_secret", app, 8080,
                health, null, dockerfile, Map.of()).normalize();
    }

    static final class FakeRuntime implements ContainerRuntime {
        final List<String> calls = new ArrayList<>();
        Map<String, String> lastEnv = Map.of();
        int lastPort;
        int lastContainerPort;
        String failStop;

        @Override
        public void build(Path context, String image) {
            calls.add("build");
        }

        @Override
        public void start(String name, String image, int hostPort, int containerPort, Map<String, String> env) {
            calls.add("start " + name);
            lastEnv = env;
            lastPort = hostPort;
            lastContainerPort = containerPort;
        }

        @Override
        public void stop(String name) {
            calls.add("stop " + name);
            if (name.equals(failStop)) {
                throw new IllegalStateException("busy");
            }
        }
    }

    static final class FakeSwitch implements TrafficSwitch {
        int port = -1;
        boolean fail;

        @Override
        public void route(int upstreamPort) {
            if (fail) {
                throw new IllegalStateException("proxy down");
            }
            port = upstreamPort;
        }

        @Override
        public int upstreamPort() {
            return port < 1 ? 0 : port;
        }

        @Override
        public String publicUrl() {
            return "https://app.trycloudflare.com";
        }
    }

    static final class FakeReady implements Readiness {
        final FakeSwitch traffic;
        int port;
        String path;
        boolean fail;

        FakeReady(FakeSwitch traffic) {
            this.traffic = traffic;
        }

        @Override
        public void await(int port, String path) {
            // 이전 배포가 프록시를 잡고 있는 것은 정상이다. 이번 후보 포트를 이미 보면 순서가 깨진 것이다.
            if (traffic.port == port) {
                throw new IllegalStateException("routed before health");
            }
            this.port = port;
            this.path = path;
            if (fail) {
                throw new IllegalStateException("ready timeout");
            }
        }
    }
}
