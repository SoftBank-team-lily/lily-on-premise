package com.lily.onpremise.cutover;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.lily.onpremise.burst.BurstClient;
import com.lily.onpremise.burst.BurstGate;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.CloudflareClient;
import com.lily.onpremise.expose.CloudflareHostnameProvisioner;
import com.lily.onpremise.job.DeployJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HomeCutoverTest {

    private static final String TUNNEL = "tid.cfargotunnel.com";
    private static final String ORIGIN = "alb.example.net";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeDns api = new FakeDns();
    private final FakeBurst client = new FakeBurst();
    private final RecordingBurst burst = new RecordingBurst(client);
    private final List<String> stops = new ArrayList<>();
    private final long[] now = {10_000};
    private final ManualLater later = new ManualLater();
    private boolean reachable = true;
    private boolean publicOk = true;
    private boolean deployOk = true;
    private boolean dockerOk = true;
    private int deploys;
    private HomeCutover cutover;

    @BeforeEach
    void setUp() {
        CloudflareHostnameProvisioner dns = new CloudflareHostnameProvisioner(api, cloudflare());
        dns.openTunnel("ab12");
        cutover = new HomeCutover(
                properties(ORIGIN), dns, client, burst,
                job -> {
                    deploys++;
                    return deployOk;
                },
                () -> dockerOk,
                url -> publicOk,
                (ingress, host) -> reachable,
                () -> "blog-blue",
                stops::add,
                later,
                () -> now[0],
                millis -> now[0] += 200_000,
                null);
        cutover.note(job("job1", "postgres", null), true);
    }

    @Test
    void 준비_확인_전에_CNAME_을_바꾸지_않는다() {
        reachable = false;

        cutover.begin("blog", "cloud");
        assertThatThrownBy(() -> cutover.perform()).hasMessageContaining("Pod");

        assertThat(api.puts).isEmpty();
        assertThat(api.content).isEqualTo(TUNNEL);
        assertThat(cutover.status().phase()).isEqualTo("ONPREM");
        assertThat(client.scales).containsExactly(2, 1);
    }

    @Test
    void CNAME_변경이_실패하면_대기_레플리카와_거점으로_돌아간다() {
        api.failPut = true;
        cutover.begin("blog", "cloud");

        assertThatThrownBy(() -> cutover.perform()).hasMessageContaining("CNAME");

        assertThat(api.content).isEqualTo(TUNNEL);
        assertThat(cutover.status().phase()).isEqualTo("ONPREM");
        assertThat(client.scales).containsExactly(2, 1);
        assertThat(stops).isEmpty();
    }

    @Test
    void 공개_확인이_실패하면_이전_CNAME_으로_되돌린다() {
        publicOk = false;
        cutover.begin("blog", "cloud");

        assertThatThrownBy(() -> cutover.perform()).hasMessageContaining("공개 주소");

        assertThat(api.puts).containsExactly(ORIGIN, TUNNEL);
        assertThat(api.content).isEqualTo(TUNNEL);
        assertThat(cutover.status().phase()).isEqualTo("ONPREM");
        assertThat(stops).isEmpty();
        assertThat(later.task).isNull();
    }

    @Test
    void 클라우드로_옮기면_버스팅_스케일을_멈춘다() {
        cutover.begin("blog", "cloud");
        cutover.perform();

        assertThat(api.content).isEqualTo(ORIGIN);
        assertThat(cutover.status().phase()).isEqualTo("CLOUD");
        assertThat(cutover.status().cname()).isEqualTo(ORIGIN);
        assertThat(burst.parked).isTrue();
        assertThat(burst.gate.getAsBoolean()).isFalse();
        assertThat(stops).isEmpty();

        later.task.run();
        assertThat(stops).containsExactly("blog-blue");
    }

    @Test
    void 기억한_앱이_없으면_컨트롤_플레인이_준_앱의_CNAME_으로_거점을_복구한다() {
        api.content = ORIGIN;
        CloudflareHostnameProvisioner dns = new CloudflareHostnameProvisioner(api, cloudflare());
        dns.openTunnel("ab12");
        HomeCutover fresh = new HomeCutover(
                properties(ORIGIN), dns, client, burst, job -> true, () -> true, url -> true,
                (ingress, host) -> true, () -> null, stops::add, later, () -> now[0],
                millis -> now[0] += 200_000, null);

        fresh.restore("blog");

        assertThat(fresh.status().phase()).isEqualTo("CLOUD");
        assertThat(burst.parked).isTrue();
        assertThatThrownBy(() -> fresh.begin("blog", "onprem")).hasMessageContaining("다시 배포");
    }

    @Test
    void DB_없는_앱도_옮긴다() {
        cutover.note(job("job1", null, "FROM scratch\n"), false);

        cutover.begin("blog", "cloud");
        cutover.perform();

        assertThat(api.content).isEqualTo(ORIGIN);
        assertThat(cutover.status().phase()).isEqualTo("CLOUD");
    }

    @Test
    void 클라우드_오리진이_없으면_거절하고_플랫폼이_주면_옮긴다() {
        CloudflareHostnameProvisioner dns = new CloudflareHostnameProvisioner(api, cloudflare());
        dns.openTunnel("ab12");
        HomeCutover platform = new HomeCutover(
                properties(""), dns, null, burst, job -> true, () -> true, url -> true,
                (ingress, host) -> true, () -> "blog-blue", stops::add, later, () -> now[0],
                millis -> now[0] += 200_000, null);
        platform.note(job("job1", "postgres", null), true);

        assertThat(platform.movable()).isFalse();
        assertThatThrownBy(() -> platform.begin("blog", "cloud")).hasMessageContaining("오리진");

        platform.platform(client, "10.0.0.1", 80, ORIGIN + ".");
        assertThat(platform.movable()).isTrue();
        platform.begin("blog", "cloud");
        platform.perform();

        assertThat(api.content).isEqualTo(ORIGIN);
        assertThat(platform.status().phase()).isEqualTo("CLOUD");
    }

    @Test
    void Dockerfile_이_없으면_CNAME_을_호출하지_않는다() {
        cutover.note(job("job1", "postgres", null), false);

        assertThatThrownBy(() -> cutover.begin("blog", "cloud")).hasMessageContaining("Dockerfile");
        assertThat(api.puts).isEmpty();
    }

    @Test
    void 잡_id_가_다르면_CNAME_을_호출하지_않는다() {
        cutover.noteCloud(job("other", "postgres", null));

        assertThatThrownBy(() -> cutover.begin("blog", "cloud")).hasMessageContaining("잡");
        assertThat(api.puts).isEmpty();
        assertThat(client.standbys).isEmpty();
    }

    @Test
    void 클라우드에서_로컬_배포가_실패하면_CNAME_은_오리진이다() {
        moveToCloud();
        int puts = api.puts.size();
        deployOk = false;

        cutover.begin("blog", "onprem");
        assertThatThrownBy(() -> cutover.perform()).hasMessageContaining("로컬 배포");

        assertThat(api.puts).hasSize(puts);
        assertThat(api.content).isEqualTo(ORIGIN);
        assertThat(cutover.status().phase()).isEqualTo("CLOUD");
        assertThat(deploys).isEqualTo(1);
    }

    @Test
    void 온프레미스로_옮기면_삼십초_뒤에_클라우드를_대기_수로_내린다() {
        moveToCloud();
        client.scales.clear();

        cutover.begin("blog", "onprem");
        cutover.perform();

        assertThat(api.content).isEqualTo(TUNNEL);
        assertThat(cutover.status().phase()).isEqualTo("ONPREM");
        assertThat(burst.gate.getAsBoolean()).isTrue();
        assertThat(client.scales).isEmpty();

        assertThat(burst.resumed).isTrue();

        later.task.run();
        assertThat(client.scales).containsExactly(1);
    }

    @Test
    void Docker_가_없으면_온프레미스_전환을_거절한다() {
        moveToCloud();
        dockerOk = false;

        assertThatThrownBy(() -> cutover.begin("blog", "onprem")).hasMessageContaining("Docker");
        assertThat(deploys).isZero();
    }

    private void moveToCloud() {
        cutover.begin("blog", "cloud");
        cutover.perform();
        later.task = null;
    }

    private static DeployJob job(String id, String database, String dockerfile) {
        return new DeployJob(id, "https://github.com/acme/blog", "main", null, "blog", 8080,
                "/health", null, dockerfile, Map.of(), database, "/ready", Map.of()).normalize();
    }

    private static AgentProperties properties(String origin) {
        return new AgentProperties(
                "", "", 8099, 18080, 18081, 180, "", "",
                cloudflare(),
                new AgentProperties.Burst(true, "http://builder", "token", "127.0.0.1", 80, "",
                        8, 3, 30, 2, 1, ""),
                new AgentProperties.Database("", "lily-tunnel", "", "", 5432, "172.17.0.1", 15432),
                new AgentProperties.Cutover(origin));
    }

    private static AgentProperties.Cloudflare cloudflare() {
        return new AgentProperties.Cloudflare(false, "", "secret", "acct", "zone", "lily.dev");
    }

    private static final class ManualLater implements HomeCutover.Later {
        private Runnable task;

        @Override
        public void after(long delayMillis, Runnable task) {
            this.task = task;
        }
    }

    private static final class RecordingBurst implements BurstGate {
        private final BurstClient client;
        private java.util.function.BooleanSupplier gate = () -> true;
        private boolean parked;
        private boolean resumed;

        private RecordingBurst(BurstClient client) {
            this.client = client;
        }

        @Override
        public void resume() {
            resumed = true;
        }

        @Override
        public String standby(DeployJob job, String publicHost) {
            return client.standby(job, publicHost, job.database());
        }

        @Override
        public void allowScale(java.util.function.BooleanSupplier gate) {
            this.gate = gate;
        }

        @Override
        public void whenStandby(Consumer<com.lily.onpremise.job.DeployJob> listener) {
        }

        @Override
        public void park() {
            parked = true;
        }
    }

    private static final class FakeBurst extends BurstClient {
        private final List<Integer> scales = new ArrayList<>();
        private final List<String> standbys = new ArrayList<>();
        private int ready = 1;

        private FakeBurst() {
            super(new AgentProperties.Burst(true, "http://builder", "token", "127.0.0.1", 80, "",
                    8, 3, 30, 2, 1, ""), new ObjectMapper());
        }

        @Override
        public String standby(DeployJob job, String host, String database) {
            standbys.add(job.id());
            return "b1";
        }

        @Override
        public BuildState build(String id) {
            return new BuildState("SUCCEEDED", "");
        }

        @Override
        public AppState app(String appName) {
            int replicas = scales.isEmpty() ? 0 : scales.get(scales.size() - 1);
            return new AppState(replicas, ready);
        }

        @Override
        public AppState scale(String appName, int replicas) {
            scales.add(replicas);
            return new AppState(replicas, ready);
        }
    }

    private static final class FakeDns implements CloudflareClient {
        private final List<String> puts = new ArrayList<>();
        private String content = TUNNEL;
        private boolean failPut;

        @Override
        public JsonNode call(String method, String path, JsonNode body) {
            if (method.equals("GET") && path.contains("/cfd_tunnel?")) {
                return MAPPER.createArrayNode();
            }
            if (method.equals("POST") && path.endsWith("/cfd_tunnel")) {
                return MAPPER.createObjectNode().put("id", "tid").put("token", "created-token");
            }
            if (path.endsWith("/token")) {
                return TextNode.valueOf("issued-token");
            }
            if (path.endsWith("/configurations")) {
                return MAPPER.createObjectNode();
            }
            if (method.equals("GET") && path.contains("/dns_records")) {
                return MAPPER.createArrayNode().add(MAPPER.createObjectNode()
                        .put("id", "rec").put("content", content).put("proxied", true));
            }
            if (path.contains("/dns_records")) {
                puts.add(body.path("content").asText());
                if (failPut) {
                    throw new IllegalStateException("put failed");
                }
                content = body.path("content").asText();
                return MAPPER.createObjectNode().put("id", "rec");
            }
            if (path.contains("/ssl/certificate_packs")) {
                return MAPPER.createArrayNode();
            }
            throw new IllegalStateException("unexpected " + method + " " + path);
        }
    }
}
