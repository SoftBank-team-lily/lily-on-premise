package com.lily.onpremise.cutover;

import com.lily.onpremise.job.DeployJob;

import java.util.Map;

/**
 * 거점 전환이 앱 DB 를 내 PC 와 RDS 사이에서 옮길 때 쓰는 것. 실제 구현은 {@link AgentDatabaseMove}, 테스트는 가짜다.
 */
public interface DatabaseMove {

    /**
     * 이 앱의 RDS DB 를 만들거나 찾고, 이 PC 에서 닿는 터널 주소 기준 접속 정보를 돌려준다. RDS 터널도 연다.
     */
    Map<String, String> rdsEnv(DeployJob job);

    /** 이 PC DB 의 스키마와 데이터로 RDS DB 를 덮어쓴다 */
    void toRemote(String appName, Map<String, String> rdsEnv);

    /** 켜면 이 PC 프록시가 모든 요청에 503 을 돌려주고, 처리 중인 요청이 끝날 때까지 기다린다 */
    void pause(boolean paused);
}
