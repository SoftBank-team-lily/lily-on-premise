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

    /** 대기 배포까지 끝난 상태 (WARMING 또는 warm 0 이면 IDLE) */
    private CloudBurst deployed(int warmReplicas) throws InterruptedException {
        CloudBurst burst = new CloudBurst(settings(warmReplicas), null, proxy, client,
                (ingress, host) -> podReachable);
        burst.onDeployed(new DeployJob("j1", "https://github.com/a/b", "main", null, "blog", 8080,
                "/health", null, null, Map.of()));
        for (int i = 0; i < 100 && burst.status().phase() == CloudBurst.Phase.STANDBY; i++) {
            Thread.sleep(10);
        }
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
        return new AgentProperties.Burst(true, "http://builder", "t", "127.0.0.1", 80, "{app}.example.com",
                LIMIT, 3, 30, 2, warmReplicas, "");
    }

    /** lily-builder 대신. 스케일 요청을 기록하고 Ready 수는 테스트가 정한다 */
    private static final class FakeClient extends BurstClient {
        final List<Integer> scales = new ArrayList<>();
        volatile int ready;

        FakeClient() {
            super(settings(1), new ObjectMapper());
        }

        @Override
        public String standby(DeployJob job, String host, String database) {
            return "b1";
        }

        @Override
        public BuildState build(String id) {
            return new BuildState("SUCCEEDED", "");
        }

        @Override
        public AppState app(String appName) {
            return new AppState(scales.isEmpty() ? 0 : scales.get(scales.size() - 1), ready);
        }

        @Override
        public synchronized AppState scale(String appName, int replicas) {
            scales.add(replicas);
            return new AppState(replicas, ready);
        }
    }
}
