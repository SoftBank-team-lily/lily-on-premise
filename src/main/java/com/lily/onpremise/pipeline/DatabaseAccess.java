package com.lily.onpremise.pipeline;

import com.lily.onpremise.job.DeployJob;

import java.util.Map;

/** 배포할 앱의 DB 접속 환경변수를 준비한다 (DeployJob.database 가 있을 때만 호출) */
public interface DatabaseAccess {

    DatabaseAccess NONE = new DatabaseAccess() {
        @Override
        public Map<String, String> prepare(DeployJob job) {
            throw new IllegalStateException("DB 연동이 설정되지 않았습니다 (lily.agent.database.*)");
        }

        @Override
        public boolean ready() {
            return false;
        }
    };

    Map<String, String> prepare(DeployJob job);

    /** database 가 있는 잡을 지금 받을 수 있다 (hello 의 database) */
    default boolean ready() {
        return true;
    }

    /**
     * 에이전트가 직접 붙을 때(스키마 적용) 쓸 접속 정보. 앱 컨테이너와 주소가 다를 때만 바꾼다
     * (사용자 DB 가 localhost 면 앱은 host.docker.internal 로 붙는다)
     */
    default Map<String, String> agentEnv(DeployJob job, Map<String, String> appEnv) {
        return appEnv;
    }

    /**
     * 에이전트가 다시 떠서 이미 떠 있던 앱 슬롯을 다시 붙였다 ({@code SlotRecovery}). 그 앱이 쓰는 터널을 다시 연다.
     * 터널은 배포할 때만 열려서, 다시 열지 않으면 앱은 떠 있어도 DB 에 붙지 못한다
     *
     * @param appEnv 다시 붙인 앱 컨테이너의 환경변수
     */
    default void resume(Map<String, String> appEnv) {
    }
}
