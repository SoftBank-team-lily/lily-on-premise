package com.lily.onpremise.burst;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lily.onpremise.config.AgentProperties;
import com.lily.onpremise.expose.UpstreamProxy;
import com.lily.onpremise.job.DeployJob;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudBurstTest {

    private static final int LIMIT = 4;

    private final FakeClient client = new FakeClient();
    private final UpstreamProxy proxy = new UpstreamProxy(0);
    private boolean podReachable = true;
    /** 대기 배포 완료 시각이 실제 시계라 tick 시각도 그 기준으로 준다 */
    private final long t0 = System.currentTimeMillis();

    @Test
    void 온프레미스_전용은_대기_배포와_스케일을_호출하지_않는다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        DeployJob job = new DeployJob("j1", "https://github.com/a/b", "main", null, "blog", 8080,
                "/health", null, null, Map.of()).withDeploymentMode("ONPREM_ONLY");

        burst.onDeployed(job);
        Thread.sleep(50);
        burst.tick(idle(), at(1));

        assertThat(client.standbys).isEmpty();
        assertThat(client.scales).isEmpty();
        assertThat(burst.status().enabled()).isFalse();
        assertThat(burst.status().deploymentMode()).isEqualTo("ONPREM_ONLY");
    }

    @Test
    void 대기_배포가_끝나면_대기_Pod_를_띄우고_닿으면_바로_넘김을_켠다() throws Exception {
        CloudBurst burst = deployed(1);

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        assertThat(client.scales).containsExactly(1);
        assertThat(proxy.overflowing()).isFalse();

        client.ready = 1;
        burst.tick(idle(), at(1));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(burst.status().warm()).isTrue();
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 재시작_뒤_다시_붙인_앱은_남은_대기_배포로_넘김을_다시_켠다() {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);

        burst.recover("blog");
        burst.tick(idle(), at(1));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        assertThat(burst.status().appName()).isEqualTo("blog");
        assertThat(client.scales).containsExactly(1);
        assertThat(client.standbys).isEmpty();

        client.ready = 1;
        burst.tick(idle(), at(2));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(burst.status().warm()).isTrue();
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 재시작_뒤_클라우드_대기_배포가_없으면_다음_배포를_기다린다() {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        client.missing = true;

        burst.recover("blog");
        burst.tick(idle(), at(1));
        burst.tick(idle(), at(2));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);
        assertThat(client.scales).isEmpty();
        assertThat(proxy.overflowing()).isFalse();
    }

    @Test
    void 재시작_뒤라도_버스팅이_꺼져_있으면_건드리지_않는다() {
        CloudBurst burst = new CloudBurst(settings(1, false), null, proxy, client, (ingress, host) -> podReachable);

        burst.recover("blog");
        burst.tick(idle(), at(1));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);
        assertThat(client.scales).isEmpty();
    }

    @Test
    void Ingress_가_아직_Pod_로_못_보내면_넘김을_켜지_않는다() throws Exception {
        CloudBurst burst = deployed(1);
        client.ready = 1;
        podReachable = false;

        burst.tick(idle(), at(1));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        assertThat(proxy.overflowing()).isFalse();
    }

    @Test
    void 대기_배포가_끝나면_리스너_뒤에도_대기_Pod_를_띄운다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        List<String> seen = new ArrayList<>();
        burst.whenStandby(job -> seen.add(job.id()));

        burst.onDeployed(new DeployJob("j1", "https://github.com/a/b", "main", null, "blog", 8080,
                "/health", null, null, Map.of()));
        for (int i = 0; i < 100 && burst.status().phase() == CloudBurst.Phase.STANDBY; i++) {
            Thread.sleep(10);
        }

        assertThat(seen).containsExactly("j1");
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        assertThat(client.scales).containsExactly(1);

        client.ready = 1;
        burst.tick(idle(), at(1));
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 스케일이_막혀_있으면_틱이_레플리카를_바꾸지_않는다() throws Exception {
        CloudBurst burst = warmed();
        int before = client.scales.size();

        burst.allowScale(() -> false);
        burst.tick(saturated(1), at(10));
        burst.tick(saturated(2), at(20));

        assertThat(client.scales).hasSize(before);
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
    }

    @Test
    void 대기_Pod_가_있으면_과부하_때_SCALING_없이_바로_OVERFLOWING() throws Exception {
        CloudBurst burst = warmed();

        burst.tick(saturated(1), at(10));
        burst.tick(saturated(2), at(13));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OVERFLOWING);
        assertThat(client.scales).containsExactly(1, 2);
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 한가해지면_대기_Pod_수로_내리고_넘김은_유지한다() throws Exception {
        CloudBurst burst = warmed();
        burst.tick(saturated(1), at(10));
        burst.tick(saturated(2), at(13));

        burst.tick(quiet(2), at(14));
        burst.tick(quiet(2), at(44));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(client.scales).containsExactly(1, 2, 1);
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 대기_Pod_가_시간_안에_안_뜨면_0_으로_내리고_예전_방식으로_동작한다() throws Exception {
        CloudBurst burst = deployed(1);

        burst.tick(idle(), at(181));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(burst.status().warm()).isFalse();
        assertThat(client.scales).containsExactly(1, 0);

        burst.tick(saturated(1), at(190));
        burst.tick(saturated(2), at(193));
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.SCALING);
        assertThat(proxy.overflowing()).isFalse();
    }

    @Test
    void warmReplicas_0_이면_대기_Pod_없이_예전처럼_동작한다() throws Exception {
        CloudBurst burst = deployed(0);

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(client.scales).isEmpty();

        burst.tick(saturated(1), at(10));
        burst.tick(saturated(2), at(13));
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.SCALING);

        client.ready = 2;
        burst.tick(saturated(3), at(14));
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OVERFLOWING);

        burst.tick(quiet(3), at(15));
        burst.tick(quiet(3), at(45));
        assertThat(client.scales).containsExactly(2, 0);
        assertThat(proxy.overflowing()).isFalse();
    }

    @Test
    void 끄면_넘김을_멈추고_클라우드를_0_으로_내린다() throws Exception {
        CloudBurst burst = warmed();

        burst.apply("blog", false, 0);

        assertThat(burst.status().enabled()).isFalse();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);
        assertThat(proxy.overflowing()).isFalse();
        assertThat(client.scales).containsExactly(1, 0);

        // 꺼져 있으면 배포가 끝나도 대기 배포하지 않는다
        burst.onDeployed(job("j2"));
        assertThat(client.standbys).containsExactly("j1@blog.example.com");
    }

    @Test
    void 다시_켜면_마지막_배포로_대기_배포하고_비율을_프록시에_건다() throws Exception {
        CloudBurst burst = warmed();
        burst.apply("blog", false, 0);

        burst.apply("blog", true, 30);
        waitPast(burst, CloudBurst.Phase.STANDBY);

        assertThat(client.standbys).containsExactly("j1@blog.example.com", "j1@blog.example.com");
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        assertThat(burst.status().cloudPercent()).isEqualTo(30);
        assertThat(proxy.cloudShare()).isEqualTo(30);
    }

    @Test
    void 다른_앱_설정은_무시한다() throws Exception {
        CloudBurst burst = warmed();

        burst.apply("other", false, 50);

        assertThat(burst.status().enabled()).isTrue();
        assertThat(proxy.cloudShare()).isZero();
    }

    @Test
    void 처음에_꺼져_있으면_배포만_기억하고_켤_때_대기_배포한다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1, false), null, proxy, client, (ingress, host) -> podReachable);
        burst.onDeployed(job("j1"));
        assertThat(client.standbys).isEmpty();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);

        burst.apply("", true, 0);
        waitPast(burst, CloudBurst.Phase.STANDBY);

        assertThat(client.standbys).containsExactly("j1@blog.example.com");
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
    }

    @Test
    void 플랫폼이_준_존과_Ingress_로_대기_배포한다() throws Exception {
        AgentProperties.Burst bare = new AgentProperties.Burst(false, "", "", "", 80, "", LIMIT, 3, 30, 2, 1, "");
        CloudBurst burst = new CloudBurst(bare, null, proxy, null, (ingress, host) -> podReachable);
        assertThat(burst.available()).isFalse();

        burst.platform(client, "10.0.0.1", 80, "lilycloud.kr");
        burst.apply("", true, 0);
        burst.onDeployed(job("j1"));
        waitPast(burst, CloudBurst.Phase.STANDBY);

        assertThat(burst.available()).isTrue();
        assertThat(client.standbys).containsExactly("j1@blog.lilycloud.kr");
    }

    @Test
    void 거점이_돌아오면_대기_Pod_로_다시_넘긴다() throws Exception {
        CloudBurst burst = warmed();
        burst.park();
        assertThat(proxy.overflowing()).isFalse();

        burst.resume();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.WARMING);
        burst.tick(idle(), System.currentTimeMillis());

        assertThat(proxy.overflowing()).isTrue();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
    }

    @Test
    void 비율로_나누는_중에는_클라우드_요청이_있어도_한가하면_내린다() throws Exception {
        CloudBurst burst = warmed();
        burst.apply("blog", true, 50);
        burst.tick(saturated(1), at(10));
        burst.tick(saturated(2), at(13));
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OVERFLOWING);

        UpstreamProxy.Pressure sharing = new UpstreamProxy.Pressure(1, 3, LIMIT, 2, 50, 0);
        burst.tick(sharing, at(14));
        burst.tick(sharing, at(44));

        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.IDLE);
        assertThat(client.scales).containsExactly(1, 2, 1);
        assertThat(proxy.overflowing()).isTrue();
    }

    @Test
    void 대기_배포_중에_거점이_클라우드로_바뀌면_끝나도_레플리카를_내리지_않는다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        boolean[] onPrem = {true};
        burst.allowScale(() -> onPrem[0]);
        burst.onDeployed(job("j1"));
        onPrem[0] = false;
        waitPast(burst, CloudBurst.Phase.STANDBY);

        assertThat(client.scales).isEmpty();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);
    }

    @Test
    void 거점이_클라우드면_버스팅을_켜지_않는다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1, false), null, proxy, client, (ingress, host) -> podReachable);
        burst.onDeployed(job("j1"));
        burst.allowScale(() -> false);

        burst.apply("blog", true, 0);

        assertThat(burst.status().enabled()).isFalse();
        assertThat(client.standbys).isEmpty();
        assertThat(burst.status().events().get(0)).contains("공개 주소가 클라우드");
    }

    @Test
    void 거점이_클라우드면_다시_배포해도_대기_배포하지_않는다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        burst.allowScale(() -> false);

        burst.onDeployed(job("j1"));

        assertThat(client.standbys).isEmpty();
        assertThat(burst.status().phase()).isEqualTo(CloudBurst.Phase.OFF);
    }

    @Test
    void 거점을_옮기는_중에는_켜고_끄지_않는다() throws Exception {
        CloudBurst burst = warmed();
        burst.holdChanges(() -> true);

        burst.apply("blog", false, 0);

        assertThat(burst.status().enabled()).isTrue();
        assertThat(burst.busy()).isFalse();
        assertThat(burst.status().events().get(0)).contains("거점을 옮기는 중");
    }

    @Test
    void 대기_배포_중이면_바쁘고_빌드_id_를_알린다() throws Exception {
        CloudBurst burst = new CloudBurst(settings(1), null, proxy, client, (ingress, host) -> podReachable);
        assertThat(burst.busy()).isFalse();
        burst.onDeployed(job("j1"));
        waitPast(burst, CloudBurst.Phase.STANDBY);

        assertThat(burst.status().standbyBuild()).isEqualTo("b1");
        assertThat(burst.status().phaseSince()).isPositive();
    }

    private static DeployJob job(String id) {
        return new DeployJob(id, "https://github.com/a/b", "main", null, "blog", 8080,
                "/health", null, null, Map.of());
    }

    private static void waitPast(CloudBurst burst, CloudBurst.Phase phase) throws InterruptedException {
        for (int i = 0; i < 200 && burst.status().phase() == phase; i++) {
            Thread.sleep(10);
        }
    }

    /** 대기 배포까지 끝난 상태 (WARMING 또는 warm 0 이면 IDLE) */
    private CloudBurst deployed(int warmReplicas) throws InterruptedException {
        CloudBurst burst = new CloudBurst(settings(warmReplicas), null, proxy, client,
                (ingress, host) -> podReachable);
        burst.onDeployed(job("j1"));
        waitPast(burst, CloudBurst.Phase.STANDBY);
        return burst;
    }

    /** 대기 Pod 가 떠서 넘김이 켜진 IDLE */
    private CloudBurst warmed() throws InterruptedException {
        CloudBurst burst = deployed(1);
        client.ready = 1;
        burst.tick(idle(), at(1));
        return burst;
    }

    private long at(int seconds) {
        return t0 + seconds * 1000L;
    }

    private static UpstreamProxy.Pressure idle() {
        return new UpstreamProxy.Pressure(0, 0, LIMIT, 0, 0, 0);
    }

    /** 로컬이 한도에 닿은 채로 요청이 계속 들어온다. saturatedTotal 은 누적이라 매번 늘려 준다 */
    private static UpstreamProxy.Pressure saturated(long total) {
        return new UpstreamProxy.Pressure(LIMIT, 1, LIMIT, total, total, 0);
    }

    private static UpstreamProxy.Pressure quiet(long total) {
        return new UpstreamProxy.Pressure(0, 0, LIMIT, total, total, 0);
    }

    private static AgentProperties.Burst settings(int warmReplicas) {
        return settings(warmReplicas, true);
    }

    private static AgentProperties.Burst settings(int warmReplicas, boolean enabled) {
        return new AgentProperties.Burst(enabled, "http://builder", "t", "127.0.0.1", 80, "{app}.example.com",
                LIMIT, 3, 30, 2, warmReplicas, "");
    }

    /** lily-builder 대신. 스케일 요청을 기록하고 Ready 수는 테스트가 정한다 */
    private static final class FakeClient extends BurstClient {
        final List<Integer> scales = new ArrayList<>();
        volatile int ready;
        /** 클라우드에 대기 배포가 없다 (builder 가 404) */
        volatile boolean missing;

        FakeClient() {
            super(settings(1), new ObjectMapper());
        }

        final List<String> standbys = new ArrayList<>();

        @Override
        public synchronized String standby(DeployJob job, String host, String database,
                                           Map<String, String> databaseEnv) {
            standbys.add(job.id() + "@" + host);
            return "b1";
        }

        @Override
        public BuildState build(String id) {
            return new BuildState("SUCCEEDED", "");
        }

        @Override
        public AppState app(String appName) {
            if (missing) {
                throw new IllegalStateException("GET /api/burst/apps/" + appName + " -> 404");
            }
            return new AppState(scales.isEmpty() ? 0 : scales.get(scales.size() - 1), ready);
        }

        @Override
        public synchronized AppState scale(String appName, int replicas) {
            scales.add(replicas);
            return new AppState(replicas, ready);
        }
    }
}
