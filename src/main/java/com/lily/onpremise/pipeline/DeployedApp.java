package com.lily.onpremise.pipeline;

import com.lily.onpremise.job.DeployJob;

/** 온프레미스 배포가 끝난 뒤 거점 전환이 그 잡과 Dockerfile 출처를 기억한다 */
@FunctionalInterface
public interface DeployedApp {

    void note(DeployJob job, boolean repoDockerfile);

    DeployedApp IGNORE = (job, repoDockerfile) -> {
    };
}
