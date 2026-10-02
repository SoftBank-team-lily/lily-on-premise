package com.lily.onpremise.burst;

import com.lily.onpremise.job.DeployJob;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 거점 전환이 버스팅의 스케일을 멈추고, 대기 배포가 끝난 잡을 알게 한다 */
public interface BurstGate {

    void allowScale(BooleanSupplier gate);

    void whenStandby(Consumer<DeployJob> listener);

    void park();

    /** 거점이 온프레미스로 돌아왔다. 버스팅이 켜져 있으면 대기 Pod 로 다시 넘긴다 */
    void resume();

    /**
     * 클라우드에 같은 잡을 대기 배포한다 (레플리카 0). DB 위치가 이 PC 면 역방향 터널 접속 정보로 보낸다.
     * @return builder 빌드 id
     */
    String standby(DeployJob job, String publicHost);

    /** 거점 전환이 클라우드 대기 배포의 DB 를 바꿨다가 되돌린다. 버스팅이 켜져 있으면 이 잡으로 다시 대기 배포한다 */
    default void standbyAgain(DeployJob job) {
    }

    /** 참이면 거점을 옮기는 중이다. 그동안 화면에서 버스팅을 켜고 끄지 않는다 (대기 배포가 겹친다) */
    default void holdChanges(BooleanSupplier moving) {
    }

    /** 버스팅이 클라우드에 대기 배포를 하는 중이다. 그동안 거점을 옮기지 않는다 */
    default boolean busy() {
        return false;
    }
}
