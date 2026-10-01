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
}
