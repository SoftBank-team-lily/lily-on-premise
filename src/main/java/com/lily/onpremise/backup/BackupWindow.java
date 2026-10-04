package com.lily.onpremise.backup;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 다음 백업의 후보 창과 요청 기준을 고른다.
 *
 * <p>마감은 마지막 백업 + 주기다. 백업한 적이 없으면 마감은 지금이다 (사본이 없으면 장애 전환이 의미가 없다).
 * 지금부터 마감 전까지의 시간 칸 중 분당 평균이 가장 낮은 곳이 후보 창이다. 기록이 쌓인 만큼 단계가 오른다.
 *
 * <ul>
 *   <li>단계 0 (기록 1일 미만): 마감 전까지 전체, 기준은 최솟값</li>
 *   <li>단계 1 (1일 이상): 시간대(24칸) 평균</li>
 *   <li>단계 2 (7일 이상): 요일 × 시간(168칸) 평균</li>
 * </ul>
 *
 * 지난 덤프가 50분을 넘었으면 창은 연속 2시간이다.
 */
public final class BackupWindow {

    static final long DAY_MINUTES = 24 * 60;
    static final long WEEK_MINUTES = 7 * DAY_MINUTES;
    static final Duration LONG_DUMP = Duration.ofMinutes(50);
    private static final int MAX_HOURS = 7 * 24;

    private BackupWindow() {
    }

    /**
     * @param start     창 시작 (포함)
     * @param end       창 끝 (제외). 마감을 넘지 않는다
     * @param threshold 창 안에서 실행하려면 최근 분마다 이 값 이하여야 한다
     * @param deadline  이 시각이 지나면 조건 없이 백업한다
     */
    public record Plan(int stage, ZonedDateTime start, ZonedDateTime end, double threshold, Instant deadline) {

        public boolean contains(Instant at) {
            return !at.isBefore(start.toInstant()) && at.isBefore(end.toInstant());
        }
    }

    /**
     * @param lastBackup  마지막 백업 (또는 변경이 없어 건너뛴) 시각. 없으면 null
     * @param longestDump 최근 덤프 중 가장 오래 걸린 시간. 없으면 null
     */
    public static Plan plan(RequestProfile profile, BackupPolicy policy, Instant now, Instant lastBackup,
                            Duration longestDump) {
        Instant deadline = lastBackup == null ? now : lastBackup.plus(policy.interval());
        int stage = stage(profile);
        ZonedDateTime local = now.atZone(policy.zone());
        ZonedDateTime until = deadline.atZone(policy.zone());
        if (!deadline.isAfter(now)) {
            return new Plan(stage, local, local, policy.minThreshold(), deadline);
        }
        if (stage == 0) {
            return new Plan(stage, local, until, policy.minThreshold(), deadline);
        }

        ZonedDateTime first = local.truncatedTo(ChronoUnit.HOURS);
        int hours = (int) Math.min(MAX_HOURS, (Duration.between(first, until).toMinutes() + 59) / 60);
        int width = Math.min(hours, longestDump != null && longestDump.compareTo(LONG_DUMP) > 0 ? 2 : 1);

        int best = -1;
        double bestScore = Double.MAX_VALUE;
        double bestPeak = 0;
        for (int i = 0; i + width <= hours; i++) {
            double score = 0;
            double peak = 0;
            for (int j = i; j < i + width; j++) {
                double mean = mean(profile, stage, first.plusHours(j));
                score += mean;
                peak = Math.max(peak, mean);
            }
            if (!Double.isNaN(score) && score < bestScore) {
                best = i;
                bestScore = score;
                bestPeak = peak;
            }
        }
        if (best < 0) {
            // 앞으로의 칸에 기록이 하나도 없다. 단계 0 과 같이 고른다
            return new Plan(stage, local, until, policy.minThreshold(), deadline);
        }
        ZonedDateTime start = first.plusHours(best);
        ZonedDateTime end = start.plusHours(width);
        if (end.isAfter(until)) {
            end = until;
        }
        double threshold = Math.max(policy.minThreshold(), bestPeak * policy.thresholdFactor());
        return new Plan(stage, start, end, threshold, deadline);
    }

    static int stage(RequestProfile profile) {
        long minutes = profile.totalMinutes();
        if (minutes >= WEEK_MINUTES) {
            return 2;
        }
        return minutes >= DAY_MINUTES ? 1 : 0;
    }

    private static double mean(RequestProfile profile, int stage, ZonedDateTime hour) {
        return stage == 2 ? profile.mean(hour.getDayOfWeek(), hour.getHour()) : profile.mean(hour.getHour());
    }
}
