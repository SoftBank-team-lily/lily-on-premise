package com.lily.onpremise.backup;

import java.time.Duration;
import java.time.ZoneId;

/**
 * PC DB → 클라우드 사본 백업의 기준값.
 *
 * @param intervalHours        백업 사이 최대 간격. 0 이면 백업을 끈다
 * @param quietMinutes         실행 전 요청 수가 기준 이하로 이어져야 하는 분
 * @param minThreshold         분당 요청 기준의 최솟값 (기록이 없거나 평균이 작을 때)
 * @param thresholdFactor      후보 창 평균에 곱해 기준을 만든다
 * @param maxCpuPercent        앱 컨테이너 CPU 상한 (코어 하나 기준)
 * @param maxActiveConnections 앱 DB 활성 연결 상한
 * @param zone                 후보 창을 고르는 시간대
 */
public record BackupPolicy(int intervalHours, int quietMinutes, int minThreshold, double thresholdFactor,
                           int maxCpuPercent, int maxActiveConnections, ZoneId zone) {

    public static BackupPolicy defaults() {
        return new BackupPolicy(24, 10, 5, 1.5, 50, 2, ZoneId.of("Asia/Seoul"));
    }

    public boolean enabled() {
        return intervalHours > 0;
    }

    public Duration interval() {
        return Duration.ofHours(intervalHours);
    }
}
