package com.lily.onpremise.burst;

import com.lily.onpremise.job.DeployJob;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 거점 전환이 버스팅의 스케일을 멈추고, 대기 배포가 끝난 잡을 알게 한다 */
public interface BurstGate {

    void allowScale(BooleanSupplier gate);

    void whenStandby(Consumer<DeployJob> listener);

    void park();
}
