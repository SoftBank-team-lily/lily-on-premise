package com.lily.onpremise.backup;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

class BackupWindowTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BackupPolicy POLICY = BackupPolicy.defaults();
    /** 2026-10-03 토요일 20:00 KST */
    private static final ZonedDateTime SAT_20 = ZonedDateTime.of(2026, 10, 3, 20, 0, 0, 0, KST);

    @Test
    void 기록이_1일이_안_되면_마감_전까지_전체가_창이고_기준은_최솟값이다() {
        RequestProfile profile = new RequestProfile();
        fill(profile, DayOfWeek.SATURDAY, 10, 3, 60);

        BackupWindow.Plan plan = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.minusHours(2).toInstant(), null);

        assertThat(plan.stage()).isZero();
        assertThat(plan.start()).isEqualTo(SAT_20);
        assertThat(plan.end()).isEqualTo(SAT_20.plusHours(22));
        assertThat(plan.threshold()).isEqualTo(5.0);
        assertThat(plan.deadline()).isEqualTo(SAT_20.plusHours(22).toInstant());
    }

    @Test
    void 백업한_적이_없으면_마감은_지금이다() {
        BackupWindow.Plan plan = BackupWindow.plan(new RequestProfile(), POLICY, SAT_20.toInstant(), null, null);

        assertThat(plan.deadline()).isEqualTo(SAT_20.toInstant());
        assertThat(plan.contains(SAT_20.toInstant())).isFalse();
    }

    @Test
    void 기록이_1일이면_시간대_평균이_가장_낮은_한_시간을_고른다() {
        RequestProfile profile = new RequestProfile();
        for (int hour = 0; hour < 24; hour++) {
            fill(profile, DayOfWeek.FRIDAY, hour, hour == 3 ? 10 : 30, 60);
        }

        BackupWindow.Plan plan = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.toInstant(), null);

        assertThat(plan.stage()).isEqualTo(1);
        assertThat(plan.start()).isEqualTo(SAT_20.plusDays(1).withHour(3));
        assertThat(plan.end()).isEqualTo(SAT_20.plusDays(1).withHour(4));
        // 기준 = 그 칸 평균 10 × 1.5
        assertThat(plan.threshold()).isEqualTo(15.0);
    }

    @Test
    void 칸_평균이_작으면_기준은_최솟값이다() {
        RequestProfile profile = new RequestProfile();
        for (int hour = 0; hour < 24; hour++) {
            fill(profile, DayOfWeek.FRIDAY, hour, hour == 3 ? 1 : 30, 60);
        }

        BackupWindow.Plan plan = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.toInstant(), null);

        assertThat(plan.threshold()).isEqualTo(5.0);
    }

    @Test
    void 기록이_7일이면_요일별_칸으로_고른다() {
        RequestProfile profile = new RequestProfile();
        for (DayOfWeek day : DayOfWeek.values()) {
            for (int hour = 0; hour < 24; hour++) {
                int perMinute = 20;
                if (hour == 3) {
                    perMinute = day == DayOfWeek.SUNDAY ? 40 : 0;
                } else if (hour == 5 && day == DayOfWeek.SUNDAY) {
                    perMinute = 1;
                }
                fill(profile, day, hour, perMinute, 60);
            }
        }
        // 시간대 평균으로는 03시(5.7)가 05시(17.3)보다 낮지만, 일요일만 보면 05시(1)가 03시(40)보다 낮다
        BackupWindow.Plan plan = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.toInstant(), null);

        assertThat(plan.stage()).isEqualTo(2);
        assertThat(plan.start()).isEqualTo(SAT_20.plusDays(1).withHour(5));
    }

    @Test
    void 지난_덤프가_50분을_넘으면_연속_2시간이_가장_한가한_곳을_고른다() {
        RequestProfile profile = new RequestProfile();
        for (int hour = 0; hour < 24; hour++) {
            int perMinute = switch (hour) {
                case 1 -> 1;
                case 3, 4 -> 2;
                default -> 30;
            };
            fill(profile, DayOfWeek.FRIDAY, hour, perMinute, 60);
        }

        BackupWindow.Plan oneHour = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.toInstant(),
                Duration.ofMinutes(20));
        BackupWindow.Plan twoHours = BackupWindow.plan(profile, POLICY, SAT_20.toInstant(), SAT_20.toInstant(),
                Duration.ofMinutes(55));

        assertThat(oneHour.start().getHour()).isEqualTo(1);
        assertThat(twoHours.start().getHour()).isEqualTo(3);
        assertThat(twoHours.end().getHour()).isEqualTo(5);
    }

    @Test
    void 주기가_짧으면_주기_안의_시간에서만_고른다() {
        BackupPolicy sixHours = new BackupPolicy(6, 10, 5, 1.5, 50, 2, KST);
        RequestProfile profile = new RequestProfile();
        for (int hour = 0; hour < 24; hour++) {
            int perMinute = switch (hour) {
                case 3 -> 0;
                case 22 -> 8;
                default -> 30;
            };
            fill(profile, DayOfWeek.FRIDAY, hour, perMinute, 60);
        }

        BackupWindow.Plan plan = BackupWindow.plan(profile, sixHours, SAT_20.toInstant(), SAT_20.toInstant(), null);

        assertThat(plan.start()).isEqualTo(SAT_20.withHour(22));
        assertThat(plan.deadline()).isEqualTo(SAT_20.plusHours(6).toInstant());
    }

    @Test
    void 창은_마감을_넘지_않는다() {
        RequestProfile profile = new RequestProfile();
        for (int hour = 0; hour < 24; hour++) {
            fill(profile, DayOfWeek.FRIDAY, hour, hour == 20 ? 0 : 30, 60);
        }
        Instant now = SAT_20.plusMinutes(10).toInstant();
        Instant last = SAT_20.minusHours(24).plusMinutes(40).toInstant();

        BackupWindow.Plan plan = BackupWindow.plan(profile, POLICY, now, last, null);

        assertThat(plan.start()).isEqualTo(SAT_20);
        assertThat(plan.end()).isEqualTo(SAT_20.plusMinutes(40));
    }

    /** 그 요일·시간 칸에 분당 perMinute 건을 minutes 분 더한다 */
    private static void fill(RequestProfile profile, DayOfWeek day, int hour, int perMinute, int minutes) {
        ZonedDateTime at = LocalDate.of(2026, 9, 28).with(TemporalAdjusters.nextOrSame(day)).atStartOfDay(KST).withHour(hour);
        for (int i = 0; i < minutes; i++) {
            profile.add(at.plusMinutes(i), perMinute);
        }
    }
}
