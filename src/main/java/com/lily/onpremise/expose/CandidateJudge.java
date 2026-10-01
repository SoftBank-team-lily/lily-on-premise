package com.lily.onpremise.expose;

import java.util.Optional;

/**
 * 프록시를 옮기기 전에 후보 슬롯만 판정한다.
 * 거절 이유가 있으면 후보는 지우고 upstream 은 그대로 둔다. 첫 배포는 호출하지 않는다.
 */
@FunctionalInterface
public interface CandidateJudge {

    CandidateJudge PASS = (candidatePort, activePort, path) -> Optional.empty();

    /** @return 거절 이유. 통과면 empty */
    Optional<String> reject(int candidatePort, int activePort, String path);
}
