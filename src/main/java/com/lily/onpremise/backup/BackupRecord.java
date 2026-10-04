package com.lily.onpremise.backup;

import java.time.Duration;
import java.time.Instant;

/**
 * 백업 한 번의 결과.
 *
 * @param forced  마감이 지나 조건 없이 실행했다
 * @param message 실패 이유나 건너뛴 이유. 성공이면 null
 */
public record BackupRecord(Instant startedAt, Instant endedAt, Outcome outcome, boolean forced, String message) {

    public enum Outcome { OK, FAILED, SKIPPED }

    public Duration took() {
        return startedAt == null || endedAt == null ? Duration.ZERO : Duration.between(startedAt, endedAt);
    }
}
