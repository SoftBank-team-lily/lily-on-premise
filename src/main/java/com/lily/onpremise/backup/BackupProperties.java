package com.lily.onpremise.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * 하이브리드 앱(DB 가 이 PC)의 PC DB → 클라우드 사본 백업. {@link BackupPolicy} 의 값들이다.
 *
 * @param intervalHours 백업 사이 최대 간격. 0 이면 끈다
 */
@ConfigurationProperties("lily.agent.backup")
public record BackupProperties(
        @DefaultValue("24") int intervalHours,
        @DefaultValue("10") int quietMinutes,
        @DefaultValue("5") int minThreshold,
        @DefaultValue("1.5") double thresholdFactor,
        @DefaultValue("50") int maxCpuPercent,
        @DefaultValue("2") int maxActiveConnections,
        @DefaultValue("Asia/Seoul") String zone) {

    public BackupPolicy policy() {
        return new BackupPolicy(Math.max(0, intervalHours), Math.max(1, quietMinutes), Math.max(0, minThreshold),
                thresholdFactor, maxCpuPercent, maxActiveConnections, ZoneId.of(zone));
    }
}
