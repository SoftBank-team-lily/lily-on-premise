package com.lily.onpremise.backup;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 실행 직전 확인에 쓰는 PC 부하. 요청은 적어도 PC 가 다른 일로 바쁠 수 있어서 직접 본다.
 * 읽지 못한 값은 null 이다 ({@link BackupDecision} 은 모르는 값이면 기다린다).
 */
public final class PcLoad {

    /**
     * @param cpuPercent        앱 컨테이너 CPU (코어 하나 기준)
     * @param activeConnections 앱 DB 에서 쿼리를 실행 중인 연결 수
     */
    public record Reading(Double cpuPercent, Integer activeConnections) {
    }

    private final Supplier<Optional<Double>> cpu;
    private final LocalDbStats db;

    /**
     * @param cpu 지금 트래픽을 받는 앱 컨테이너의 CPU ({@code ContainerStats.usage()})
     */
    public PcLoad(Supplier<Optional<Double>> cpu, LocalDbStats db) {
        this.cpu = cpu;
        this.db = db;
    }

    public Reading read(String app) {
        Double percent = cpu.get().orElse(null);
        Integer active;
        try {
            active = db.activeConnections(app);
        } catch (RuntimeException e) {
            active = null;
        }
        return new Reading(percent, active);
    }
}
