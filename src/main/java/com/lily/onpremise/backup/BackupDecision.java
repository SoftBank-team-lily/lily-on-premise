package com.lily.onpremise.backup;

import java.time.Instant;
import java.util.List;

/**
 * 지금 백업할지 정한다. 입력만 보고 정하는 순수 함수다.
 *
 * <p>순서: 대상·주기 → 덤프 중 → 실패 뒤 대기 → 변경 없음(SKIP) → 마감(FORCE) → 후보 창 → 요청 수 → CPU → DB 연결 → RUN.
 * 마감이 지나면 창·요청·CPU·연결을 보지 않는다. 하루 한 번(주기 한 번)은 반드시 백업한다.
 */
public final class BackupDecision {

    public enum Action {
        /** 할 일 없음 (대상 아님, 꺼짐, 덤프 중) */
        NONE,
        /** 조건이 맞을 때까지 기다린다 */
        WAIT,
        /** 마지막 백업 뒤 변경이 없다. 백업하지 않고 마감 시계를 새로 시작한다 */
        SKIP,
        RUN,
        /** 마감이 지나 조건 없이 백업한다 */
        FORCE
    }

    public record Result(Action action, String reason) {
    }

    /**
     * @param target            HYBRID 이고 DB 가 이 PC 에 있는 앱
     * @param running           덤프가 진행 중
     * @param retryAfter        덤프가 실패한 뒤 이 시각까지 다시 하지 않는다. 없으면 null
     * @param changed           마지막 백업 뒤 DB 변경이 있다
     * @param lastMinutes       끝난 분의 요청 수, 오래된 것부터 (마지막이 가장 최근)
     * @param cpuPercent        앱 컨테이너 CPU. 알 수 없으면 null
     * @param activeConnections 앱 DB 활성 연결 수. 알 수 없으면 null
     */
    public record Inputs(boolean target, boolean running, Instant retryAfter, boolean changed, Instant now,
                         BackupWindow.Plan plan, List<Integer> lastMinutes, Double cpuPercent,
                         Integer activeConnections) {
    }

    private BackupDecision() {
    }

    public static Result decide(Inputs in, BackupPolicy policy) {
        if (!in.target()) {
            return new Result(Action.NONE, "대상 앱이 아니다 (HYBRID 이고 DB 가 이 PC 에 있어야 한다)");
        }
        if (!policy.enabled()) {
            return new Result(Action.NONE, "백업 주기가 0 이라 꺼져 있다");
        }
        if (in.running()) {
            return new Result(Action.NONE, "덤프 중");
        }
        if (in.retryAfter() != null && in.now().isBefore(in.retryAfter())) {
            return new Result(Action.WAIT, "실패 뒤 대기 (" + in.retryAfter() + " 까지)");
        }
        if (!in.changed()) {
            return new Result(Action.SKIP, "마지막 백업 뒤 변경 없음");
        }
        if (!in.now().isBefore(in.plan().deadline())) {
            return new Result(Action.FORCE, "마감이 지났다");
        }
        if (!in.plan().contains(in.now())) {
            return new Result(Action.WAIT, "후보 창 밖");
        }
        List<Integer> minutes = in.lastMinutes();
        if (minutes.size() < policy.quietMinutes()) {
            return new Result(Action.WAIT, "요청 기록이 " + policy.quietMinutes() + "분이 안 된다");
        }
        for (int count : minutes.subList(minutes.size() - policy.quietMinutes(), minutes.size())) {
            if (count > in.plan().threshold()) {
                return new Result(Action.WAIT, "요청 많음 (분당 " + count + "건, 기준 " + format(in.plan().threshold()) + ")");
            }
        }
        if (in.cpuPercent() == null) {
            return new Result(Action.WAIT, "CPU 를 확인하지 못했다");
        }
        if (in.cpuPercent() > policy.maxCpuPercent()) {
            return new Result(Action.WAIT, "CPU 높음 (" + format(in.cpuPercent()) + "%)");
        }
        if (in.activeConnections() == null) {
            return new Result(Action.WAIT, "DB 연결 수를 확인하지 못했다");
        }
        if (in.activeConnections() > policy.maxActiveConnections()) {
            return new Result(Action.WAIT, "DB 연결 많음 (" + in.activeConnections() + "개)");
        }
        return new Result(Action.RUN, "후보 창 안, 요청·CPU·DB 연결이 기준 이하");
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format("%.1f", value);
    }
}
