package com.lily.onpremise.backup;

import com.lily.onpremise.backup.BackupDecision.Action;
import com.lily.onpremise.backup.BackupDecision.Inputs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupDecisionTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final BackupPolicy POLICY = BackupPolicy.defaults();
    private static final ZonedDateTime START = ZonedDateTime.of(2026, 10, 4, 3, 0, 0, 0, KST);
    private static final Instant IN_WINDOW = START.plusMinutes(20).toInstant();
    /** 03:00~04:00 창, 기준 7, 마감은 그날 20:00 */
    private static final BackupWindow.Plan PLAN =
            new BackupWindow.Plan(1, START, START.plusHours(1), 7, START.withHour(20).toInstant());
    private static final List<Integer> QUIET = Collections.nCopies(10, 2);

    @Test
    void 조건이_모두_맞으면_RUN() {
        assertThat(decide(inputs())).isEqualTo(Action.RUN);
    }

    @Test
    void 대상_앱이_아니거나_주기가_0이면_NONE() {
        Inputs notTarget = new Inputs(false, false, null, true, IN_WINDOW, PLAN, QUIET, 10.0, 0);
        BackupPolicy off = new BackupPolicy(0, 10, 5, 1.5, 50, 2, KST);

        assertThat(decide(notTarget)).isEqualTo(Action.NONE);
        assertThat(BackupDecision.decide(inputs(), off).action()).isEqualTo(Action.NONE);
    }

    @Test
    void 덤프_중이면_NONE() {
        assertThat(decide(new Inputs(true, true, null, true, IN_WINDOW, PLAN, QUIET, 10.0, 0))).isEqualTo(Action.NONE);
    }

    @Test
    void 실패_뒤_대기_중이면_마감이_지나도_WAIT() {
        Instant late = START.withHour(21).toInstant();

        assertThat(decide(new Inputs(true, false, late.plusSeconds(60), true, late, PLAN, QUIET, 10.0, 0)))
                .isEqualTo(Action.WAIT);
        assertThat(decide(new Inputs(true, false, late.minusSeconds(60), true, late, PLAN, QUIET, 10.0, 0)))
                .isEqualTo(Action.FORCE);
    }

    @Test
    void 변경이_없으면_마감이_지나도_SKIP() {
        Instant late = START.withHour(21).toInstant();

        assertThat(decide(new Inputs(true, false, null, false, late, PLAN, QUIET, 10.0, 0))).isEqualTo(Action.SKIP);
    }

    @Test
    void 마감이_지나면_창_밖이고_바빠도_FORCE() {
        Instant late = START.withHour(21).toInstant();
        List<Integer> busy = Collections.nCopies(10, 100);

        assertThat(decide(new Inputs(true, false, null, true, late, PLAN, busy, 99.0, 50))).isEqualTo(Action.FORCE);
        assertThat(decide(new Inputs(true, false, null, true, late, PLAN, List.of(), null, null))).isEqualTo(Action.FORCE);
    }

    @Test
    void 후보_창_밖이면_WAIT() {
        Instant before = START.minusMinutes(1).toInstant();

        BackupDecision.Result result = BackupDecision.decide(
                new Inputs(true, false, null, true, before, PLAN, QUIET, 10.0, 0), POLICY);

        assertThat(result.action()).isEqualTo(Action.WAIT);
        assertThat(result.reason()).contains("창 밖");
    }

    @Test
    void 최근_10분_중_한_분이라도_기준을_넘으면_WAIT() {
        List<Integer> spike = new java.util.ArrayList<>(QUIET);
        spike.set(9, 8);
        // 10분보다 오래된 분은 보지 않는다
        List<Integer> oldSpike = new java.util.ArrayList<>(List.of(100));
        oldSpike.addAll(QUIET);

        BackupDecision.Result result = BackupDecision.decide(
                new Inputs(true, false, null, true, IN_WINDOW, PLAN, spike, 10.0, 0), POLICY);

        assertThat(result.action()).isEqualTo(Action.WAIT);
        assertThat(result.reason()).contains("요청 많음", "8건", "기준 7");
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, oldSpike, 10.0, 0)))
                .isEqualTo(Action.RUN);
    }

    @Test
    void 요청_기록이_10분이_안_되면_WAIT() {
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, Collections.nCopies(9, 0), 10.0, 0)))
                .isEqualTo(Action.WAIT);
    }

    @Test
    void CPU_가_높거나_모르면_WAIT() {
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 51.0, 0))).isEqualTo(Action.WAIT);
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, null, 0))).isEqualTo(Action.WAIT);
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 50.0, 0))).isEqualTo(Action.RUN);
    }

    @Test
    void DB_활성_연결이_많거나_모르면_WAIT() {
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 10.0, 3))).isEqualTo(Action.WAIT);
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 10.0, null))).isEqualTo(Action.WAIT);
        assertThat(decide(new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 10.0, 2))).isEqualTo(Action.RUN);
    }

    private static Inputs inputs() {
        return new Inputs(true, false, null, true, IN_WINDOW, PLAN, QUIET, 10.0, 0);
    }

    private static Action decide(Inputs inputs) {
        return BackupDecision.decide(inputs, POLICY).action();
    }
}
