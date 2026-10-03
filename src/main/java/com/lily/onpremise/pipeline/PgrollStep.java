package com.lily.onpremise.pipeline;

import com.lily.onpremise.job.DeployJob;

import java.util.Map;
import java.util.Optional;

/**
 * pgroll 무중단 스키마 변경 (PostgreSQL). 잡의 migrations 가 pgroll 파일({@code NN_설명.yaml|json})일 때 쓴다.
 *
 * <pre>
 * 배포   이전 active complete → start → 후보 슬롯은 public_{새 이름} 으로 접속 → Ready → 판정 → 전환 → 롤백 창
 * 실패   전환 전이면 후보를 내린 뒤 rollback
 * 롤백   (창 안) 이전 슬롯 복구 → 전환 → 현재 슬롯 내림 → rollback
 * 종료   창이 지나거나 다음 배포가 오면 complete
 * </pre>
 */
public interface PgrollStep {

    PgrollStep NONE = new PgrollStep() {
        @Override
        public boolean handles(Map<String, String> migrations) {
            return false;
        }

        @Override
        public Started start(DeployJob job, Map<String, String> agentEnv, boolean previousLive) {
            throw new IllegalStateException("이 에이전트는 pgroll 마이그레이션을 받지 않는다");
        }

        @Override
        public Optional<String> latest(Map<String, String> agentEnv) {
            return Optional.empty();
        }

        @Override
        public void revert(String app, Map<String, String> agentEnv, String migration) {
        }

        @Override
        public void opened(String app, String migration, Map<String, String> agentEnv) {
        }

        @Override
        public Optional<String> rollbackBlocker(String app, String migration) {
            return Optional.of("이 에이전트는 pgroll 을 쓰지 않는다");
        }

        @Override
        public void rolledBack(String app, String migration) {
        }
    };

    /** pgroll 파일이 있다. SQL 과 섞여 있으면 IllegalArgumentException */
    boolean handles(Map<String, String> migrations);

    /**
     * 앱 DB 에 pgroll 을 켜고(DB 위치마다) 이번 잡의 마이그레이션을 시작한다. 실패하면 DB 는 이전 그대로다.
     *
     * @param previousLive 이전 슬롯이 트래픽을 받고 있다 (그러면 새 마이그레이션은 하나만)
     */
    Started start(DeployJob job, Map<String, String> agentEnv, boolean previousLive);

    /** 마이그레이션 파일이 없는 잡도 pgroll 로 관리하던 DB 면 최신 버전으로 붙는다. 아니면 빈 값 */
    Optional<String> latest(Map<String, String> agentEnv);

    /** 전환 전 실패. 후보 컨테이너를 내린 뒤에 부른다 */
    void revert(String app, Map<String, String> agentEnv, String migration);

    /** 전환 뒤. 여기부터 롤백 창이다 (에이전트가 다시 떠도 이어 간다) */
    void opened(String app, String migration, Map<String, String> agentEnv);

    /** 롤백 API 전에 본다. 롤백 창이 닫혔으면(complete) 이유 */
    Optional<String> rollbackBlocker(String app, String migration);

    /** 롤백 API 로 이전 슬롯에 트래픽을 옮기고 현재 슬롯을 내린 뒤 */
    void rolledBack(String app, String migration);

    /**
     * @param migration 후보 슬롯이 접속할 마이그레이션 (버전 스키마 public_{migration})
     * @param started   이번 잡이 시작했다 (실패하면 되돌린다)
     * @param logs      잡 상태 줄로 올릴 진행 기록
     */
    record Started(String migration, boolean started, java.util.List<String> logs) {
    }
}
